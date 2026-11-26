package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.SyncConflict;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The rows two machines disagree about, and what each device has been told so far.
 *
 * <p>Both tables exist for the same reason: a sync has to know where it got to, and a disagreement has to
 * survive being noticed. Neither is of any use until there is a second machine, and both are cheap enough
 * to be in place before there is.
 */
public final class SyncConflictDao {

    private static final String SELECT = """
            SELECT c.id, c.table_name, c.row_id, c.state, c.our_sequence, c.our_device_id,
                   d.name AS our_device_name, u.full_name AS our_user_name,
                   c.their_device, c.their_user, c.their_sequence, c.noticed_at,
                   c.resolved_at, c.resolved_by, r.full_name AS resolved_by_name, c.resolution
            FROM sync_conflicts c
            LEFT JOIN devices d ON d.id = c.our_device_id
            LEFT JOIN users u ON u.id = c.our_user_id
            LEFT JOIN users r ON r.id = c.resolved_by
            """;

    /** How far this database and one device have got with each other. */
    public record SyncState(long deviceId, long sentThrough, long receivedThrough,
                            LocalDateTime lastSyncedAt) { }

    private final DatabaseManager db;

    public SyncConflictDao(DatabaseManager db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- where each device got to

    /**
     * What this database and a device have told each other.
     *
     * <p>A device nobody has ever synced with starts at zero, which means everything in the log is news to
     * it — the honest answer, and the reason a first sync is a bootstrap rather than a replay.
     */
    public SyncState stateFor(long deviceId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT device_id, sent_through, received_through, last_synced_at
                     FROM device_sync_state WHERE device_id = ?""")) {
            ps.setLong(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new SyncState(deviceId, 0, 0, null);
                }
                return new SyncState(rs.getLong("device_id"), rs.getLong("sent_through"),
                        rs.getLong("received_through"), DaoDateUtil.parse(rs.getString("last_synced_at")));
            }
        } catch (SQLException ex) {
            throw new DaoException("Could not read the sync state for device " + deviceId, ex);
        }
    }

    public void recordSync(long deviceId, long sentThrough, long receivedThrough) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO device_sync_state(device_id, sent_through, received_through, last_synced_at)
                     VALUES (?,?,?,?)
                     ON CONFLICT(device_id) DO UPDATE SET
                         sent_through = MAX(sent_through, excluded.sent_through),
                         received_through = MAX(received_through, excluded.received_through),
                         last_synced_at = excluded.last_synced_at""")) {
            ps.setLong(1, deviceId);
            ps.setLong(2, sentThrough);
            ps.setLong(3, receivedThrough);
            ps.setString(4, DaoDateUtil.now());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DaoException("Could not record the sync with device " + deviceId, ex);
        }
    }

    // ---------------------------------------------------------------- the disagreements

    /** Records a conflict, naming the person signed in on this side as well as the machine. */
    public long open(String table, long rowId, long ourSequence, Long ourDeviceId, Long ourUserId,
                     String theirDevice, String theirUser, long theirSequence) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO sync_conflicts(table_name, row_id, state, our_sequence, our_device_id,
                         our_user_id, their_device, their_user, their_sequence, noticed_at)
                     VALUES (?,?,'OPEN',?,?,?,?,?,?,?) RETURNING id""")) {
            ps.setString(1, table);
            ps.setLong(2, rowId);
            ps.setLong(3, ourSequence);
            setNullableLong(ps, 4, ourDeviceId);
            setNullableLong(ps, 5, ourUserId);
            ps.setString(6, theirDevice);
            ps.setString(7, theirUser);
            ps.setLong(8, theirSequence);
            ps.setString(9, DaoDateUtil.now());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException ex) {
            throw new DaoException("Could not record the conflict over " + table, ex);
        }
    }

    public List<SyncConflict> findOpen() {
        return many(SELECT + " WHERE c.state = 'OPEN' ORDER BY c.id");
    }

    public List<SyncConflict> findAll() {
        return many(SELECT + " ORDER BY c.id DESC");
    }

    public Optional<SyncConflict> find(long id) {
        List<SyncConflict> rows = many(SELECT + " WHERE c.id = " + id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long countOpen() {
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sync_conflicts WHERE state = 'OPEN'")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException ex) {
            throw new DaoException("Could not count the open conflicts", ex);
        }
    }

    /** Settles one, with what was decided written down: the record is the point, not the button. */
    public int resolve(long id, long userId, String resolution) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE sync_conflicts SET state = 'RESOLVED', resolved_at = ?, resolved_by = ?,
                         resolution = ? WHERE id = ? AND state = 'OPEN'""")) {
            ps.setString(1, DaoDateUtil.now());
            ps.setLong(2, userId);
            ps.setString(3, resolution);
            ps.setLong(4, id);
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DaoException("Could not settle conflict " + id, ex);
        }
    }

    private List<SyncConflict> many(String sql) {
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            List<SyncConflict> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(map(rs));
            }
            return List.copyOf(rows);
        } catch (SQLException ex) {
            throw new DaoException("Could not read the conflicts", ex);
        }
    }

    private static SyncConflict map(ResultSet rs) throws SQLException {
        long ourDevice = rs.getLong("our_device_id");
        boolean noDevice = rs.wasNull();
        long resolvedBy = rs.getLong("resolved_by");
        boolean unresolved = rs.wasNull();
        return new SyncConflict(rs.getLong("id"), rs.getString("table_name"), rs.getLong("row_id"),
                SyncConflict.State.valueOf(rs.getString("state")), rs.getLong("our_sequence"),
                noDevice ? null : ourDevice, rs.getString("our_device_name"), rs.getString("our_user_name"),
                rs.getString("their_device"), rs.getString("their_user"), rs.getLong("their_sequence"),
                DaoDateUtil.parse(rs.getString("noticed_at")), DaoDateUtil.parse(rs.getString("resolved_at")),
                unresolved ? null : resolvedBy, rs.getString("resolved_by_name"), rs.getString("resolution"));
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }
}
