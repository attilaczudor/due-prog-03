package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.model.AppNotification;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.WarehouseLocation;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Warehouse forklift / in-house transporter workflow service handling:
 * <ol>
 *     <li><b>Putaway Queue:</b> moving newly sorted {@code IN_STOCK} packages from
 *     {@link LocationType#OPERATOR_STATION} workstations to {@link LocationType#STORAGE} shelves.</li>
 *     <li><b>Assigned Order Collection (Barcode Scan):</b> scanning and picking {@code RESERVED} packages
 *     from storage shelves for orders assigned to the forklift driver and placing them at the order's
 *     {@link LocationType#DISPATCH_ZONE} collection site.</li>
 * </ol>
 */
public final class TransportService {

    /** Projection row for an order-allocated package awaiting movement to the dispatch/collection site. */
    public record StagingTask(
            long packageId,
            String qrCode,
            BigDecimal weightKg,
            long currentLocationId,
            String currentLocationCode,
            LocationType currentLocationType,
            long orderId,
            String orderNumber,
            String customerName,
            String orderStatus,
            Long assignedForkliftId,
            String assignedForkliftName,
            Long targetDispatchLocationId,
            String targetDispatchLocationCode,
            boolean customerArrived
    ) {
        public boolean isAtDispatchZone() {
            return currentLocationType == LocationType.DISPATCH_ZONE;
        }
    }

    private final DatabaseManager db;
    private final InventoryPackageDao packageDao;
    private final WarehouseLocationDao locationDao;
    private final InventoryService inventoryService;
    private NotificationService notificationService;

    public TransportService(DatabaseManager db,
                            InventoryPackageDao packageDao,
                            WarehouseLocationDao locationDao,
                            InventoryService inventoryService) {
        this.db = db;
        this.packageDao = packageDao;
        this.locationDao = locationDao;
        this.inventoryService = inventoryService;
    }

    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Returns all {@code IN_STOCK} packages currently waiting at any {@link LocationType#OPERATOR_STATION}. */
    public List<InventoryPackage> findPutawayQueue() {
        List<WarehouseLocation> stations = locationDao.findByType(LocationType.OPERATOR_STATION, false);
        if (stations.isEmpty()) {
            return List.of();
        }
        List<InventoryPackage> result = new ArrayList<>();
        for (WarehouseLocation st : stations) {
            for (InventoryPackage pkg : packageDao.findByLocationId(st.id())) {
                if (pkg.status() == PackageStatus.IN_STOCK) {
                    result.add(pkg);
                }
            }
        }
        return result;
    }

    /** Returns all active order-staging tasks across all orders (used by Management / Dashboard). */
    public List<StagingTask> findOrderStagingTasks() {
        return findOrderStagingTasksForDriver(null);
    }

    /**
     * Returns active ({@code DRAFT} / {@code PACKING}) order-staging tasks.
     * When {@code driverId} is non-null, filters strictly to orders assigned to that Forklift Driver.
     */
    public List<StagingTask> findOrderStagingTasksForDriver(Long driverId) {
        String sql = """
                SELECT ip.id AS package_id, ip.qr_code, ip.weight_kg,
                       wl.id AS loc_id, wl.code AS loc_code, wl.location_type,
                       o.id AS order_id, o.order_number, c.name AS customer_name, o.status AS order_status,
                       o.assigned_forklift_id, fk.full_name AS assigned_forklift_name,
                       o.dispatch_location_id, dl.code AS dispatch_location_code,
                       o.customer_arrived_at
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                JOIN customers c ON c.id = o.customer_id
                JOIN inventory_packages ip ON ip.id = oi.package_id
                JOIN warehouse_locations wl ON wl.id = ip.location_id
                LEFT JOIN users fk ON fk.id = o.assigned_forklift_id
                LEFT JOIN warehouse_locations dl ON dl.id = o.dispatch_location_id
                WHERE o.status IN ('DRAFT', 'PACKING')
                  AND ip.status = 'RESERVED'
                """ + (driverId != null ? " AND o.assigned_forklift_id = ? " : "") + """
                ORDER BY CASE WHEN o.customer_arrived_at IS NOT NULL THEN 0 ELSE 1 END,
                         CASE WHEN wl.location_type = 'DISPATCH_ZONE' THEN 1 ELSE 0 END,
                         o.order_number ASC, ip.qr_code ASC
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (driverId != null) {
                ps.setLong(1, driverId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<StagingTask> tasks = new ArrayList<>();
                while (rs.next()) {
                    long fkRaw = rs.getLong("assigned_forklift_id");
                    Long fkId = rs.wasNull() ? null : fkRaw;
                    long dlRaw = rs.getLong("dispatch_location_id");
                    Long dlId = rs.wasNull() ? null : dlRaw;
                    tasks.add(new StagingTask(
                            rs.getLong("package_id"),
                            rs.getString("qr_code"),
                            BigDecimal.valueOf(rs.getDouble("weight_kg")),
                            rs.getLong("loc_id"),
                            rs.getString("loc_code"),
                            LocationType.fromDb(rs.getString("location_type")),
                            rs.getLong("order_id"),
                            rs.getString("order_number"),
                            rs.getString("customer_name"),
                            rs.getString("order_status"),
                            fkId,
                            rs.getString("assigned_forklift_name"),
                            dlId,
                            rs.getString("dispatch_location_code"),
                            rs.getString("customer_arrived_at") != null
                    ));
                }
                return tasks;
            }
        } catch (SQLException e) {
            throw new hu.due.rewear.dao.DaoException("Failed to load forklift order staging tasks", e);
        }
    }

    /** Moves one or more packages from an Operator Station onto a {@link LocationType#STORAGE} shelf. */
    public int putawayToStorage(List<Long> packageIds, long targetStorageLocationId, long driverUserId) {
        WarehouseLocation target = locationDao.findById(targetStorageLocationId)
                .orElseThrow(() -> new IllegalArgumentException("Target storage shelf not found."));
        if (target.locationType() != LocationType.STORAGE) {
            throw new IllegalArgumentException("Putaway destination must be a STORAGE shelf.");
        }
        return inventoryService.relocatePackages(packageIds, targetStorageLocationId,
                "Forklift putaway -> " + target.code(), driverUserId);
    }

    /**
     * Barcode/QR scan collection: forklift driver scans a package's QR code to start fulfilling an assigned
     * order and move the package directly to the order's Collection / Dispatch Site ({@code DISPATCH_ZONE}).
     */
    public StagingTask scanAndCollectForOrder(String rawQrCode, Long fallbackDispatchLocationId,
                                              long driverUserId, boolean enforceAssignedDriver) {
        if (rawQrCode == null || rawQrCode.isBlank()) {
            throw new IllegalArgumentException("Scan or enter a package QR code.");
        }
        String qr = rawQrCode.trim().toUpperCase(Locale.ROOT);
        List<StagingTask> candidates = enforceAssignedDriver
                ? findOrderStagingTasksForDriver(driverUserId)
                : findOrderStagingTasks();
        StagingTask task = candidates.stream()
                .filter(t -> t.qrCode().equalsIgnoreCase(qr))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Package " + qr + " is not in your assigned open order collection queue."));

        Long targetDockId = task.targetDispatchLocationId() != null
                ? task.targetDispatchLocationId()
                : fallbackDispatchLocationId;
        if (targetDockId == null) {
            List<WarehouseLocation> docks = locationDao.findByType(LocationType.DISPATCH_ZONE, true);
            if (docks.isEmpty()) {
                throw new IllegalStateException("No active DISPATCH_ZONE collection site configured.");
            }
            targetDockId = docks.get(0).id();
        }

        // Transition DRAFT -> PACKING automatically when forklift starts scanning packages for the order
        if ("DRAFT".equalsIgnoreCase(task.orderStatus())) {
            final long orderId = task.orderId();
            db.runInTransaction(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE orders SET status = 'PACKING' WHERE id = ? AND status = 'DRAFT'")) {
                    ps.setLong(1, orderId);
                    ps.executeUpdate();
                }
            });
        }

        stageToDispatchZone(List.of(task.packageId()), targetDockId, driverUserId);
        return findOrderStagingTasks().stream()
                .filter(t -> t.packageId() == task.packageId())
                .findFirst().orElse(task);
    }

    /** Moves one or more order-reserved packages to a {@link LocationType#DISPATCH_ZONE} collection dock. */
    public int stageToDispatchZone(List<Long> packageIds, long targetDispatchLocationId, long driverUserId) {
        WarehouseLocation target = locationDao.findById(targetDispatchLocationId)
                .orElseThrow(() -> new IllegalArgumentException("Target dispatch/collection zone not found."));
        if (target.locationType() != LocationType.DISPATCH_ZONE) {
            throw new IllegalArgumentException("Order staging destination must be a DISPATCH_ZONE.");
        }
        int moved = inventoryService.relocatePackages(packageIds, targetDispatchLocationId,
                "Forklift order collection -> " + target.code(), driverUserId);
        if (notificationService != null && moved > 0) {
            notificationService.notifyRoles(
                    Set.of(RoleCode.MANAGER),
                    AppNotification.CAT_ORDER_READY,
                    "Collected at " + target.code(),
                    moved + " order package(s) scanned & placed at collection site " + target.code() + " by forklift.",
                    hu.due.rewear.ui.MainFrame.VIEW_ORDERS);
        }
        return moved;
    }
}
