package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.HandoverIssue;
import hu.due.rewear.model.RotaShift.ShiftCode;
import hu.due.rewear.model.ShiftHandover;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads and writes shift handovers, their open issues, and the figures frozen into them at close. */
public final class ShiftHandoverDao {

    private static final String SELECT = """
            SELECT h.id, h.work_day, h.shift_code, h.outgoing_by, o.full_name AS outgoing_name,
                   h.equipment_status, h.staffing_notes, h.safety_items, h.must_know,
                   h.processed_grams, h.target_grams, h.output_grams, h.downtime_minutes,
                   h.primary_cause_reason_id, r.code AS cause_code, r.name_en AS cause_en,
                   r.name_hu AS cause_hu, h.submitted_at, h.acknowledged_by,
                   a.full_name AS acknowledged_name, h.acknowledged_at
            FROM shift_handovers h
            JOIN users o ON o.id = h.outgoing_by
            LEFT JOIN users a ON a.id = h.acknowledged_by
            LEFT JOIN downtime_reasons r ON r.id = h.primary_cause_reason_id
            """;

    private static final String ISSUE_SELECT = """
            SELECT i.id, i.handover_id, h.work_day, i.summary, i.owner_id, u.full_name AS owner_name,
                   i.due_on, i.state, i.created_at, i.escalated_at, i.escalated_to,
                   e.full_name AS escalated_name, i.resolved_at, i.resolved_by,
                   v.full_name AS resolved_name, i.resolution
            FROM shift_handover_issues i
            JOIN shift_handovers h ON h.id = i.handover_id
            JOIN users u ON u.id = i.owner_id
            LEFT JOIN users e ON e.id = i.escalated_to
            LEFT JOIN users v ON v.id = i.resolved_by
            """;

    /**
     * The measured field of a handover, over one shift's own hours.
     *
     * @param primaryCauseReasonId the single reason that cost the most minutes, which is what the template
     *                             asks for — not a list, because a handover read in ten minutes cannot carry
     *                             one
     */
    public record ShiftFigures(long processedGrams, long targetGrams, long outputGrams, int downtimeMinutes,
                               Long primaryCauseReasonId) {

        public static ShiftFigures none() {
            return new ShiftFigures(0, 0, 0, 0, null);
        }
    }

    private final DatabaseManager db;

