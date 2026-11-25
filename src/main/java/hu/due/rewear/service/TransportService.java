package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.DaoException;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.model.AppNotification;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.WarehouseLocation;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            return currentLocationType == LocationType.DISPATCH_ZONE
                    && (targetDispatchLocationId == null || currentLocationId == targetDispatchLocationId);
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


    private static final String STAGING_FROM = """
            FROM order_items oi
            JOIN orders o ON o.id = oi.order_id
            JOIN inventory_packages ip ON ip.id = oi.package_id
            JOIN warehouse_locations wl ON wl.id = ip.location_id
            """;
    private static final String ACTIVE_STAGING = """
            WHERE o.status IN ('DRAFT', 'PACKING') AND ip.status = 'RESERVED'
              AND (o.delivery_date IS NULL OR (o.status='PACKING'
                   AND o.assigned_forklift_id IS NOT NULL AND o.dispatch_location_id IS NOT NULL))
            """;

    public List<InventoryPackage> findPutawayQueue() {
        return packageDao.findPutawayQueue();
    }

    /** Returns all active order-staging tasks across all orders (used by Management / Dashboard). */
    public List<StagingTask> findOrderStagingTasks() {
        return findOrderStagingTasksForDriver(null);
    }

    public List<StagingTask> findOrderStagingTasksForDriver(Long driverId) {
        try (Connection conn = db.getConnection()) {
            return queryStaging(conn, driverId, "", null);
        } catch (SQLException e) {
            throw new DaoException("Failed to load forklift order staging tasks", e);
        }
    }

    public int countPendingStaging(Long driverId) {
        String sql = "SELECT COUNT(*) " + STAGING_FROM + ACTIVE_STAGING + """
                AND (wl.location_type <> 'DISPATCH_ZONE'
                     OR (o.dispatch_location_id IS NOT NULL AND ip.location_id <> o.dispatch_location_id))
                """ + (driverId == null ? "" : " AND o.assigned_forklift_id = ?");
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (driverId != null) {
                ps.setLong(1, driverId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to count pending staging tasks", e);
        }
    }

    private List<StagingTask> queryStaging(Connection conn, Long driverId, String filter, Object value)
            throws SQLException {
        String sql = """
                SELECT ip.id AS package_id, ip.qr_code, ip.weight_kg,
                       wl.id AS loc_id, wl.code AS loc_code, wl.location_type,
                       o.id AS order_id, o.order_number, c.name AS customer_name, o.status AS order_status,
                       o.assigned_forklift_id, fk.full_name AS assigned_forklift_name,
                       o.dispatch_location_id, dl.code AS dispatch_location_code, o.customer_arrived_at
                """ + STAGING_FROM + """
                JOIN customers c ON c.id = o.customer_id
                LEFT JOIN users fk ON fk.id = o.assigned_forklift_id
                LEFT JOIN warehouse_locations dl ON dl.id = o.dispatch_location_id
                """ + ACTIVE_STAGING + filter + (driverId == null ? "" : " AND o.assigned_forklift_id = ?")
                + """
                 ORDER BY o.customer_arrived_at IS NULL,
                          wl.location_type = 'DISPATCH_ZONE'
                              AND (o.dispatch_location_id IS NULL OR ip.location_id = o.dispatch_location_id),
                          o.delivery_date IS NULL, o.delivery_date, o.order_number, ip.qr_code
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            if (value != null) {
                ps.setObject(index++, value);
            }
            if (driverId != null) {
                ps.setLong(index, driverId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<StagingTask> tasks = new ArrayList<>();
                while (rs.next()) {
                    long forkliftId = rs.getLong("assigned_forklift_id");
                    Long assignedForklift = rs.wasNull() ? null : forkliftId;
                    long dockId = rs.getLong("dispatch_location_id");
                    Long assignedDock = rs.wasNull() ? null : dockId;
                    tasks.add(new StagingTask(
                            rs.getLong("package_id"), rs.getString("qr_code"),
                            hu.due.rewear.dao.ExactDecimal.get(rs, "weight_kg"), rs.getLong("loc_id"),
                            rs.getString("loc_code"), LocationType.fromDb(rs.getString("location_type")),
                            rs.getLong("order_id"), rs.getString("order_number"), rs.getString("customer_name"),
                            rs.getString("order_status"), assignedForklift, rs.getString("assigned_forklift_name"),
                            assignedDock, rs.getString("dispatch_location_code"),
                            rs.getString("customer_arrived_at") != null));
                }
                return tasks;
            }
        }
    }

    /** Moves one or more packages from an Operator Station onto a storage shelf. */
    public int putawayToStorage(List<Long> packageIds, long targetStorageLocationId, long driverUserId) {
        return db.executeInTransaction(conn -> {
            WarehouseLocation target = requireActiveLocation(conn, targetStorageLocationId, LocationType.STORAGE);
            return inventoryService.relocatePackages(conn, packageIds, target.id(),
                    "Forklift putaway -> " + target.code(), driverUserId);
        });
    }

    /** Collects one package and advances its order in the same transaction. */
    public StagingTask scanAndCollectForOrder(String rawQrCode, Long fallbackDispatchLocationId,
                                             long driverUserId, boolean enforceAssignedDriver) {
        if (rawQrCode == null || rawQrCode.isBlank()) {
            throw new IllegalArgumentException("Scan or enter a package QR code.");
        }
        String qr = rawQrCode.trim().toUpperCase(Locale.ROOT);
        Long driverFilter = enforceAssignedDriver ? driverUserId : null;
        Map<Long, StagingTask> readyOrders = new LinkedHashMap<>();
        StagingTask result = db.executeInTransaction(conn -> {
            List<StagingTask> matches = queryStaging(conn, driverFilter, " AND ip.qr_code = ?", qr);
            if (matches.isEmpty()) {
                matches = queryStaging(conn, driverFilter, " AND ip.qr_code = ? COLLATE NOCASE", qr);
            }
            if (matches.size() != 1) {
                throw new IllegalArgumentException("Package " + qr + " must match one eligible open order.");
            }
            StagingTask task = matches.get(0);
            Long dockId = task.targetDispatchLocationId() != null
                    ? task.targetDispatchLocationId() : fallbackDispatchLocationId;
            long targetId = dockId != null ? dockId : firstActiveDock(conn);
            stagePackage(conn, task, targetId, driverUserId, readyOrders);
            return queryStaging(conn, driverFilter, " AND ip.id = ?", task.packageId()).get(0);
        });
        notifyReadyOrders(readyOrders);
        return result;
    }

    public int stageToDispatchZone(List<Long> packageIds, long targetDispatchLocationId, long driverUserId) {
        return stageToDispatchZone(packageIds, targetDispatchLocationId, driverUserId, false);
    }

    /** Stages an entire selection atomically and respects assigned docks and driver visibility. */
    public int stageToDispatchZone(List<Long> packageIds, long targetDispatchLocationId,
                                   long driverUserId, boolean enforceAssignedDriver) {
        if (packageIds == null || packageIds.isEmpty()) {
            return 0;
        }
        Long driverFilter = enforceAssignedDriver ? driverUserId : null;
        Map<Long, StagingTask> readyOrders = new LinkedHashMap<>();
        int moved = db.executeInTransaction(conn -> {
            int count = 0;
            for (Long packageId : packageIds) {
                if (packageId == null) {
                    continue;
                }
                List<StagingTask> matches = queryStaging(conn, driverFilter, " AND ip.id = ?", packageId);
                if (matches.size() != 1) {
                    throw new IllegalArgumentException("Package " + packageId + " must match one eligible open order.");
                }
                count += stagePackage(conn, matches.get(0), targetDispatchLocationId, driverUserId, readyOrders);
            }
            return count;
        });
        notifyReadyOrders(readyOrders);
        return moved;
    }

    private int stagePackage(Connection conn, StagingTask task, long dockId, long driverUserId,
                             Map<Long, StagingTask> readyOrders) throws SQLException {
        if (task.targetDispatchLocationId() != null && task.targetDispatchLocationId() != dockId) {
            throw new IllegalArgumentException("Order " + task.orderNumber() + " is assigned to "
                    + task.targetDispatchLocationCode() + ".");
        }
        try (var ps = conn.prepareStatement("""
                SELECT 1 FROM orders o JOIN users u ON u.id=? JOIN roles r ON r.id=u.role_id
                WHERE o.id=? AND o.delivery_date IS NOT NULL AND r.code IN ('DELIVERY_DRIVER','SUPPLY_DRIVER')
                  AND (u.active=0 OR o.assigned_forklift_id<>u.id)
                """)) {
            ps.setLong(1, driverUserId);
            ps.setLong(2, task.orderId());
            try (var rows = ps.executeQuery()) {
                if (rows.next()) throw new IllegalStateException("This scheduled order belongs to another warehouse driver.");
            }
        }
        WarehouseLocation dock = requireActiveLocation(conn, dockId, LocationType.DISPATCH_ZONE);
        if (task.currentLocationId() != dock.id()
                && dock.usedWeightKg().add(task.weightKg()).compareTo(dock.maxCapacityKg()) > 0) {
            throw new IllegalStateException("Dispatch zone " + dock.code() + " has insufficient capacity.");
        }
        int moved = inventoryService.relocatePackages(conn, List.of(task.packageId()), dock.id(),
                "Forklift order collection -> " + dock.code(), driverUserId);
        if ("DRAFT".equals(task.orderStatus()) || task.targetDispatchLocationId() == null) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE orders SET status = 'PACKING', dispatch_location_id = ?
                    WHERE id = ?
                    """)) {
                ps.setLong(1, dock.id());
                ps.setLong(2, task.orderId());
                ps.executeUpdate();
            }
        }
        if (moved > 0 && allPackagesStaged(conn, task.orderId(), dock.id())) {
            readyOrders.put(task.orderId(), task);
        }
        return moved;
    }

    private boolean allPackagesStaged(Connection conn, long orderId, long dockId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT NOT EXISTS (
                    SELECT 1 FROM order_items oi
                    JOIN inventory_packages ip ON ip.id = oi.package_id
                    WHERE oi.order_id = ? AND (ip.location_id <> ? OR ip.status <> 'RESERVED')
                )
                """)) {
            ps.setLong(1, orderId);
            ps.setLong(2, dockId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.getBoolean(1);
            }
        }
    }

    private WarehouseLocation requireActiveLocation(Connection conn, long id, LocationType type) throws SQLException {
        return locationDao.findById(conn, id)
                .filter(location -> location.active() && location.locationType() == type)
                .orElseThrow(() -> new IllegalArgumentException("Choose an active " + type + " destination."));
    }

    private long firstActiveDock(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id FROM warehouse_locations WHERE location_type = 'DISPATCH_ZONE' AND active = 1
                ORDER BY code LIMIT 1
                """);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            throw new IllegalStateException("No active dispatch zone is configured.");
        }
    }

    private void notifyReadyOrders(Map<Long, StagingTask> readyOrders) {
        if (notificationService == null) {
            return;
        }
        for (StagingTask task : readyOrders.values()) {
            notificationService.notifyRoles(Set.of(RoleCode.MANAGER), AppNotification.CAT_ORDER_READY,
                    "Ready for pickup: " + task.orderNumber(),
                    "All packages for " + task.orderNumber() + " are at the assigned dispatch zone.",
                    hu.due.rewear.ui.MainFrame.VIEW_ORDERS);
        }
    }
}
