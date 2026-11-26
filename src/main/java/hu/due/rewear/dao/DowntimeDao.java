package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.DowntimeEvent;
import hu.due.rewear.model.DowntimeReason;
import hu.due.rewear.model.LossGroup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes the stoppage log and the reason codes behind it.
 *
 * <p>Minutes are summed as integers, the way grams are in grade yield: the sums are exact in SQL and nobody
 * has to argue about the last digit. Two different totals are kept apart on purpose. Wall-clock minutes are
 * how long the line stood still, which is what a person's own non-productive time means; person-minutes —
 * wall-clock times the number of people affected — are what an availability figure has to be built from,
 * because a half-hour that stopped four people cost four times what a half-hour that stopped one did.
 */
public final class DowntimeDao {

    private static final String EVENT_SELECT = """
            SELECT e.id, e.work_day, e.team_id, t.name AS team_name, e.sorter_id, u.full_name AS sorter_name,
                   e.station_id, l.code AS station_code, e.started_at, e.ended_at, e.minutes,
                   e.people_affected, e.reason_id, r.code AS reason_code, r.loss_group, r.name_en, r.name_hu,
                   r.attributable, e.note, e.opened_by, o.full_name AS opened_by_name, e.opened_at,
                   e.closed_by, e.closed_at
            FROM downtime_events e
            LEFT JOIN sorting_teams t ON t.id = e.team_id
            LEFT JOIN users u ON u.id = e.sorter_id
            LEFT JOIN warehouse_locations l ON l.id = e.station_id
            LEFT JOIN downtime_reasons r ON r.id = e.reason_id
            LEFT JOIN users o ON o.id = e.opened_by
            """;

    private static final String REASON_SELECT = """
            SELECT id, code, loss_group, name_en, name_hu, attributable, active, created_by, created_at,
                   retired_at
            FROM downtime_reasons
            """;

    /** How a stoppage report is narrowed. Every field is optional; the nulls simply do not filter. */
    public record Scope(LocalDate from, LocalDate to, Long teamId, Long sorterId) {

        public static Scope all() {
            return new Scope(null, null, null, null);
        }

        public static Scope day(LocalDate day) {
            return new Scope(day, day, null, null);
        }

        public Scope forTeam(Long teamId) {
            return new Scope(from, to, teamId, sorterId);
        }

        public Scope forSorter(Long sorterId) {
            return new Scope(from, to, teamId, sorterId);
        }
    }

    /** Minutes lost to one reason, both ways round. */
    public record ReasonMinutes(long reasonId, long minutes, long personMinutes) { }

    private final DatabaseManager db;

    public DowntimeDao(DatabaseManager db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- reasons

    public List<DowntimeReason> findReasons(boolean activeOnly) {
        String sql = REASON_SELECT + (activeOnly ? " WHERE active = 1" : "")
                + " ORDER BY loss_group, code";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<DowntimeReason> reasons = new ArrayList<>();
            while (rs.next()) {
                reasons.add(mapReason(rs));
            }
            return reasons;
        } catch (SQLException ex) {
            throw new DaoException("Failed to read downtime reasons", ex);
        }
    }

    public Optional<DowntimeReason> findReason(long id) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(REASON_SELECT + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapReason(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read downtime reason " + id, ex);
        }
    }

