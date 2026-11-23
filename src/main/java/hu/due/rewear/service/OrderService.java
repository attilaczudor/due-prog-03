package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.MovementType;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.model.PackageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

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

    private NotificationService notificationService;

    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public Order createOrder(long customerId, String notes, long userId) {
        return createOrder(customerId, "EUR", notes, userId);
    }

    public Order createOrder(long customerId, CurrencyCode currency, String notes, long userId) {
        return createOrder(customerId, currency != null ? currency.name() : "EUR", notes, userId);
    }

    public Order createOrder(long customerId, String currencyCode, String notes, long userId) {
        LocalDateTime now = LocalDateTime.now();
        String cur = (currencyCode != null && !currencyCode.isBlank()) ? currencyCode.trim().toUpperCase(Locale.ROOT) : "EUR";
        String cleanNotes = notes != null && !notes.isBlank() ? notes.trim() : null;
        long orderId = db.executeInTransaction(conn -> {
            String orderNumber = orderDao.generateNextOrderNumber(conn);
            return orderDao.insert(conn, orderNumber, customerId, OrderStatus.DRAFT, cur,
                    BigDecimal.ZERO, BigDecimal.ZERO, cleanNotes, userId, now, null);
        });
        Order created = orderDao.findById(orderId).orElseThrow();
        LOG.info("Created order {} ({}) for customerId={} by userId={}", created.orderNumber(), cur, customerId, userId);
        if (notificationService != null) {
            notificationService.notifyRoles(
                    java.util.Set.of(hu.due.rewear.model.RoleCode.FORKLIFT),
                    hu.due.rewear.model.AppNotification.CAT_ORDER_STAGING,
                    "New Order: " + created.orderNumber(),
                    "Order " + created.orderNumber() + " (" + created.customerName() + ") created — prepare for dispatch collection.",
                    hu.due.rewear.ui.MainFrame.VIEW_TRANSPORT);
        }
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

        String cur = order.effectiveCurrencyCode();
        int decimals = order.currencyDecimals();
        BigDecimal unitPrice = customPricePerKg != null
                ? CurrencyDef.roundForScale(customPricePerKg, decimals)
                : pkg.effectivePricePerKg(cur);
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Price per kg must be >= 0");
        }
        BigDecimal lineTotal = CurrencyDef.roundForScale(pkg.weightKg().multiply(unitPrice), decimals);
        LocalDateTime now = LocalDateTime.now();

        db.runInTransaction(conn -> {
            orderItemDao.insert(conn, orderId, packageId, unitPrice, lineTotal);
            packageDao.updateStatus(conn, packageId, PackageStatus.RESERVED, now);
            movementDao.insert(conn, packageId, MovementType.RESERVE, pkg.locationId(), pkg.locationId(),
                    userId, "Reserved for " + order.orderNumber(), now);
            recalculateTotals(conn, orderId, decimals);
        });

        LOG.info("Added package {} to order {} ({} {}/kg)", pkg.qrCode(), order.orderNumber(), unitPrice, cur);
        if (notificationService != null) {
            String title = "Collect for " + order.orderNumber();
            String msg = "Collect " + pkg.qrCode() + " from " + pkg.locationCode()
                    + " to " + (order.dispatchLocationCode() != null ? order.dispatchLocationCode() : "Dispatch Zone")
                    + " for " + order.customerName() + ".";
            if (order.assignedForkliftId() != null) {
                notificationService.notifyUser(order.assignedForkliftId(),
                        hu.due.rewear.model.AppNotification.CAT_ORDER_STAGING,
                        title, msg, hu.due.rewear.ui.MainFrame.VIEW_TRANSPORT);
            } else {
                notificationService.notifyRoles(
                        java.util.Set.of(hu.due.rewear.model.RoleCode.FORKLIFT),
                        hu.due.rewear.model.AppNotification.CAT_ORDER_STAGING,
                        title, msg, hu.due.rewear.ui.MainFrame.VIEW_TRANSPORT);
            }
        }
        return orderDao.findById(orderId).orElseThrow();
    }

    /** Assigns a Forklift Driver and target Collection/Dispatch Zone to an order and notifies that driver. */
    public Order assignForkliftAndDock(long orderId, Long forkliftUserId, Long dispatchLocationId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        orderDao.updateAssignment(orderId, forkliftUserId, dispatchLocationId);
        Order updated = orderDao.findById(orderId).orElseThrow();
        if (notificationService != null && forkliftUserId != null) {
            String dock = updated.dispatchLocationCode() != null ? updated.dispatchLocationCode() : "Dispatch Zone";
            notificationService.notifyUser(forkliftUserId,
                    hu.due.rewear.model.AppNotification.CAT_ORDER_STAGING,
                    "Assigned Order: " + updated.orderNumber(),
                    "Collect packages for " + updated.orderNumber() + " (" + updated.customerName() + ") and stage at " + dock + ".",
                    hu.due.rewear.ui.MainFrame.VIEW_TRANSPORT);
        }
        return updated;
    }

    /** Marks that the customer has arrived on site for pickup and alerts Managers &amp; the assigned Forklift Driver. */
    public Order markCustomerArrived(long orderId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() == OrderStatus.SHIPPED || order.status() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Cannot mark customer arrived for order in status: " + order.status());
        }
        orderDao.updateCustomerArrivedAt(orderId, LocalDateTime.now());
        Order updated = orderDao.findById(orderId).orElseThrow();
        if (notificationService != null) {
            String dock = updated.dispatchLocationCode() != null ? updated.dispatchLocationCode() : "DOCK-01";
            notificationService.notifyRoles(
                    java.util.Set.of(hu.due.rewear.model.RoleCode.MANAGER, hu.due.rewear.model.RoleCode.ADMIN, hu.due.rewear.model.RoleCode.SUPER_ADMIN),
                    hu.due.rewear.model.AppNotification.CAT_ORDER_READY,
                    "CUSTOMER ARRIVED: " + updated.orderNumber(),
                    updated.customerName() + " has arrived for pickup (" + updated.orderNumber() + " at " + dock + "). Scan all items to hand over.",
                    hu.due.rewear.ui.MainFrame.VIEW_ORDERS);
            if (updated.assignedForkliftId() != null) {
                notificationService.notifyUser(updated.assignedForkliftId(),
                        hu.due.rewear.model.AppNotification.CAT_ORDER_READY,
                        "CUSTOMER ARRIVED: " + updated.orderNumber(),
                        updated.customerName() + " is here! Ensure all packages for " + updated.orderNumber() + " are at " + dock + ".",
                        hu.due.rewear.ui.MainFrame.VIEW_TRANSPORT);
            }
        }
        return updated;
    }

    /**
     * Manager scans a package barcode/QR code during customer handover to verify it belongs to this order.
     */
    public OrderItem scanOrderItemForHandover(long orderId, String rawQrCode, long managerUserId) {
        if (rawQrCode == null || rawQrCode.isBlank()) {
            throw new IllegalArgumentException("Scan or enter a package QR code.");
        }
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() != OrderStatus.DRAFT && order.status() != OrderStatus.PACKING) {
            throw new IllegalStateException("Order is not open for handover (status: " + order.status() + ").");
        }
        String code = rawQrCode.trim().toUpperCase(Locale.ROOT);
        List<OrderItem> items = orderItemDao.findByOrderId(orderId);
        OrderItem match = items.stream()
                .filter(i -> i.packageQrCode().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Package " + code + " does NOT belong to order " + order.orderNumber() + "!"));

        LocalDateTime now = LocalDateTime.now();
        db.runInTransaction(conn -> {
            orderItemDao.markHandoverScanned(conn, match.id(), true);
            InventoryPackage pkg = packageDao.findById(conn, match.packageId()).orElseThrow();
            movementDao.insert(conn, match.packageId(), MovementType.SCAN_VERIFY,
                    pkg.locationId(), pkg.locationId(), managerUserId,
                    "Customer handover scan verified for " + order.orderNumber(), now);
        });
        return orderItemDao.findByOrderId(orderId).stream()
                .filter(i -> i.id() == match.id()).findFirst().orElseThrow();
    }

    /**
     * Completes customer handover and ships the order ONLY after 100% of allocated packages have been scanned.
     */
    public Order completeVerifiedHandover(long orderId, long managerUserId) {
        List<OrderItem> items = orderItemDao.findByOrderId(orderId);
        if (items.isEmpty()) {
            throw new IllegalStateException("Cannot hand over an empty order.");
        }
        long unscanned = items.stream().filter(i -> !i.handoverScanned()).count();
        if (unscanned > 0) {
            throw new IllegalStateException(
                    unscanned + " package(s) still need to be scanned before handing over this order.");
        }
        return shipOrder(orderId, managerUserId);
    }

    public Order changeOrderCurrency(long orderId, CurrencyCode targetCurrency) {
        return changeOrderCurrency(orderId, targetCurrency != null ? targetCurrency.name() : "EUR");
    }

    /**
     * Switches an open (DRAFT / PACKING) order to any active currency (HUF, EUR, USD, ...),
     * recalculating every allocated package's unit price and updating order totals.
     */
    public Order changeOrderCurrency(long orderId, String targetCurrencyCode) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() != OrderStatus.DRAFT && order.status() != OrderStatus.PACKING) {
            throw new IllegalStateException("Cannot change currency for order in status: " + order.status());
        }
        String cur = (targetCurrencyCode != null && !targetCurrencyCode.isBlank())
                ? targetCurrencyCode.trim().toUpperCase(Locale.ROOT) : "EUR";
        if (order.effectiveCurrencyCode().equalsIgnoreCase(cur)) {
            return order;
        }
        int decimals = CurrencyDef.defaultDecimalsFor(cur);
        List<OrderItem> items = orderItemDao.findByOrderId(orderId);
        db.runInTransaction(conn -> {
            orderDao.updateCurrency(conn, orderId, cur);
            for (OrderItem item : items) {
                InventoryPackage pkg = packageDao.findById(conn, item.packageId()).orElseThrow();
                BigDecimal unitPrice = pkg.effectivePricePerKg(cur);
                BigDecimal lineTotal = CurrencyDef.roundForScale(pkg.weightKg().multiply(unitPrice), decimals);
                orderItemDao.updateItemPricing(conn, item.id(), unitPrice, lineTotal);
            }
            recalculateTotals(conn, orderId, decimals);
        });
        LOG.info("Changed order {} currency to {}", order.orderNumber(), cur);
        return orderDao.findById(orderId).orElseThrow();
    }

    /**
     * Updates the negotiated unit price per kg for a single allocated package on an open
     * ({@code DRAFT} / {@code PACKING}) order and atomically recalculates the line &amp; order totals.
     */
    public Order updateOrderItemPrice(long orderId, long orderItemId, BigDecimal newPricePerKg) {
        if (newPricePerKg == null || newPricePerKg.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Price per kg must be >= 0");
        }
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.status() != OrderStatus.DRAFT && order.status() != OrderStatus.PACKING) {
            throw new IllegalStateException("Cannot edit prices on order in status: " + order.status());
        }
        OrderItem target = orderItemDao.findByOrderId(orderId).stream()
                .filter(i -> i.id() == orderItemId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Order item not found: " + orderItemId));

        int decimals = order.currencyDecimals();
        BigDecimal roundedUnit = CurrencyDef.roundForScale(newPricePerKg, decimals);
        BigDecimal lineTotal = CurrencyDef.roundForScale(target.weightKg().multiply(roundedUnit), decimals);

        db.runInTransaction(conn -> {
            orderItemDao.updateItemPricing(conn, orderItemId, roundedUnit, lineTotal);
            recalculateTotals(conn, orderId, decimals);
        });

        LOG.info("Updated item {} on order {} to {} {}/kg", target.packageQrCode(),
                order.orderNumber(), roundedUnit, order.effectiveCurrencyCode());
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
            recalculateTotals(conn, orderId, order.currencyDecimals());
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

        LocalDateTime now = LocalDateTime.now();
        db.runInTransaction(conn -> {
            // If restoring from CANCELLED, verify all allocated packages are still IN_STOCK atomically inside the write tx
            if (current == OrderStatus.CANCELLED) {
                for (OrderItem item : items) {
                    InventoryPackage pkg = packageDao.findById(conn, item.packageId()).orElseThrow();
                    if (pkg.status() != PackageStatus.IN_STOCK) {
                        throw new IllegalStateException("Cannot restore order: package " + pkg.qrCode()
                                + " is currently " + pkg.status());
                    }
                }
            }

            if (targetStatus == OrderStatus.SHIPPED) {
                for (OrderItem item : items) {
                    InventoryPackage pkg = packageDao.findById(conn, item.packageId()).orElseThrow();
                    packageDao.updateStatus(conn, pkg.id(), PackageStatus.SHIPPED, now);
                    movementDao.insert(conn, pkg.id(), MovementType.SHIP, pkg.locationId(), null,
                            userId, "Shipped on " + order.orderNumber(), now);
                }
                orderDao.updateStatus(conn, orderId, OrderStatus.SHIPPED, now);
            } else if (targetStatus == OrderStatus.CANCELLED) {
                for (OrderItem item : items) {
                    InventoryPackage pkg = packageDao.findById(conn, item.packageId()).orElseThrow();
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
                        InventoryPackage pkg = packageDao.findById(conn, item.packageId()).orElseThrow();
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

    private void recalculateTotals(Connection conn, long orderId, int decimals) throws SQLException {
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
                    totalP = CurrencyDef.roundForScale(BigDecimal.valueOf(rs.getDouble("total_p")), decimals);
                }
                orderDao.updateTotals(conn, orderId, totalW, totalP);
            }
        }
    }
}
