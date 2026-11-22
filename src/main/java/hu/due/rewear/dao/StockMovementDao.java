package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.MovementType;
import hu.due.rewear.model.StockMovement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for the immutable warehouse audit trail ({@code stock_movements} table).
 * Records every intake, relocation, QR verification scan, order reservation/release, shipment, and discard event.
 */
public final class StockMovementDao {

    private static final String BASE_SELECT = """
            SELECT sm.id, sm.package_id, ip.qr_code AS pkg_qr, sm.movement_type,
                   sm.from_location_id, fl.code AS from_loc_code,
                   sm.to_location_id, tl.code AS to_loc_code,
                   sm.user_id, u.username, u.full_name, sm.note, sm.created_at
            FROM stock_movements sm
            JOIN inventory_packages ip ON ip.id = sm.package_id
            LEFT JOIN warehouse_locations fl ON fl.id = sm.from_location_id
            LEFT JOIN warehouse_locations tl ON tl.id = sm.to_location_id
            JOIN users u ON u.id = sm.user_id
            """;

    private final DatabaseManager db;

    public StockMovementDao(DatabaseManager db) {
        this.db = db;
    }

    public long insert(Connection conn, long packageId, MovementType type,
                       Long fromLocationId, Long toLocationId, long userId,
                       String note, LocalDateTime createdAt) throws SQLException {
        String sql = """
                INSERT INTO stock_movements (
                    package_id, movement_type, from_location_id, to_location_id, user_id, note, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, packageId);
            ps.setString(2, type.name());
            if (fromLocationId != null) {
                ps.setLong(3, fromLocationId);
            } else {
                ps.setNull(3, Types.INTEGER);
            }
            if (toLocationId != null) {
                ps.setLong(4, toLocationId);
            } else {
                ps.setNull(4, Types.INTEGER);
            }
            ps.setLong(5, userId);
            ps.setString(6, note);
            ps.setString(7, DaoDateUtil.format(createdAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for stock_movements insert");
            }
        }
    }

    public List<StockMovement> findByPackageId(long packageId) {
        String sql = BASE_SELECT + " WHERE sm.package_id = ? ORDER BY sm.created_at DESC, sm.id DESC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, packageId);
            try (ResultSet rs = ps.executeQuery()) {
                List<StockMovement> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to list movements for package: " + packageId, e);
        }
    }

    public List<StockMovement> findRecent(int limit) {
        String sql = BASE_SELECT + " ORDER BY sm.created_at DESC, sm.id DESC LIMIT ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<StockMovement> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to list recent stock movements", e);
        }
    }

    public List<StockMovement> findAll() {
        String sql = BASE_SELECT + " ORDER BY sm.created_at DESC, sm.id DESC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<StockMovement> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list all stock movements", e);
        }
    }

    private static StockMovement mapRow(ResultSet rs) throws SQLException {
        long fromId = rs.getLong("from_location_id");
        Long fromLocationId = rs.wasNull() ? null : fromId;
        long toId = rs.getLong("to_location_id");
        Long toLocationId = rs.wasNull() ? null : toId;

        return new StockMovement(
                rs.getLong("id"),
                rs.getLong("package_id"),
                rs.getString("pkg_qr"),
                MovementType.valueOf(rs.getString("movement_type")),
                fromLocationId,
                rs.getString("from_loc_code"),
                toLocationId,
                rs.getString("to_loc_code"),
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("full_name"),
                rs.getString("note"),
                DaoDateUtil.parse(rs.getString("created_at"))
        );
    }
}
