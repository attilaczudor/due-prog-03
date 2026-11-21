package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.model.CurrencyCode;
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
 * Coordinates B2B customer order lifecycle and atomic, reversible stock reservation/dispatch transitions.
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
        return createOrder(customerId, CurrencyCode.EUR, notes, userId);
    }

    public Order createOrder(long customerId, CurrencyCode currency, String notes, long userId) {
        LocalDateTime now = LocalDateTime.now();
        CurrencyCode cur = currency != null ? currency : CurrencyCode.EUR;
        String cleanNotes = notes != null && !notes.isBlank() ? notes.trim() : null;
        long orderId = db.executeInTransaction(conn -> {
            String orderNumber = orderDao.generateNextOrderNumber(conn);
            return orderDao.insert(conn, orderNumber, customerId, OrderStatus.DRAFT, cur,
                    BigDecimal.ZERO, BigDecimal.ZERO, cleanNotes, userId, now, null);
        });
        Order created = orderDao.findById(orderId).orElseThrow();
        LOG.info("Created order {} ({}) for customerId={} by userId={}", created.orderNumber(), cur, customerId, userId);
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

        CurrencyCode cur = order.effectiveCurrency();
        BigDecimal unitPrice = customPricePerKg != null ? cur.round(customPricePerKg) : pkg.effectivePricePerKg(cur);
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Price per kg must be >= 0");
        }
        BigDecimal lineTotal = cur.round(pkg.weightKg().multiply(unitPrice));
        LocalDateTime now = LocalDateTime.now();

        db.runInTransaction(conn -> {
            orderItemDao.insert(conn, orderId, packageId, unitPrice, lineTotal);
            packageDao.updateStatus(conn, packageId, PackageStatus.RESERVED, now);
            movementDao.insert(conn, packageId, MovementType.RESERVE, pkg.locationId(), pkg.locationId(),
                    userId, "Reserved for " + order.orderNumber(), now);
            recalculateTotals(conn, orderId, cur);
        });

        LOG.info("Added package {} to order {} ({} {}/kg)", pkg.qrCode(), order.orderNumber(), unitPrice, cur);
        return orderDao.findById(orderId).orElseThrow();
    }

    /**
     * Switches an open (DRAFT / PACKING) order between EUR and HUF, recalculating every allocated
     * package's unit price from its product/grade price in the target currency and updating order totals.
     */
    public Order changeOrderCurrency(long orderId, CurrencyCode targetCurrency) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() != OrderStatus.DRAFT && order.status() != OrderStatus.PACKING) {
            throw new IllegalStateException("Cannot change currency for order in status: " + order.status());
        }
        CurrencyCode cur = targetCurrency != null ? targetCurrency : CurrencyCode.EUR;
        if (order.effectiveCurrency() == cur) {
            return order;
        }
        List<OrderItem> items = orderItemDao.findByOrderId(orderId);
        db.runInTransaction(conn -> {
            orderDao.updateCurrency(conn, orderId, cur);
            for (OrderItem item : items) {
                InventoryPackage pkg = packageDao.findById(item.packageId()).orElseThrow();
                BigDecimal unitPrice = pkg.effectivePricePerKg(cur);
                BigDecimal lineTotal = cur.round(pkg.weightKg().multiply(unitPrice));
                orderItemDao.updateItemPricing(conn, item.id(), unitPrice, lineTotal);
            }
            recalculateTotals(conn, orderId, cur);
        });
        LOG.info("Changed order {} currency to {}", order.orderNumber(), cur);
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
            recalculateTotals(conn, orderId, order.effectiveCurrency());
        });

        return orderDao.findById(orderId).orElseThrow();
    }

    /**
     * Atomically transitions an order between any linear lifecycle status (DRAFT, PACKING, SHIPPED, CANCELLED),
     * including reversing SHIPPED -> PACKING/DRAFT (re-reserving packages) and restoring CANCELLED -> DRAFT/PACKING
     * when all allocated packages are still IN_STOCK.
     */
    public Order changeOrderStatus(long orderId, OrderStatus targetStatus, long userId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        OrderStatus current = order.status();
        if (current == targetStatus) {
            return order;
        }

        List<OrderItem> items = orderItemDao.findByOrderId(orderId);
        if ((targetStatus == OrderStatus.PACKING || targetStatus == OrderStatus.SHIPPED) && items.isEmpty()) {
            throw new IllegalStateException("Cannot transition an empty order to " + targetStatus);
        }

        // If restoring from CANCELLED, verify all allocated packages are still available IN_STOCK
        if (current == OrderStatus.CANCELLED) {
            for (OrderItem item : items) {
                InventoryPackage pkg = packageDao.findById(item.packageId()).orElseThrow();
                if (pkg.status() != PackageStatus.IN_STOCK) {
                    throw new IllegalStateException("Cannot restore order: package " + pkg.qrCode()
                            + " is currently " + pkg.status());
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        db.runInTransaction(conn -> {
            if (targetStatus == OrderStatus.SHIPPED) {
                for (OrderItem item : items) {
                    InventoryPackage pkg = packageDao.findById(item.packageId()).orElseThrow();
                    packageDao.updateStatus(conn, pkg.id(), PackageStatus.SHIPPED, now);
                    movementDao.insert(conn, pkg.id(), MovementType.SHIP, pkg.locationId(), null,
                            userId, "Shipped on " + order.orderNumber(), now);
                }
                orderDao.updateStatus(conn, orderId, OrderStatus.SHIPPED, now);
            } else if (targetStatus == OrderStatus.CANCELLED) {
                for (OrderItem item : items) {
                    InventoryPackage pkg = packageDao.findById(item.packageId()).orElseThrow();
                    packageDao.updateStatus(conn, pkg.id(), PackageStatus.IN_STOCK, now);
                    movementDao.insert(conn, pkg.id(), MovementType.RELEASE, pkg.locationId(), pkg.locationId(),
                            userId, "Order " + order.orderNumber() + " cancelled", now);
                }
                orderDao.updateStatus(conn, orderId, OrderStatus.CANCELLED, null);
            } else {
                // Target is DRAFT or PACKING
                if (current == OrderStatus.SHIPPED || current == OrderStatus.CANCELLED) {
                    String reason = current == OrderStatus.SHIPPED
                            ? "Shipment reverted on " + order.orderNumber()
                            : "Order " + order.orderNumber() + " restored";
                    for (OrderItem item : items) {
                        InventoryPackage pkg = packageDao.findById(item.packageId()).orElseThrow();
                        packageDao.updateStatus(conn, pkg.id(), PackageStatus.RESERVED, now);
                        movementDao.insert(conn, pkg.id(), MovementType.RESERVE, pkg.locationId(), pkg.locationId(),
                                userId, reason, now);
                    }
                }
                orderDao.updateStatus(conn, orderId, targetStatus, null);
            }
        });

        LOG.info("Order {} status changed {} -> {} by userId={}", order.orderNumber(), current, targetStatus, userId);
        return orderDao.findById(orderId).orElseThrow();
    }

    public Order startPacking(long orderId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() != OrderStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT orders can transition to PACKING.");
        }
        return changeOrderStatus(orderId, OrderStatus.PACKING, order.createdBy());
    }

    public Order shipOrder(long orderId, long userId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() != OrderStatus.DRAFT && order.status() != OrderStatus.PACKING) {
            throw new IllegalStateException("Cannot ship order in status: " + order.status());
        }
        return changeOrderStatus(orderId, OrderStatus.SHIPPED, userId);
    }

    public Order cancelOrder(long orderId, long userId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() != OrderStatus.DRAFT && order.status() != OrderStatus.PACKING) {
            throw new IllegalStateException("Cannot cancel order in status: " + order.status());
        }
        return changeOrderStatus(orderId, OrderStatus.CANCELLED, userId);
    }

    private void recalculateTotals(Connection conn, long orderId, CurrencyCode currency) throws SQLException {
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
                BigDecimal totalW = BigDecimal.ZERO;
                BigDecimal totalP = BigDecimal.ZERO;
                if (rs.next()) {
                    totalW = BigDecimal.valueOf(rs.getDouble("total_w"));
                    totalP = currency.round(BigDecimal.valueOf(rs.getDouble("total_p")));
                }
                orderDao.updateTotals(conn, orderId, totalW, totalP);
            }
        }
    }
}