    public long insertReason(Connection conn, String code, LossGroup group, String nameEn, String nameHu,
                             boolean attributable, Long createdBy, LocalDateTime at) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO downtime_reasons (code, loss_group, name_en, name_hu, attributable, active,
                    created_by, created_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code);
            ps.setString(2, group.name());
            ps.setString(3, nameEn);
            ps.setString(4, nameHu);
            ps.setInt(5, attributable ? 1 : 0);
            if (createdBy == null) {
                ps.setNull(6, Types.INTEGER);
            } else {
                ps.setLong(6, createdBy);
            }
            ps.setString(7, DaoDateUtil.format(at));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for downtime_reasons insert");
            }
        }
    }

    /** Rewording is the point of the fortnightly review, so a reason's text and group can change. */
    public int updateReason(Connection conn, long id, LossGroup group, String nameEn, String nameHu,
                            boolean attributable) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE downtime_reasons SET loss_group = ?, name_en = ?, name_hu = ?, attributable = ?
                WHERE id = ?
                """)) {
            ps.setString(1, group.name());
            ps.setString(2, nameEn);
            ps.setString(3, nameHu);
            ps.setInt(4, attributable ? 1 : 0);
            ps.setLong(5, id);
            return ps.executeUpdate();
        }
    }

    public int retireReason(Connection conn, long id, LocalDateTime at) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE downtime_reasons SET active = 0, retired_at = ? WHERE id = ? AND active = 1
                """)) {
            ps.setString(1, DaoDateUtil.format(at));
            ps.setLong(2, id);
            return ps.executeUpdate();
        }
    }

    public int reinstateReason(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE downtime_reasons SET active = 1, retired_at = NULL WHERE id = ? AND active = 0
                """)) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- events

    public long insertEvent(Connection conn, LocalDate workDay, Long teamId, Long sorterId, Long stationId,
                            LocalDateTime startedAt, LocalDateTime endedAt, Integer minutes, int peopleAffected,
                            Long reasonId, String note, long openedBy, LocalDateTime openedAt,
                            Long closedBy, LocalDateTime closedAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO downtime_events (work_day, team_id, sorter_id, station_id, started_at, ended_at,
                    minutes, people_affected, reason_id, note, opened_by, opened_at, closed_by, closed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, workDay.toString());
            setNullableLong(ps, 2, teamId);
            setNullableLong(ps, 3, sorterId);
            setNullableLong(ps, 4, stationId);
            ps.setString(5, DaoDateUtil.format(startedAt));
            ps.setString(6, DaoDateUtil.format(endedAt));
            if (minutes == null) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setInt(7, minutes);
            }
            ps.setInt(8, peopleAffected);
            setNullableLong(ps, 9, reasonId);
            ps.setString(10, note);
            ps.setLong(11, openedBy);
            ps.setString(12, DaoDateUtil.format(openedAt));
            setNullableLong(ps, 13, closedBy);
            ps.setString(14, DaoDateUtil.format(closedAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for downtime_events insert");
            }
        }
    }

    /** Closes a running stoppage. Returns 0 if it was already closed, so the caller can say so. */
    public int closeEvent(Connection conn, long id, LocalDateTime endedAt, int minutes, long reasonId,
                          String note, long closedBy, LocalDateTime closedAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE downtime_events
                SET ended_at = ?, minutes = ?, reason_id = ?, note = COALESCE(?, note),
                    closed_by = ?, closed_at = ?
                WHERE id = ? AND ended_at IS NULL
                """)) {
            ps.setString(1, DaoDateUtil.format(endedAt));
            ps.setInt(2, minutes);
            ps.setLong(3, reasonId);
            ps.setString(4, note);
            ps.setLong(5, closedBy);
            ps.setString(6, DaoDateUtil.format(closedAt));
            ps.setLong(7, id);
            return ps.executeUpdate();
        }
    }

    public Optional<DowntimeEvent> findEvent(long id) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(EVENT_SELECT + " WHERE e.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapEvent(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read downtime event " + id, ex);
        }
    }

    public List<DowntimeEvent> findEvents(Scope scope) {
        List<Object> params = new ArrayList<>();
        String sql = EVENT_SELECT + where(scope, params) + " ORDER BY e.started_at DESC, e.id DESC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<DowntimeEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(mapEvent(rs));
                }
                return events;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read downtime events", ex);
        }
    }

    /** Stoppages that are still running, whatever day they were opened on. */
    public List<DowntimeEvent> findRunning() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(EVENT_SELECT
                     + " WHERE e.ended_at IS NULL ORDER BY e.started_at");
             ResultSet rs = ps.executeQuery()) {
            List<DowntimeEvent> events = new ArrayList<>();
            while (rs.next()) {
                events.add(mapEvent(rs));
            }
            return events;
        } catch (SQLException ex) {
            throw new DaoException("Failed to read running downtime", ex);
        }
    }

    /**
     * Stoppages still running that cover one person or one team, by the same rule the report filter uses:
     * a stoppage with nobody named covers the team, and a stoppage with no team covers the whole line.
     *
     * <p>Both arguments null means every running stoppage, which is what a production leader's work list
     * wants to see.
     */
    public List<DowntimeEvent> findRunningCovering(Long teamId, Long sorterId) {
        StringBuilder sql = new StringBuilder(EVENT_SELECT + " WHERE e.ended_at IS NULL");
        List<Object> params = new ArrayList<>();
        appendCovering(sql, teamId, sorterId, params);
        sql.append(" ORDER BY e.started_at");
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<DowntimeEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(mapEvent(rs));
                }
                return events;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read running downtime", ex);
        }
    }

    public List<ReasonMinutes> minutesByReason(Scope scope) {
        List<Object> params = new ArrayList<>();
        // When the scope is one person, the person-minutes are that person's minutes. The other three people
        // standing at the same bench are a fact about the team's loss, not about hers, and adding them into
        // her figure is precisely the arithmetic that makes somebody distrust a bonus calculation.
        String personMinutes = scope.sorterId() == null
                ? "COALESCE(SUM(e.minutes * e.people_affected), 0)"
                : "COALESCE(SUM(e.minutes), 0)";
        String sql = "SELECT e.reason_id, COALESCE(SUM(e.minutes), 0), " + personMinutes
                + " FROM downtime_events e"
                + where(scope, params) + " AND e.minutes IS NOT NULL GROUP BY e.reason_id";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<ReasonMinutes> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new ReasonMinutes(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
                }
                return rows;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to total downtime by reason", ex);
        }
    }

    /**
     * Rostered sorter-minutes in the period: the denominator an availability figure needs.
     *
     * <p>Sorters only. The line's capacity is the time of the people who sort on it; a helper packs what
     * comes off the bench and a driver feeds it, and counting their hours in would make the same stoppage
     * look smaller than it was.
     */
    public long rosteredMinutes(LocalDate from, LocalDate to, Long teamId, Long sorterId) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(SUM(CASE WHEN s.end_minute > s.start_minute
                                         THEN s.end_minute - s.start_minute
                                         ELSE s.end_minute + 1440 - s.start_minute END), 0)
                FROM rota_shifts s
                JOIN users u ON u.id = s.user_id
                JOIN roles r ON r.id = u.role_id
                WHERE s.shift_code <> 'OFF' AND r.code = 'SORTER'
                """);
        if (from != null) {
            sql.append(" AND s.work_day >= ?");
            params.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND s.work_day <= ?");
            params.add(to.toString());
        }
        if (sorterId != null) {
            sql.append(" AND s.user_id = ?");
            params.add(sorterId);
        }
        if (teamId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM sorting_team_members m"
                    + " WHERE m.sorter_id = s.user_id AND m.team_id = ?)");
            params.add(teamId);
        }
        return scalar(sql.toString(), params);
    }

    /**
     * Minutes between a sorter calling for material and a driver delivering it.
     *
     * <p>This is the one piece of non-productive time the application has always been able to work out for
     * itself, because both timestamps were already being written. Each wait is rounded to the nearest
     * minute rather than truncated: a day of twenty-five-minute waits read back as twenty-four would lose an
     * hour nobody could account for, since the difference of two dates in SQLite is a floating-point day. It is reported next to the logged
     * stoppages and never added to them: if the shift leader also logged "no material at the bench" for the
     * same wait, adding the two would count that wait twice.
     */
    public long waitingForStockMinutes(Scope scope) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(SUM(CAST(ROUND((julianday(q.delivered_at) - julianday(q.requested_at))
                                               * 1440.0) AS INTEGER)), 0)
                FROM sorting_requests q
                WHERE q.delivered_at IS NOT NULL AND q.requested_at IS NOT NULL
                """);
        // The window is a range of moments rather than a comparison of dates, because the call was stored
        // as a UTC instant and the day asked about is a Budapest one: a call at half past midnight belongs
        // to the day the sorter was working, not to the day UTC had reached.
        if (scope.from() != null) {
            sql.append(" AND q.requested_at >= ?");
            params.add(DaoDateUtil.dayRange(scope.from())[0]);
        }
        if (scope.to() != null) {
            sql.append(" AND q.requested_at < ?");
            params.add(DaoDateUtil.dayRange(scope.to())[1]);
        }
        if (scope.teamId() != null) {
            sql.append(" AND q.team_id = ?");
            params.add(scope.teamId());
        }
        if (scope.sorterId() != null) {
            sql.append(" AND q.sorter_id = ?");
            params.add(scope.sorterId());
        }
        return scalar(sql.toString(), params);
    }

    /**
     * How many people a stoppage stops: one named sorter, everybody active on a team, or — when it is
     * nobody's in particular — every sorter rostered that day, falling back to the whole active roster when
     * no rota has been written.
     */
    public int peopleAffected(Connection conn, Long teamId, Long sorterId, LocalDate day) throws SQLException {
        if (sorterId != null) {
            return 1;
        }
        if (teamId != null) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT COUNT(*) FROM sorting_team_members m JOIN users u ON u.id = m.sorter_id
                    WHERE m.team_id = ? AND u.active = 1
                    """)) {
                ps.setLong(1, teamId);
                try (ResultSet rs = ps.executeQuery()) {
                    return Math.max(1, rs.next() ? rs.getInt(1) : 1);
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM rota_shifts s JOIN users u ON u.id = s.user_id
                JOIN roles r ON r.id = u.role_id
                WHERE s.work_day = ? AND s.shift_code <> 'OFF' AND u.active = 1 AND r.code = 'SORTER'
                """)) {
            ps.setString(1, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                int rostered = rs.next() ? rs.getInt(1) : 0;
                if (rostered > 0) {
                    return rostered;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM users u JOIN roles r ON r.id = u.role_id
                WHERE u.active = 1 AND r.code = 'SORTER'
                """);
             ResultSet rs = ps.executeQuery()) {
            return Math.max(1, rs.next() ? rs.getInt(1) : 1);
        }
    }

    /**
     * Whether a stoppage that already covers these people is still running, so a second one is not opened
     * over the top of it and the same minutes counted twice. "Covers" is the same word here as in a report:
     * a stoppage of the whole line covers every team on it, and a team's stoppage covers its sorters.
     */
    public boolean hasRunning(Connection conn, Long teamId, Long sorterId) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT 1 FROM downtime_events e WHERE e.ended_at IS NULL");
        List<Object> params = new ArrayList<>();
        appendCovering(sql, teamId, sorterId, params);
        sql.append(" LIMIT 1");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * A stoppage counts for a team when it was that team's, and also when it was nobody's in particular —
     * a power cut stops every team on the floor, and a team report that left it out would be answering a
     * different question from the one that was asked.
     */
    private static String where(Scope scope, List<Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (scope.from() != null) {
            sql.append(" AND e.work_day >= ?");
            params.add(scope.from().toString());
        }
        if (scope.to() != null) {
            sql.append(" AND e.work_day <= ?");
            params.add(scope.to().toString());
        }
        appendCovering(sql, scope.teamId(), scope.sorterId(), params);
        return sql.toString();
    }

    /**
     * Narrows to the stoppages that covered these people. A named sorter is covered by their own stoppage,
     * by their team's, and by one of the whole line; a team is covered by its own and by the whole line's.
     * One wording, used both for reporting and for refusing a second stoppage over a running one, so the two
     * can never drift into disagreeing about who was standing still.
     */
    private static void appendCovering(StringBuilder sql, Long teamId, Long sorterId, List<Object> params) {
        if (sorterId != null) {
            sql.append(" AND (e.sorter_id = ? OR (e.sorter_id IS NULL AND (e.team_id IS NULL"
                    + " OR EXISTS (SELECT 1 FROM sorting_team_members m"
                    + " WHERE m.team_id = e.team_id AND m.sorter_id = ?))))");
            params.add(sorterId);
            params.add(sorterId);
            return;
        }
        if (teamId != null) {
            sql.append(" AND (e.team_id = ? OR e.team_id IS NULL)");
            params.add(teamId);
        }
    }

    private long scalar(String sql, List<Object> params) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to total downtime minutes", ex);
        }
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static DowntimeReason mapReason(ResultSet rs) throws SQLException {
        long createdBy = rs.getLong("created_by");
        boolean noCreator = rs.wasNull();
        return new DowntimeReason(
                rs.getLong("id"),
                rs.getString("code"),
                LossGroup.valueOf(rs.getString("loss_group")),
                rs.getString("name_en"),
                rs.getString("name_hu"),
                rs.getInt("attributable") == 1,
                rs.getInt("active") == 1,
                noCreator ? null : createdBy,
                DaoDateUtil.parse(rs.getString("created_at")),
                DaoDateUtil.parse(rs.getString("retired_at")));
    }

    private static DowntimeEvent mapEvent(ResultSet rs) throws SQLException {
        long teamId = rs.getLong("team_id");
        Long team = rs.wasNull() ? null : teamId;
        long sorterId = rs.getLong("sorter_id");
        Long sorter = rs.wasNull() ? null : sorterId;
        long stationId = rs.getLong("station_id");
        Long station = rs.wasNull() ? null : stationId;
        int minutes = rs.getInt("minutes");
        Integer length = rs.wasNull() ? null : minutes;
        long reasonId = rs.getLong("reason_id");
        Long reason = rs.wasNull() ? null : reasonId;
        int attributable = rs.getInt("attributable");
        Boolean crewsOwn = rs.wasNull() ? null : attributable == 1;
        long closedBy = rs.getLong("closed_by");
        Long closer = rs.wasNull() ? null : closedBy;
        String group = rs.getString("loss_group");
        return new DowntimeEvent(
                rs.getLong("id"),
                LocalDate.parse(rs.getString("work_day")),
                team,
                rs.getString("team_name"),
                sorter,
                rs.getString("sorter_name"),
                station,
                rs.getString("station_code"),
                DaoDateUtil.parse(rs.getString("started_at")),
                DaoDateUtil.parse(rs.getString("ended_at")),
                length,
                rs.getInt("people_affected"),
                reason,
                rs.getString("reason_code"),
                group == null ? null : LossGroup.valueOf(group),
                rs.getString("name_en"),
                rs.getString("name_hu"),
                crewsOwn,
                rs.getString("note"),
                rs.getLong("opened_by"),
                rs.getString("opened_by_name"),
                DaoDateUtil.parse(rs.getString("opened_at")),
                closer,
                DaoDateUtil.parse(rs.getString("closed_at")));
    }
}
