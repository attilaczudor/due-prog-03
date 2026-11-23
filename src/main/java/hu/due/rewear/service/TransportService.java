package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.WarehouseLocation;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Warehouse forklift / in-house transporter workflow service handling:
 * <ol>
 *     <li><b>Putaway Queue:</b> moving newly sorted {@code IN_STOCK} packages from
 *     {@link LocationType#OPERATOR_STATION} workstations to {@link LocationType#STORAGE} shelves.</li>
 *     <li><b>Order Staging Queue:</b> picking {@code RESERVED} packages belonging to active
 *     ({@code DRAFT}/{@code PACKING}) orders from storage shelves and moving them to the
 *     {@link LocationType#DISPATCH_ZONE} connection/loading dock.</li>
 * </ol>
 */
public final class TransportService {

    /** Projection row for an order-allocated package awaiting movement to the dispatch/connection zone. */
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
            String orderStatus
    ) {
        public boolean isAtDispatchZone() {
            return currentLocationType == LocationType.DISPATCH_ZONE;
        }
    }

    private final DatabaseManager db;
    private final InventoryPackageDao packageDao;
    private final WarehouseLocationDao locationDao;
    private final InventoryService inventoryService;

    public TransportService(DatabaseManager db,
                            InventoryPackageDao packageDao,
                            WarehouseLocationDao locationDao,
                            InventoryService inventoryService) {
        this.db = db;
        this.packageDao = packageDao;
        this.locationDao = locationDao;
        this.inventoryService = inventoryService;
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

    /**
     * Returns all packages allocated to active ({@code DRAFT} / {@code PACKING}) orders so the forklift driver
     * can see which ones still need moving to {@link LocationType#DISPATCH_ZONE} and which are already staged.
     */
    public List<StagingTask> findOrderStagingTasks() {
        String sql = """
                SELECT ip.id AS package_id, ip.qr_code, ip.weight_kg,
                       wl.id AS loc_id, wl.code AS loc_code, wl.location_type,
                       o.id AS order_id, o.order_number, c.name AS customer_name, o.status AS order_status
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                JOIN customers c ON c.id = o.customer_id
                JOIN inventory_packages ip ON ip.id = oi.package_id
                JOIN warehouse_locations wl ON wl.id = ip.location_id
                WHERE o.status IN ('DRAFT', 'PACKING')
                  AND ip.status = 'RESERVED'
                ORDER BY CASE WHEN wl.location_type = 'DISPATCH_ZONE' THEN 1 ELSE 0 END,
                         o.order_number ASC, ip.qr_code ASC
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<StagingTask> tasks = new ArrayList<>();
            while (rs.next()) {
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
                        rs.getString("order_status")
                ));
            }
            return tasks;
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

    /** Moves one or more order-reserved packages to a {@link LocationType#DISPATCH_ZONE} connection dock. */
    public int stageToDispatchZone(List<Long> packageIds, long targetDispatchLocationId, long driverUserId) {
        WarehouseLocation target = locationDao.findById(targetDispatchLocationId)
                .orElseThrow(() -> new IllegalArgumentException("Target dispatch/connection zone not found."));
        if (target.locationType() != LocationType.DISPATCH_ZONE) {
            throw new IllegalArgumentException("Order staging destination must be a DISPATCH_ZONE.");
        }
        return inventoryService.relocatePackages(packageIds, targetDispatchLocationId,
                "Forklift order staging -> " + target.code(), driverUserId);
    }
}
