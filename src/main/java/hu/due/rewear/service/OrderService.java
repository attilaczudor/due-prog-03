package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.MovementType;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.model.PackageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Coordinates B2B customer order lifecycle and atomic stock reservation/dispatch transitions.
 */
public final class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);

    private final DatabaseManager db;
    private final OrderDao orderDao;
    private final OrderItemDao orderItemDao;
    private final InventoryPackageDao packageDao;
    private final StockMovementDao movementDao;

    public OrderService(DatabaseManager db,
                        OrderDao orderDao,
                        OrderItemDao orderItemDao,
                        InventoryPackageDao packageDao,
                        StockMovementDao movementDao) {
        this.db = db;
        this.orderDao = orderDao;
        this.orderItemDao = orderItemDao;
        this.packageDao = packageDao;
        this.movementDao = movementDao;
    }

    public Order createOrder(long customerId, String notes, long userId) {
        LocalDateTime now = LocalDateTime.now();
        String cleanNotes = notes != null && !notes.isBlank() ? notes.trim() : null;
        long orderId = db.executeInTransaction(conn -> {
            String orderNumber = orderDao.generateNextOrderNumber(conn);
            return orderDao.insert(conn, orderNumber, customerId, OrderStatus.DRAFT,
                    BigDecimal.ZERO, BigDecimal.ZERO, cleanNotes, userId, now, null);
        });
        Order created = orderDao.findById(orderId).orElseThrow();
        LOG.info("Created order {} for customerId={} by userId={}", created.orderNumber(), customerId, userId);
        return created;
    }

    public Order addPackageToOrder(long orderId, long packageId, BigDecimal customPricePerKg, long userId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() != OrderStatus.DRAFT && order.status() != OrderStatus.PACKING) {
            throw new IllegalStateException("Cannot add packages to order in status: " + order.status());
        }
        InventoryPackage pkg = packageDao.findById(packageId)
                .orElseThrow(() -> new IllegalArgumentException("Package not found: " + packageId));
        if (pkg.status() != PackageStatus.IN_STOCK) {
            throw new IllegalStateException("Package " + pkg.qrCode() + " is not IN_STOCK (status: " + pkg.status() + ")");
        }

        BigDecimal unitPrice = customPricePerKg != null ? customPricePerKg : pkg.defaultPricePerKg();
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Price per kg must be >= 0");
        }
        BigDecimal lineTotal = pkg.weightKg().multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        LocalDateTime now = LocalDateTime.now();

        db.runInTransaction(conn -> {
            orderItemDao.insert(conn, orderId, packageId, unitPrice, lineTotal);
            packageDao.updateStatus(conn, packageId, PackageStatus.RESERVED, now);
            movementDao.insert(conn, packageId, MovementType.RESERVE, pkg.locationId(), pkg.locationId(),
                    userId, "Reserved for " + order.orderNumber(), now);
            recalculateTotals(conn, orderId);
        });

        LOG.info("Added package {} to order {}", pkg.qrCode(), order.orderNumber());
        return orderDao.findById(orderId).orElseThrow();
    }

    public Order removeItemFromOrder(long orderId, long orderItemId, long userId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() != OrderStatus.DRAFT && order.status() != OrderStatus.PACKING) {
            throw new IllegalStateException("Cannot modify order in status: " + order.status());
        }

        List<OrderItem> items = orderItemDao.findByOrderId(orderId);
        OrderItem target = items.stream()
                .filter(i -> i.id() == orderItemId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Order item not found: " + orderItemId));
        InventoryPackage pkg = packageDao.findById(target.packageId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();

        db.runInTransaction(conn -> {
            orderItemDao.deleteById(conn, orderItemId);
            packageDao.updateStatus(conn, pkg.id(), PackageStatus.IN_STOCK, now);
            movementDao.insert(conn, pkg.id(), MovementType.RELEASE, pkg.locationId(), pkg.locationId(),
                    userId, "Released from " + order.orderNumber(), now);
            recalculateTotals(conn, orderId);
        });

        return orderDao.findById(orderId).orElseThrow();
    }

    public Order startPacking(long orderId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() != OrderStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT orders can transition to PACKING.");
        }
        if (orderItemDao.findByOrderId(orderId).isEmpty()) {
            throw new IllegalStateException("Cannot start packing an empty order.");
        }
        db.runInTransaction(conn -> orderDao.updateStatus(conn, orderId, OrderStatus.PACKING, null));
        return orderDao.findById(orderId).orElseThrow();
    }

    public Order shipOrder(long orderId, long userId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() != OrderStatus.DRAFT && order.status() != OrderStatus.PACKING) {
            throw new IllegalStateException("Cannot ship order in status: " + order.status());
        }
        List<OrderItem> items = orderItemDao.findByOrderId(orderId);
        if (items.isEmpty()) {
            throw new IllegalStateException("Cannot ship an empty order.");
        }
        LocalDateTime now = LocalDateTime.now();

        db.runInTransaction(conn -> {
            for (OrderItem item : items) {
                InventoryPackage pkg = packageDao.findById(item.packageId()).orElseThrow();
                packageDao.updateStatus(conn, pkg.id(), PackageStatus.SHIPPED, now);
                movementDao.insert(conn, pkg.id(), MovementType.SHIP, pkg.locationId(), null,
                        userId, "Shipped on " + order.orderNumber(), now);
            }
            orderDao.updateStatus(conn, orderId, OrderStatus.SHIPPED, now);
        });

        LOG.info("Shipped order {} ({} packages) by userId={}", order.orderNumber(), items.size(), userId);
        return orderDao.findById(orderId).orElseThrow();
    }

    public Order cancelOrder(long orderId, long userId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() != OrderStatus.DRAFT && order.status() != OrderStatus.PACKING) {
            throw new IllegalStateException("Cannot cancel order in status: " + order.status());
        }
        List<OrderItem> items = orderItemDao.findByOrderId(orderId);
        LocalDateTime now = LocalDateTime.now();

        db.runInTransaction(conn -> {
            for (OrderItem item : items) {
                InventoryPackage pkg = packageDao.findById(item.packageId()).orElseThrow();
                packageDao.updateStatus(conn, pkg.id(), PackageStatus.IN_STOCK, now);
                movementDao.insert(conn, pkg.id(), MovementType.RELEASE, pkg.locationId(), pkg.locationId(),
                        userId, "Order " + order.orderNumber() + " cancelled", now);
            }
            orderDao.updateStatus(conn, orderId, OrderStatus.CANCELLED, null);
        });

        LOG.info("Cancelled order {} and released {} packages", order.orderNumber(), items.size());
        return orderDao.findById(orderId).orElseThrow();
    }

    private void recalculateTotals(Connection conn, long orderId) throws SQLException {
        String sql = """
                SELECT COALESCE(SUM(ip.weight_kg), 0) AS total_w,
                       COALESCE(SUM(oi.line_total), 0) AS total_p
                FROM order_items oi
                JOIN inventory_packages ip ON ip.id = oi.package_id
                WHERE oi.order_id = ?
                """;
        try (var ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (var rs = ps.executeQuery()) {
                BigDecimal totalW = rs.next() ? BigDecimal.valueOf(rs.getDouble("total_w")) : BigDecimal.ZERO;
                BigDecimal totalP = BigDecimal.valueOf(rs.getDouble("total_p")).setScale(2, RoundingMode.HALF_UP);
                orderDao.updateTotals(conn, orderId, totalW, totalP);
            }
        }
    }
}
