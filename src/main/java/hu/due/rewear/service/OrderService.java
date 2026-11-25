package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderDemandDao;
import hu.due.rewear.dao.OrderDemandDao.Product;
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
import hu.due.rewear.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
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
    private final OrderDemandDao demand = new OrderDemandDao();

    public record Availability(OrderDemandDao.Line line, int reserved, int available) {
        public int shortage() { return Math.max(0, line.quantity() - reserved - available); }
    }

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
        LocalDateTime now = BusinessClock.now();
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
                    java.util.Set.of(hu.due.rewear.model.RoleCode.DELIVERY_DRIVER),
                    hu.due.rewear.model.AppNotification.CAT_ORDER_STAGING,
                    "New Order: " + created.orderNumber(),
                    "Order " + created.orderNumber() + " (" + created.customerName() + ") created — prepare for dispatch collection.",
                    hu.due.rewear.ui.MainFrame.VIEW_STAGING);
        }
        return created;
    }

    public Order createScheduledOrder(long customerId, String currency, LocalDate deliveryDate, long userId) {
        return createScheduled(customerId, currency, deliveryDate, null, null, Map.of(), userId);
    }

    public Order importWebshopOrder(String source, String externalId, long customerId, String currency,
                                    LocalDate deliveryDate, Map<Product, Integer> products, long userId) {
        if (source == null || source.isBlank() || externalId == null || externalId.isBlank()
                || source.strip().length() > 80 || externalId.strip().length() > 120 || products.isEmpty()) {
            throw new IllegalArgumentException("Webshop source, external order ID and ordered products are required.");
        }
        return createScheduled(customerId, currency, deliveryDate, source.strip(), externalId.strip(), products, userId);
    }

    private Order createScheduled(long customerId, String currency, LocalDate date, String source, String externalId,
                                  Map<Product, Integer> products, long userId) {
        Objects.requireNonNull(date, "Select a delivery date.");
        String code = Objects.requireNonNull(currency, "Select an order currency.").strip().toUpperCase(Locale.ROOT);
        return db.executeInTransaction(conn -> {
            if (source != null) {
                var existing = demand.bySource(conn, source, externalId);
                if (existing.isPresent()) return orderDao.findById(conn, existing.getAsLong()).orElseThrow();
            }
            try (var ps = conn.prepareStatement("""
                    SELECT 1 FROM customers c JOIN currencies cur ON cur.code=? AND cur.active=1
                    WHERE c.id=? AND c.active=1 AND c.partner_type IN ('CUSTOMER','BOTH')
                    """)) {
                ps.setString(1, code);
                ps.setLong(2, customerId);
                try (var rows = ps.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Select an active buyer and currency.");
                }
            }
            long id = orderDao.insert(conn, orderDao.generateNextOrderNumber(conn), customerId, OrderStatus.DRAFT, code,
                    BigDecimal.ZERO, BigDecimal.ZERO, null, userId, BusinessClock.now(), null);
            orderDao.updateDeliveryDate(conn, id, date);
            if (source != null) demand.setSource(conn, id, source, externalId);
            for (var entry : products.entrySet()) {
                validateQuantity(entry.getValue());
                demand.setQuantity(conn, id, entry.getKey(), entry.getValue());
            }
            return orderDao.findById(conn, id).orElseThrow();
        });
    }

    public List<Availability> checkAvailability(long orderId) {
        return db.executeInTransaction(conn -> availability(conn, orderId));
    }

    private List<Availability> availability(Connection conn, long orderId) throws SQLException {
        List<Availability> result = new ArrayList<>();
        for (var line : demand.lines(conn, orderId)) {
            int reserved = demand.reservedCount(conn, orderId, line.product());
            int available = demand.availableCount(conn, line.product());
            result.add(new Availability(line, reserved, available));
        }
        return List.copyOf(result);
    }

    public void setRequestedProduct(long orderId, Product product, int quantity) {
        validateQuantity(quantity);
        db.runInTransaction(conn -> {
            requireEditableDemand(conn, orderId);
            demand.setQuantity(conn, orderId, product, quantity);
        });
    }

    public void removeRequestedProduct(long orderId, long lineId) {
        db.runInTransaction(conn -> {
            requireEditableDemand(conn, orderId);
            demand.remove(conn, orderId, lineId);
        });
    }

    private static void validateQuantity(int quantity) {
        if (quantity < 1 || quantity > 10_000) throw new IllegalArgumentException("Enter a package quantity from 1 to 10000.");
    }

    private void requireEditableDemand(Connection conn, long orderId) throws SQLException {
        Order order = requireOrder(conn, orderId);
        if (order.deliveryDate() == null || order.status() != OrderStatus.DRAFT || !orderItemDao.findByOrderId(conn, orderId).isEmpty()) {
            throw new IllegalStateException("Products can be edited on a scheduled draft before allocation.");
        }
    }

    public Order allocateRequestedProducts(long orderId, long userId) {
        Order result = db.executeInTransaction(conn -> {
            Order order = requireOrder(conn, orderId);
            if (order.deliveryDate() == null || order.status() != OrderStatus.DRAFT) {
                throw new IllegalStateException("Only scheduled draft orders can be allocated.");
            }
            requireAssignedCollection(order);
            var lines = demand.lines(conn, orderId);
            if (lines.isEmpty()) throw new IllegalStateException("Add the customer's requested products first.");
            var planned = new ArrayList<Long>();
            for (var line : lines) {
                int missing = line.quantity() - demand.reservedCount(conn, orderId, line.product());
                if (missing < 0) throw new IllegalStateException("Allocated quantity exceeds the requested quantity.");
                List<Long> available = demand.available(conn, line.product(), missing);
                if (available.size() != missing) {
                    throw new IllegalStateException("Insufficient storage stock for " + line.description()
                            + " (" + available.size() + "/" + missing + " packages available).");
                }
                planned.addAll(available);
            }
            LocalDateTime now = BusinessClock.now();
            for (long packageId : planned) {
                reservePackage(conn, order, packageDao.findById(conn, packageId).orElseThrow(), null, userId, now);
            }
            requireDemandComplete(conn, order);
            recalculateTotals(conn, orderId, order.currencyDecimals());
            orderDao.updateStatus(conn, orderId, OrderStatus.PACKING, null);
            recordScheduledCollection(conn, order);
            return requireOrder(conn, orderId);
        });
        if (notificationService != null) notificationService.publishCommitted();
        return result;
    }

    private void recordScheduledCollection(Connection conn, Order order) throws SQLException {
        if (notificationService != null) notificationService.record(conn, order.assignedForkliftId(),
                hu.due.rewear.model.AppNotification.CAT_ORDER_STAGING, "Collect " + order.orderNumber(),
                order.customerName() + " · " + order.deliveryDate() + " · " + order.dispatchLocationCode(),
                hu.due.rewear.ui.MainFrame.VIEW_STAGING);
    }

    private Order requireOrder(Connection conn, long orderId) throws SQLException {
        return orderDao.findById(conn, orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    private void requireDemandComplete(Connection conn, Order order) throws SQLException {
        if (order.deliveryDate() == null) return;
        var lines = demand.lines(conn, order.id());
        if (lines.isEmpty()) throw new IllegalStateException("Add the customer's requested products first.");
        int allocated = 0;
        for (var line : lines) {
            int count = demand.reservedCount(conn, order.id(), line.product());
            if (count != line.quantity()) throw new IllegalStateException("Complete the requested quantity for " + line.description() + ".");
            allocated += count;
        }
        if (allocated != orderItemDao.findByOrderId(conn, order.id()).size()) {
            throw new IllegalStateException("The allocation contains additional packages.");
        }
    }

    private static void requireAssignedCollection(Order order) {
        if (order.assignedForkliftId() == null || order.dispatchLocationId() == null) {
            throw new IllegalStateException("Assign a fulfilment driver and collection dock first.");
        }
    }

    public Order addPackageToOrder(long orderId, long packageId, BigDecimal customPricePerKg, long userId) {
        LocalDateTime now = BusinessClock.now();
        InventoryPackage pkg = db.executeInTransaction(conn -> {
            Order order = orderDao.findById(conn, orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
            if (order.status() != OrderStatus.DRAFT && order.status() != OrderStatus.PACKING) {
                throw new IllegalStateException("Cannot add packages to order in status: " + order.status());
            }
            InventoryPackage available = packageDao.findById(conn, packageId)
                    .orElseThrow(() -> new IllegalArgumentException("Package not found: " + packageId));
            if (available.status() != PackageStatus.IN_STOCK) {
                throw new IllegalStateException("Package " + available.qrCode() + " is not in stock.");
            }
            if (order.deliveryDate() != null) {
                var product = new Product(available.targetGroupId(), available.garmentTypeId(),
                        available.qualityGradeId(), available.packageTypeId());
                var line = demand.lines(conn, order.id()).stream().filter(item -> item.product().equals(product)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("This product was not requested on this order."));
                if (demand.reservedCount(conn, orderId, product) >= line.quantity()
                        || !demand.isActiveStorage(conn, available.locationId())) {
                    throw new IllegalStateException("The requested quantity is complete or the package is outside active storage.");
                }
            }
            reservePackage(conn, order, available, customPricePerKg, userId, now);
            recalculateTotals(conn, orderId, order.currencyDecimals());
            return available;
        });

        Order order = orderDao.findById(orderId).orElseThrow();
        LOG.info("Added package {} to order {}", pkg.qrCode(), order.orderNumber());
        if (notificationService != null && order.deliveryDate() == null) {
            String title = "Collect for " + order.orderNumber();
            String msg = "Collect " + pkg.qrCode() + " from " + pkg.locationCode()
                    + " to " + (order.dispatchLocationCode() != null ? order.dispatchLocationCode() : "Dispatch Zone")
                    + " for " + order.customerName() + ".";
            if (order.assignedForkliftId() != null) {
                notificationService.notifyUser(order.assignedForkliftId(),
                        hu.due.rewear.model.AppNotification.CAT_ORDER_STAGING,
                        title, msg, hu.due.rewear.ui.MainFrame.VIEW_STAGING);
            } else {
                notificationService.notifyRoles(
                        java.util.Set.of(hu.due.rewear.model.RoleCode.DELIVERY_DRIVER),
                        hu.due.rewear.model.AppNotification.CAT_ORDER_STAGING,
                        title, msg, hu.due.rewear.ui.MainFrame.VIEW_STAGING);
            }
        }
        return order;
    }

    private void reservePackage(Connection conn, Order order, InventoryPackage pkg, BigDecimal customPricePerKg,
                                long userId, LocalDateTime now) throws SQLException {
        int decimals = order.currencyDecimals();
        BigDecimal price = customPricePerKg != null ? customPricePerKg : pkg.effectivePricePerKg(order.effectiveCurrencyCode());
        if (price == null || price.signum() < 0) throw new IllegalArgumentException("Price per kg must be >= 0");
        BigDecimal unitPrice = CurrencyDef.roundForScale(price, decimals);
        BigDecimal lineTotal = CurrencyDef.roundForScale(pkg.weightKg().multiply(unitPrice), decimals);
        orderItemDao.insert(conn, order.id(), pkg.id(), unitPrice, lineTotal);
        packageDao.updateStatus(conn, pkg.id(), PackageStatus.RESERVED, now);
        movementDao.insert(conn, pkg.id(), MovementType.RESERVE, pkg.locationId(), pkg.locationId(), userId,
                "Reserved for " + order.orderNumber(), now);
    }

    /** Assigns a Forklift Driver and target Collection/Dispatch Zone to an order and notifies that driver. */
    public Order assignForkliftAndDock(long orderId, Long forkliftUserId, Long dispatchLocationId) {
        Order updated = db.executeInTransaction(conn -> {
            Order order = requireOrder(conn, orderId);
            if (order.deliveryDate() != null && (order.status() != OrderStatus.DRAFT)) {
                throw new IllegalStateException("Change collection assignment before the order starts packing.");
            }
            if (forkliftUserId != null) {
                try (var ps = conn.prepareStatement("""
                        SELECT 1 FROM users u JOIN roles r ON r.id=u.role_id WHERE u.id=? AND u.active=1 AND r.code='DELIVERY_DRIVER'
                        """)) {
                    ps.setLong(1, forkliftUserId);
                    try (var rows = ps.executeQuery()) {
                        if (!rows.next()) throw new IllegalArgumentException("Select an active forklift driver.");
                    }
                }
            }
            if (dispatchLocationId != null) {
                try (var ps = conn.prepareStatement("SELECT 1 FROM warehouse_locations WHERE id=? AND active=1 AND location_type='DISPATCH_ZONE'")) {
                    ps.setLong(1, dispatchLocationId);
                    try (var rows = ps.executeQuery()) {
                        if (!rows.next()) throw new IllegalArgumentException("Select an active collection dock.");
                    }
                }
            }
            orderDao.updateAssignment(conn, orderId, forkliftUserId, dispatchLocationId);
            return requireOrder(conn, orderId);
        });
        if (notificationService != null && forkliftUserId != null && updated.deliveryDate() == null) {
            String dock = updated.dispatchLocationCode() != null ? updated.dispatchLocationCode() : "Dispatch Zone";
            notificationService.notifyUser(forkliftUserId,
                    hu.due.rewear.model.AppNotification.CAT_ORDER_STAGING,
                    "Assigned Order: " + updated.orderNumber(),
                    "Collect packages for " + updated.orderNumber() + " (" + updated.customerName() + ") and stage at " + dock + ".",
                    hu.due.rewear.ui.MainFrame.VIEW_STAGING);
        }
        return updated;
    }

    /** Marks that the customer has arrived on site for pickup and alerts Managers &amp; the assigned Forklift Driver. */
    public Order markCustomerArrived(long orderId) {
        boolean recorded = orderDao.recordCustomerArrival(orderId, BusinessClock.now());
        Order updated = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (!recorded && (updated.status() == OrderStatus.SHIPPED || updated.status() == OrderStatus.CANCELLED)) {
            throw new IllegalStateException("Cannot mark customer arrived for order in status: " + updated.status());
        }
        if (recorded && notificationService != null) {
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
                        hu.due.rewear.ui.MainFrame.VIEW_STAGING);
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
        String code = rawQrCode.trim().toUpperCase(Locale.ROOT);
        return db.executeInTransaction(conn -> {
            Order order = orderDao.findById(conn, orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
            if (order.status() != OrderStatus.DRAFT && order.status() != OrderStatus.PACKING) {
                throw new IllegalStateException("Order is not open for handover (status: " + order.status() + ").");
            }
            if (!order.isCustomerArrived()) throw new IllegalStateException("Mark the customer as arrived before scanning packages.");
            if (order.deliveryDate() != null) {
                requireAssignedCollection(order);
                requireDemandComplete(conn, order);
            }
            verifyHandoverActor(conn, order, managerUserId);
            OrderItem match = orderItemDao.findByOrderId(conn, orderId).stream()
                    .filter(item -> item.packageQrCode().equalsIgnoreCase(code))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Package " + code + " does not belong to order " + order.orderNumber() + "."));
            InventoryPackage pkg = packageDao.findById(conn, match.packageId()).orElseThrow();
            validatePickupPackage(order, pkg);
            if (match.handoverScanned()) {
                return match;
            }
            orderItemDao.markHandoverScanned(conn, match.id(), true);
            movementDao.insert(conn, match.packageId(), MovementType.SCAN_VERIFY,
                    pkg.locationId(), pkg.locationId(), managerUserId,
                    "Customer handover scan verified for " + order.orderNumber(), BusinessClock.now());
            return orderItemDao.findByOrderId(conn, orderId).stream()
                    .filter(item -> item.id() == match.id()).findFirst().orElseThrow();
        });
    }

    /**
     * Completes customer handover and ships the order ONLY after 100% of allocated packages have been scanned.
     */
    public Order completeVerifiedHandover(long orderId, long managerUserId) {
        return changeOrderStatus(orderId, OrderStatus.SHIPPED, managerUserId, true);
    }

    private static void verifyHandoverActor(Connection conn, Order order, long userId) throws SQLException {
        try (var ps = conn.prepareStatement("""
                SELECT r.code FROM users u JOIN roles r ON r.id=u.role_id WHERE u.id=? AND u.active=1
                """)) {
            ps.setLong(1, userId);
            try (var rows = ps.executeQuery()) {
                if (rows.next()) {
                    String role = rows.getString(1);
                    if (role.equals("ADMIN") || role.equals("SUPER_ADMIN") || role.equals("MANAGER")
                            || (role.equals("DELIVERY_DRIVER") && Objects.equals(order.assignedForkliftId(), userId))) return;
                }
            }
        }
        throw new IllegalStateException("Only warehouse management and the assigned fulfilment driver can verify loading.");
    }

    private static void validatePickupPackage(Order order, InventoryPackage pkg) {
        if (pkg.status() != PackageStatus.RESERVED) {
            throw new IllegalStateException("Package " + pkg.qrCode() + " is no longer reserved.");
        }
        if (order.dispatchLocationId() != null && pkg.locationId() != order.dispatchLocationId()) {
            throw new IllegalStateException("Package " + pkg.qrCode() + " must be at " + order.dispatchLocationCode() + ".");
        }
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
        LocalDateTime now = BusinessClock.now();

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
        Order result = changeOrderStatus(orderId, targetStatus, userId, false);
        if (notificationService != null && result.deliveryDate() != null && targetStatus == OrderStatus.PACKING) {
            notificationService.publishCommitted();
        }
        return result;
    }

    private Order changeOrderStatus(long orderId, OrderStatus targetStatus, long userId, boolean verifiedHandover) {
        LocalDateTime now = BusinessClock.now();
        return db.executeInTransaction(conn -> {
            Order order = orderDao.findById(conn, orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
            OrderStatus current = order.status();
            if (current == targetStatus) {
                return order;
            }
            if (order.deliveryDate() != null && current == OrderStatus.SHIPPED && hasActiveOrderInvoice(conn, orderId)) {
                throw new IllegalStateException("Cancel or credit the linked invoice before reopening this order.");
            }
            List<OrderItem> items = orderItemDao.findByOrderId(conn, orderId);
            if ((targetStatus == OrderStatus.PACKING || targetStatus == OrderStatus.SHIPPED) && items.isEmpty()) {
                throw new IllegalStateException("Cannot transition an empty order to " + targetStatus);
            }
            if (order.deliveryDate() != null && (targetStatus == OrderStatus.PACKING || targetStatus == OrderStatus.SHIPPED)) {
                requireAssignedCollection(order);
                requireDemandComplete(conn, order);
            }
            if (targetStatus == OrderStatus.SHIPPED && (verifiedHandover || order.deliveryDate() != null)) {
                verifyHandoverActor(conn, order, userId);
                if (!order.isCustomerArrived()) throw new IllegalStateException("Mark the customer as arrived before shipping.");
            }
            boolean requiresScans = verifiedHandover || order.deliveryDate() != null || order.assignedForkliftId() != null
                    || order.dispatchLocationId() != null || order.isCustomerArrived();
            if (targetStatus == OrderStatus.SHIPPED && requiresScans
                    && (current == OrderStatus.CANCELLED || items.stream().anyMatch(item -> !item.handoverScanned()))) {
                throw new IllegalStateException("Complete the QR scan for every package in the pickup handover before shipping.");
            }
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
                    if (requiresScans) {
                        validatePickupPackage(order, pkg);
                    } else if (current != OrderStatus.CANCELLED && pkg.status() != PackageStatus.RESERVED) {
                        throw new IllegalStateException("Package " + pkg.qrCode() + " is no longer reserved.");
                    }
                    packageDao.updateStatus(conn, pkg.id(), PackageStatus.SHIPPED, now);
                    movementDao.insert(conn, pkg.id(), MovementType.SHIP, pkg.locationId(), null,
                            userId, "Shipped on " + order.orderNumber(), now);
                }
                orderDao.updateStatus(conn, orderId, OrderStatus.SHIPPED, now);
            } else if (targetStatus == OrderStatus.CANCELLED) {
                orderItemDao.clearHandoverScans(conn, orderId);
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
                    orderItemDao.clearHandoverScans(conn, orderId);
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
                if (order.deliveryDate() != null && targetStatus == OrderStatus.PACKING) recordScheduledCollection(conn, order);
            }
            LOG.info("Order {} status changed {} -> {} by userId={}", order.orderNumber(), current, targetStatus, userId);
            return orderDao.findById(conn, orderId).orElseThrow();
        });
    }

    private static boolean hasActiveOrderInvoice(Connection conn, long orderId) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT 1 FROM invoices WHERE order_id=? AND status<>'CANCELLED' LIMIT 1")) {
            ps.setLong(1, orderId);
            try (var rows = ps.executeQuery()) { return rows.next(); }
        }
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

    /**
     * Recomputes an order's totals. The weight is a measurement and SQL may add it up, but the money is
     * summed here with exact decimals: SQLite would add the line totals as floating point numbers.
     */
    private void recalculateTotals(Connection conn, long orderId, int decimals) throws SQLException {
        String sql = """
                SELECT ip.weight_kg AS weight, oi.line_total AS line_total
                FROM order_items oi
                JOIN inventory_packages ip ON ip.id = oi.package_id
                WHERE oi.order_id = ?
                """;
        BigDecimal totalW = BigDecimal.ZERO;
        BigDecimal totalP = BigDecimal.ZERO;
        try (var ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    totalW = totalW.add(BigDecimal.valueOf(rs.getDouble("weight")));
                    totalP = totalP.add(hu.due.rewear.dao.Money.get(rs, "line_total"));
                }
            }
        }
        orderDao.updateTotals(conn, orderId, totalW, CurrencyDef.roundForScale(totalP, decimals));
    }
}
