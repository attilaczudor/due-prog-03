package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the outbox.
 *
 * <p>One question matters: what has changed since the number I last saw. Everything else here exists to
 * make that answer readable by a person as well as by a device.
 */
public final class ChangeLogDao {

    public enum Operation { INSERT, UPDATE, DELETE }

    /**
     * One change.
     *
     * @param sequence where it falls in the order changes happened. Handed out by the database and never
     *                 reused, so "everything after 412" means the same thing tomorrow.
     */
    public record Change(long sequence, String tableName, long rowId, Operation operation,
                         Long deviceId, String deviceName, Long userId, String userName) {

        /** Who to ask about this change: the person if there was one, otherwise the machine. */
        public String madeBy() {
            return userName != null ? userName : (deviceName == null ? "—" : deviceName);
        }
    }

    private static final String SELECT = """
            SELECT c.id, c.table_name, c.row_id, c.operation, c.device_id, d.name AS device_name,
                   c.user_id, u.full_name AS user_name
            FROM change_log c
            LEFT JOIN devices d ON d.id = c.device_id
            LEFT JOIN users u ON u.id = c.user_id
            """;

    private final DatabaseManager db;

    public ChangeLogDao(DatabaseManager db) {
        this.db = db;
    }

    /** The highest sequence number handed out, or zero on a log nothing has been written to. */
    public long latestSequence() {
        return scalar("SELECT COALESCE(MAX(id), 0) FROM change_log");
    }

    public long count() {
        return scalar("SELECT COUNT(*) FROM change_log");
    }

    /** How many changes a device that last saw {@code sequence} has to catch up on. */
    public long countSince(long sequence) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM change_log WHERE id > ?")) {
            ps.setLong(1, sequence);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException ex) {
            throw new DaoException("Could not count the changes since " + sequence, ex);
        }
    }

    /**
     * The changes after a sequence number, oldest first.
     *
     * <p>Bounded, because the answer to "everything since I was last here" may be a year's work and the
     * thing asking is a handheld. The last sequence in the batch is where the next one starts.
     */
    public List<Change> since(long sequence, int limit) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     SELECT + " WHERE c.id > ? ORDER BY c.id LIMIT ?")) {
            ps.setLong(1, sequence);
            ps.setInt(2, Math.max(1, Math.min(limit, 10_000)));
            try (ResultSet rs = ps.executeQuery()) {
                List<Change> changes = new ArrayList<>();
                while (rs.next()) {
                    changes.add(map(rs));
                }
                return List.copyOf(changes);
            }
        } catch (SQLException ex) {
            throw new DaoException("Could not read the changes since " + sequence, ex);
        }
    }

    /** Every change to one row, oldest first: what happened to this package, this run, this sheet. */
    public List<Change> forRow(String tableName, long rowId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     SELECT + " WHERE c.table_name = ? AND c.row_id = ? ORDER BY c.id")) {
            ps.setString(1, tableName);
            ps.setLong(2, rowId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Change> changes = new ArrayList<>();
                while (rs.next()) {
                    changes.add(map(rs));
                }
                return List.copyOf(changes);
            }
        } catch (SQLException ex) {
            throw new DaoException("Could not read the changes to " + tableName + " " + rowId, ex);
        }
    }

    private static Change map(ResultSet rs) throws SQLException {
        long deviceId = rs.getLong("device_id");
        boolean noDevice = rs.wasNull();
        long userId = rs.getLong("user_id");
        boolean nobody = rs.wasNull();
        return new Change(rs.getLong("id"), rs.getString("table_name"), rs.getLong("row_id"),
                Operation.valueOf(rs.getString("operation")), noDevice ? null : deviceId,
                rs.getString("device_name"), nobody ? null : userId, rs.getString("user_name"));
    }

    private long scalar(String sql) {
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException ex) {
            throw new DaoException("Could not read the change log", ex);
        }
    }
}
