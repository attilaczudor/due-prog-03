package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.WarehouseLocation;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class WarehouseLocationDao {

    private static final String BASE_SELECT = """
            SELECT wl.id, wl.code, wl.zone, wl.aisle, wl.shelf, wl.max_capacity_kg, wl.active,
                   COALESCE(SUM(CASE WHEN ip.status IN ('IN_STOCK', 'RESERVED') THEN ip.weight_kg ELSE 0 END), 0) AS used_weight_kg
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
                + " GROUP BY wl.id ORDER BY wl.code";
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

    public Optional<WarehouseLocation> findById(long id) {
        String sql = BASE_SELECT + " WHERE wl.id = ? GROUP BY wl.id";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find warehouse location by id: " + id, e);
        }
    }

    public long insert(String code, String zone, String aisle, String shelf, BigDecimal maxCapacityKg) {
        return db.executeInTransaction(conn -> insert(conn, code, zone, aisle, shelf, maxCapacityKg));
    }

    public long insert(Connection conn, String code, String zone, String aisle,
                       String shelf, BigDecimal maxCapacityKg) throws SQLException {
        String sql = """
                INSERT INTO warehouse_locations (code, zone, aisle, shelf, max_capacity_kg, active)
                VALUES (?, ?, ?, ?, ?, 1)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code.trim().toUpperCase());
            ps.setString(2, zone.trim().toUpperCase());
            ps.setString(3, aisle.trim());
            ps.setString(4, shelf.trim());
            ps.setDouble(5, maxCapacityKg.doubleValue());
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
        String sql = """
                UPDATE warehouse_locations
                SET code = ?, zone = ?, aisle = ?, shelf = ?, max_capacity_kg = ?, active = ?
                WHERE id = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code.trim().toUpperCase());
            ps.setString(2, zone.trim().toUpperCase());
            ps.setString(3, aisle.trim());
            ps.setString(4, shelf.trim());
            ps.setDouble(5, maxCapacityKg.doubleValue());
            ps.setInt(6, active ? 1 : 0);
            ps.setLong(7, id);
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
                BigDecimal.valueOf(rs.getDouble("max_capacity_kg")),
                rs.getInt("active") == 1,
                BigDecimal.valueOf(rs.getDouble("used_weight_kg"))
        );
    }
}