    public ShiftHandoverDao(DatabaseManager db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- the handover

    public long insert(Connection conn, LocalDate workDay, ShiftCode shift, long outgoingBy,
                       String equipment, String staffing, String safety, String mustKnow,
                       ShiftFigures figures, LocalDateTime submittedAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO shift_handovers (work_day, shift_code, outgoing_by, equipment_status,
                    staffing_notes, safety_items, must_know, processed_grams, target_grams, output_grams,
                    downtime_minutes, primary_cause_reason_id, submitted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, workDay.toString());
            ps.setString(2, shift.name());
            ps.setLong(3, outgoingBy);
            ps.setString(4, equipment);
            ps.setString(5, staffing);
            ps.setString(6, safety);
            ps.setString(7, mustKnow);
            ps.setLong(8, figures.processedGrams());
            ps.setLong(9, figures.targetGrams());
            ps.setLong(10, figures.outputGrams());
            ps.setInt(11, figures.downtimeMinutes());
            if (figures.primaryCauseReasonId() == null) {
                ps.setNull(12, Types.INTEGER);
            } else {
                ps.setLong(12, figures.primaryCauseReasonId());
            }
            ps.setString(13, DaoDateUtil.format(submittedAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for shift_handovers insert");
            }
        }
    }

    public int acknowledge(Connection conn, long id, long userId, LocalDateTime at) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE shift_handovers SET acknowledged_by = ?, acknowledged_at = ?
                WHERE id = ? AND acknowledged_at IS NULL
                """)) {
            ps.setLong(1, userId);
            ps.setString(2, DaoDateUtil.format(at));
            ps.setLong(3, id);
            return ps.executeUpdate();
        }
    }

    public Optional<ShiftHandover> findById(long id) {
        return one(SELECT + " WHERE h.id = ?", id);
    }

    /**
     * The last shift that was handed over, which is the screen an incoming supervisor should open on.
     *
     * <p>"Last" is the shift that <em>ended</em> last, not the sheet that was typed last: somebody catching
     * up on yesterday's paperwork this afternoon has not thereby become the shift you are taking over from.
     * Within a day that order is morning, afternoon, night, because the night shift ends the next morning.
     */
    public Optional<ShiftHandover> findLatest() {
        return one(SELECT + " ORDER BY h.work_day DESC, " + SHIFT_ORDER + " DESC, h.submitted_at DESC"
                + " LIMIT 1");
    }

    private static final String SHIFT_ORDER = """
            CASE h.shift_code WHEN 'NIGHT' THEN 3 WHEN 'AFTERNOON' THEN 2 ELSE 1 END""";

    public Optional<ShiftHandover> find(LocalDate workDay, ShiftCode shift) {
        return one(SELECT + " WHERE h.work_day = ? AND h.shift_code = ?", workDay.toString(), shift.name());
    }

    public List<ShiftHandover> findRecent(int limit) {
        return many(SELECT + " ORDER BY h.work_day DESC, " + SHIFT_ORDER + " DESC, h.submitted_at DESC"
                + " LIMIT ?", limit);
    }

    // ---------------------------------------------------------------- issues

    public long insertIssue(Connection conn, long handoverId, String summary, long ownerId, LocalDate dueOn,
                            LocalDateTime createdAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO shift_handover_issues (handover_id, summary, owner_id, due_on, state, created_at)
                VALUES (?, ?, ?, ?, 'OPEN', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, handoverId);
            ps.setString(2, summary);
            ps.setLong(3, ownerId);
            ps.setString(4, dueOn.toString());
            ps.setString(5, DaoDateUtil.format(createdAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for shift_handover_issues insert");
            }
        }
    }

    public int resolveIssue(Connection conn, long issueId, long userId, String resolution,
                           LocalDateTime at) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE shift_handover_issues
                SET state = 'RESOLVED', resolved_by = ?, resolved_at = ?, resolution = ?
                WHERE id = ? AND state = 'OPEN'
                """)) {
            ps.setLong(1, userId);
            ps.setString(2, DaoDateUtil.format(at));
            ps.setString(3, resolution);
            ps.setLong(4, issueId);
            return ps.executeUpdate();
        }
    }

    public int markEscalated(Connection conn, long issueId, Long toUserId, LocalDateTime at)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE shift_handover_issues SET escalated_at = ?, escalated_to = ?
                WHERE id = ? AND state = 'OPEN' AND escalated_at IS NULL
                """)) {
            ps.setString(1, DaoDateUtil.format(at));
            if (toUserId == null) {
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setLong(2, toUserId);
            }
            ps.setLong(3, issueId);
            return ps.executeUpdate();
        }
    }

    public List<HandoverIssue> findIssues(long handoverId) {
        return issues(ISSUE_SELECT + " WHERE i.handover_id = ? ORDER BY i.state, i.due_on, i.id",
                handoverId);
    }

    /** Every issue still open, oldest first: the ones at the top are the ones that have waited longest. */
    public List<HandoverIssue> findOpenIssues() {
        return issues(ISSUE_SELECT + " WHERE i.state = 'OPEN' ORDER BY i.created_at");
    }

    public Optional<HandoverIssue> findIssue(long issueId) {
        List<HandoverIssue> rows = issues(ISSUE_SELECT + " WHERE i.id = ?", issueId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ---------------------------------------------------------------- the frozen figures

    /**
     * What the shift produced and what stopped it, between two moments.
     *
     * <p>Sorting timestamps are instants and stoppage timestamps are local, so the window is handed in as
     * local time and converted for the half of the query that needs it. Comparing the two conventions
     * directly is how an hour goes missing twice a year.
     */
    public ShiftFigures figuresBetween(LocalDateTime from, LocalDateTime to) {
        try (Connection conn = db.getConnection()) {
            return figuresBetween(conn, from, to);
        } catch (SQLException ex) {
            throw new DaoException("Failed to read the shift's figures", ex);
        }
    }

    /** The same, on a caller's connection, for code that is already inside a transaction. */
    public ShiftFigures figuresBetween(Connection conn, LocalDateTime from, LocalDateTime to) {
        // One window for both halves of the figures. It used to be two, because the sorting half was stored
        // as an instant and the stoppage half as a local date-time, and this method had to convert the same
        // window twice in two different ways to ask one question.
        String windowFrom = DaoDateUtil.format(from);
        String windowTo = DaoDateUtil.format(to);
        long processed = 0;
        long target = 0;
        long output = 0;
        try {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT COALESCE(SUM(r.processed_grams), 0), COALESCE(SUM(r.target_grams), 0),
                           COALESCE((SELECT SUM(b.weight_grams) FROM sorting_bags b
                                     JOIN sorting_requests q ON q.id = b.request_id
                                     WHERE q.finished_at IS NOT NULL
                                       AND q.finished_at >= ? AND q.finished_at < ?), 0)
                    FROM sorting_requests r
                    WHERE r.finished_at IS NOT NULL
                      AND r.finished_at >= ? AND r.finished_at < ?
                    """)) {
                ps.setString(1, windowFrom);
                ps.setString(2, windowTo);
                ps.setString(3, windowFrom);
                ps.setString(4, windowTo);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        processed = rs.getLong(1);
                        target = rs.getLong(2);
                        output = rs.getLong(3);
                    }
                }
            }
            int downtime = 0;
            Long cause = null;
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT reason_id, SUM(minutes) AS total FROM downtime_events
                    WHERE minutes IS NOT NULL AND started_at >= ? AND started_at < ?
                    GROUP BY reason_id ORDER BY total DESC
                    """)) {
                ps.setString(1, windowFrom);
                ps.setString(2, windowTo);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long reason = rs.getLong("reason_id");
                        boolean noReason = rs.wasNull();
                        int minutes = rs.getInt("total");
                        downtime += minutes;
                        // The first row is the biggest, so the primary cause is whatever cost most.
                        if (cause == null && !noReason) {
                            cause = reason;
                        }
                    }
                }
            }
            return new ShiftFigures(processed, target, output, downtime, cause);
        } catch (SQLException ex) {
            throw new DaoException("Failed to read the shift's figures", ex);
        }
    }

    // ---------------------------------------------------------------- plumbing

    private Optional<ShiftHandover> one(String sql, Object... params) {
        List<ShiftHandover> rows = many(sql, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private List<ShiftHandover> many(String sql, Object... params) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            Map<Long, ShiftHandover> found = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ShiftHandover handover = map(rs);
                    found.put(handover.id(), handover);
                }
            }
            List<ShiftHandover> withIssues = new ArrayList<>();
            for (ShiftHandover handover : found.values()) {
                withIssues.add(new ShiftHandover(handover.id(), handover.workDay(), handover.shift(),
                        handover.outgoingBy(), handover.outgoingByName(), handover.equipmentStatus(),
                        handover.staffingNotes(), handover.safetyItems(), handover.mustKnow(),
                        handover.processedGrams(), handover.targetGrams(), handover.outputGrams(),
                        handover.downtimeMinutes(), handover.primaryCauseReasonId(),
                        handover.primaryCauseCode(), handover.primaryCauseEn(), handover.primaryCauseHu(),
                        handover.submittedAt(), handover.acknowledgedBy(), handover.acknowledgedByName(),
                        handover.acknowledgedAt(), findIssues(handover.id())));
            }
            return withIssues;
        } catch (SQLException ex) {
            throw new DaoException("Failed to read shift handovers", ex);
        }
    }

    private List<HandoverIssue> issues(String sql, Object... params) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<HandoverIssue> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapIssue(rs));
                }
                return rows;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read handover issues", ex);
        }
    }

    private static ShiftHandover map(ResultSet rs) throws SQLException {
        long cause = rs.getLong("primary_cause_reason_id");
        Long causeId = rs.wasNull() ? null : cause;
        long acknowledgedBy = rs.getLong("acknowledged_by");
        Long acknowledger = rs.wasNull() ? null : acknowledgedBy;
        return new ShiftHandover(
                rs.getLong("id"),
                LocalDate.parse(rs.getString("work_day")),
                ShiftCode.valueOf(rs.getString("shift_code")),
                rs.getLong("outgoing_by"),
                rs.getString("outgoing_name"),
                rs.getString("equipment_status"),
                rs.getString("staffing_notes"),
                rs.getString("safety_items"),
                rs.getString("must_know"),
                rs.getLong("processed_grams"),
                rs.getLong("target_grams"),
                rs.getLong("output_grams"),
                rs.getInt("downtime_minutes"),
                causeId,
                rs.getString("cause_code"),
                rs.getString("cause_en"),
                rs.getString("cause_hu"),
                DaoDateUtil.parse(rs.getString("submitted_at")),
                acknowledger,
                rs.getString("acknowledged_name"),
                DaoDateUtil.parse(rs.getString("acknowledged_at")),
                List.of());
    }

    private static HandoverIssue mapIssue(ResultSet rs) throws SQLException {
        long escalatedTo = rs.getLong("escalated_to");
        Long escalated = rs.wasNull() ? null : escalatedTo;
        long resolvedBy = rs.getLong("resolved_by");
        Long resolver = rs.wasNull() ? null : resolvedBy;
        return new HandoverIssue(
                rs.getLong("id"),
                rs.getLong("handover_id"),
                LocalDate.parse(rs.getString("work_day")),
                rs.getString("summary"),
                rs.getLong("owner_id"),
                rs.getString("owner_name"),
                LocalDate.parse(rs.getString("due_on")),
                HandoverIssue.State.valueOf(rs.getString("state")),
                DaoDateUtil.parse(rs.getString("created_at")),
                DaoDateUtil.parse(rs.getString("escalated_at")),
                escalated,
                rs.getString("escalated_name"),
                DaoDateUtil.parse(rs.getString("resolved_at")),
                resolver,
                rs.getString("resolved_name"),
                rs.getString("resolution"));
    }
}
