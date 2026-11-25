package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.WarehouseLocation;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Data Access Object for physical warehouse locations ({@code warehouse_locations} table) —
 * covering {@link LocationType#OPERATOR_STATION}, {@link LocationType#STORAGE}, and
 * {@link LocationType#DISPATCH_ZONE} bays with live aggregated weight occupancy.
 */
public final class WarehouseLocationDao {

    private static final String BASE_SELECT = """
            SELECT wl.id, wl.code, wl.zone, wl.aisle, wl.shelf, wl.location_type, wl.max_capacity_kg, wl.active,
                   COALESCE(SUM(CASE WHEN ip.status IN ('IN_STOCK', 'RESERVED') THEN CAST(ip.weight_kg AS REAL) ELSE 0 END), 0) AS used_weight_kg
            FROM warehouse_locations wl
            LEFT JOIN inventory_packages ip ON ip.location_id = wl.id
            """;

    private final DatabaseManager db;

    public WarehouseLocationDao(DatabaseManager db) {
        this.db = db;
    }

    public List<WarehouseLocation> findAll(boolean activeOnly) {
        String sql = BASE_SELECT
                + (activeOnly ? " WHERE wl.active = 1 " : "")
                + " GROUP BY wl.id ORDER BY wl.location_type, wl.code";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<WarehouseLocation> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list warehouse locations", e);
        }
    }

    /** Lists active (or all) locations matching a specific functional {@link LocationType}. */
    public List<WarehouseLocation> findByType(LocationType type, boolean activeOnly) {
        String sql = BASE_SELECT
                + " WHERE wl.location_type = ?" + (activeOnly ? " AND wl.active = 1 " : " ")
                + " GROUP BY wl.id ORDER BY wl.code";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, (type != null ? type : LocationType.STORAGE).name());
            try (ResultSet rs = ps.executeQuery()) {
                List<WarehouseLocation> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to list warehouse locations by type: " + type, e);
        }
    }

    public Optional<WarehouseLocation> findById(long id) {
        try (Connection conn = db.getConnection()) {
            return findById(conn, id);
        } catch (SQLException e) {
            throw new DaoException("Failed to find warehouse location by id: " + id, e);
        }
    }

    /** Connection-reusing lookup for active write transactions. */
    public Optional<WarehouseLocation> findById(Connection conn, long id) throws SQLException {
        String sql = BASE_SELECT + " WHERE wl.id = ? GROUP BY wl.id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public long insert(String code, String zone, String aisle, String shelf, BigDecimal maxCapacityKg) {
        return insert(code, zone, aisle, shelf, LocationType.STORAGE, maxCapacityKg);
    }

    public long insert(String code, String zone, String aisle, String shelf,
                       LocationType locationType, BigDecimal maxCapacityKg) {
        return db.executeInTransaction(conn -> insert(conn, code, zone, aisle, shelf, locationType, maxCapacityKg));
    }

    public long insert(Connection conn, String code, String zone, String aisle,
                       String shelf, BigDecimal maxCapacityKg) throws SQLException {
        return insert(conn, code, zone, aisle, shelf, LocationType.STORAGE, maxCapacityKg);
    }

    public long insert(Connection conn, String code, String zone, String aisle,
                       String shelf, LocationType locationType, BigDecimal maxCapacityKg) throws SQLException {
        String sql = """
                INSERT INTO warehouse_locations (code, zone, aisle, shelf, location_type, max_capacity_kg, active)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code.trim().toUpperCase(Locale.ROOT));
            ps.setString(2, zone.trim().toUpperCase(Locale.ROOT));
            ps.setString(3, aisle.trim());
            ps.setString(4, shelf.trim());
            ps.setString(5, (locationType != null ? locationType : LocationType.STORAGE).name());
            ExactDecimal.set(ps, 6, maxCapacityKg);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for warehouse_locations insert");
            }
        }
    }

    public void update(long id, String code, String zone, String aisle, String shelf,
                       BigDecimal maxCapacityKg, boolean active) {
        LocationType currentType = findById(id).map(WarehouseLocation::locationType).orElse(LocationType.STORAGE);
        update(id, code, zone, aisle, shelf, currentType, maxCapacityKg, active);
    }

    public void update(long id, String code, String zone, String aisle, String shelf,
                       LocationType locationType, BigDecimal maxCapacityKg, boolean active) {
        String sql = """
                UPDATE warehouse_locations
                SET code = ?, zone = ?, aisle = ?, shelf = ?, location_type = ?, max_capacity_kg = ?, active = ?
                WHERE id = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code.trim().toUpperCase(Locale.ROOT));
            ps.setString(2, zone.trim().toUpperCase(Locale.ROOT));
            ps.setString(3, aisle.trim());
            ps.setString(4, shelf.trim());
            ps.setString(5, (locationType != null ? locationType : LocationType.STORAGE).name());
            ExactDecimal.set(ps, 6, maxCapacityKg);
            ps.setInt(7, active ? 1 : 0);
            ps.setLong(8, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update warehouse location: " + id, e);
        }
    }

    private static WarehouseLocation mapRow(ResultSet rs) throws SQLException {
        return new WarehouseLocation(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("zone"),
                rs.getString("aisle"),
                rs.getString("shelf"),
                LocationType.fromDb(rs.getString("location_type")),
                ExactDecimal.get(rs, "max_capacity_kg"),
                rs.getInt("active") == 1,
                BigDecimal.valueOf(rs.getDouble("used_weight_kg"))
        );
    }
}
