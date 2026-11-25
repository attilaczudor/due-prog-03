package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.NavOperation;
import hu.due.rewear.model.NavReport;
import hu.due.rewear.model.NavReportEvent;
import hu.due.rewear.model.NavReportState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads and writes the NAV reporting queue and its history. */
public final class NavReportDao {

    private static final String SELECT = """
            SELECT r.id, r.invoice_id, i.invoice_number, i.partner_name, r.operation, r.state, r.request_id,
                   r.transaction_id, r.invoice_index, r.response_code, r.message, r.attempt_count,
                   r.next_attempt_at, r.submitted_at, r.completed_at, r.created_at, r.updated_at
            FROM nav_invoice_reports r
            JOIN invoices i ON i.id = r.invoice_id
            """;

    private final DatabaseManager db;

    public NavReportDao(DatabaseManager db) {
        this.db = db;
    }

    public long insert(Connection conn, long invoiceId, NavOperation operation, LocalDateTime now)
            throws SQLException {
        String sql = """
                INSERT INTO nav_invoice_reports
                    (invoice_id, operation, state, attempt_count, created_at, updated_at)
                VALUES (?, ?, 'PENDING', 0, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, invoiceId);
            ps.setString(2, operation.name());
            ps.setString(3, DaoDateUtil.format(now));
            ps.setString(4, DaoDateUtil.format(now));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for nav_invoice_reports insert");
            }
        }
    }

    /** True when this invoice already has a report for the operation that has not failed. */
    public boolean hasLiveReport(Connection conn, long invoiceId, NavOperation operation) throws SQLException {
        String sql = """
                SELECT COUNT(*) FROM nav_invoice_reports
                WHERE invoice_id = ? AND operation = ? AND state NOT IN ('ERROR', 'ABORTED')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, invoiceId);
            ps.setString(2, operation.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    public List<NavReport> findAll() {
        return query(SELECT + " ORDER BY r.id DESC", ps -> { });
    }

    public List<NavReport> findByState(NavReportState state) {
        return query(SELECT + " WHERE r.state = ? ORDER BY r.id", ps -> ps.setString(1, state.name()));
    }

    /** Pending rows whose backoff has elapsed, oldest first so a queue drains in the order it filled. */
    public List<NavReport> findDue(LocalDateTime now) {
        return query(SELECT + " WHERE r.state = 'PENDING' AND (r.next_attempt_at IS NULL OR r.next_attempt_at <= ?)"
                + " ORDER BY r.id", ps -> ps.setString(1, DaoDateUtil.format(now)));
    }

    public Optional<NavReport> findById(long id) {
        List<NavReport> found = query(SELECT + " WHERE r.id = ?", ps -> ps.setLong(1, id));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public Optional<NavReport> findLatestForInvoice(long invoiceId) {
        List<NavReport> found = query(SELECT + " WHERE r.invoice_id = ? ORDER BY r.id DESC LIMIT 1",
                ps -> ps.setLong(1, invoiceId));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public void markSubmitted(Connection conn, long reportId, String requestId, String transactionId,
                              int index, LocalDateTime now) throws SQLException {
        String sql = """
                UPDATE nav_invoice_reports
                SET state = 'SUBMITTED', request_id = ?, transaction_id = ?, invoice_index = ?,
                    submitted_at = ?, updated_at = ?, attempt_count = attempt_count + 1,
                    next_attempt_at = NULL, response_code = NULL, message = NULL
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestId);
            ps.setString(2, transactionId);
            ps.setInt(3, index);
            ps.setString(4, DaoDateUtil.format(now));
            ps.setString(5, DaoDateUtil.format(now));
            ps.setLong(6, reportId);
            ps.executeUpdate();
        }
    }

    /** Records an outcome. A resting state also stamps {@code completed_at}. */
    public void markOutcome(Connection conn, long reportId, NavReportState state, String responseCode,
                            String message, LocalDateTime now) throws SQLException {
        String sql = """
                UPDATE nav_invoice_reports
                SET state = ?, response_code = ?, message = ?, updated_at = ?,
                    completed_at = CASE WHEN ? IN ('DONE', 'WARN', 'ERROR', 'ABORTED') THEN ? ELSE completed_at END
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setString(2, responseCode);
            ps.setString(3, message);
            ps.setString(4, DaoDateUtil.format(now));
            ps.setString(5, state.name());
            ps.setString(6, DaoDateUtil.format(now));
            ps.setLong(7, reportId);
            ps.executeUpdate();
        }
    }

    /** Puts a failed attempt back in the queue behind a delay. */
    public void scheduleRetry(Connection conn, long reportId, String message, LocalDateTime nextAttemptAt,
                              LocalDateTime now) throws SQLException {
        String sql = """
                UPDATE nav_invoice_reports
                SET state = 'PENDING', message = ?, attempt_count = attempt_count + 1,
                    next_attempt_at = ?, updated_at = ?
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, message);
            ps.setString(2, DaoDateUtil.format(nextAttemptAt));
            ps.setString(3, DaoDateUtil.format(now));
            ps.setLong(4, reportId);
            ps.executeUpdate();
        }
    }

    public void addEvent(Connection conn, long reportId, NavReportState from, NavReportState to,
                         String detail, Long userId, LocalDateTime now) throws SQLException {
        String sql = """
                INSERT INTO nav_report_events (report_id, from_state, to_state, detail, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, reportId);
            ps.setString(2, from == null ? null : from.name());
            ps.setString(3, to.name());
            ps.setString(4, detail);
            if (userId == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setLong(5, userId);
            }
            ps.setString(6, DaoDateUtil.format(now));
            ps.executeUpdate();
        }
    }

    public List<NavReportEvent> findEvents(long reportId) {
        String sql = """
                SELECT e.id, e.report_id, e.from_state, e.to_state, e.detail, e.created_by, u.full_name, e.created_at
                FROM nav_report_events e
                LEFT JOIN users u ON u.id = e.created_by
                WHERE e.report_id = ?
                ORDER BY e.id
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                List<NavReportEvent> events = new ArrayList<>();
                while (rs.next()) {
                    long createdBy = rs.getLong("created_by");
                    // Asked immediately after the read it belongs to: wasNull() reports on the last column
                    // fetched, so a single read in between would make it answer about the wrong one.
                    boolean systemAction = rs.wasNull();
                    events.add(new NavReportEvent(
                            rs.getLong("id"),
                            rs.getLong("report_id"),
                            rs.getString("from_state") == null ? null
                                    : NavReportState.valueOf(rs.getString("from_state")),
                            NavReportState.valueOf(rs.getString("to_state")),
                            rs.getString("detail"),
                            systemAction ? null : createdBy,
                            rs.getString("full_name"),
                            DaoDateUtil.parse(rs.getString("created_at"))));
                }
                return events;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read the NAV report history", ex);
        }
    }

    /** How many documents already amend this invoice, which is what the next modification index counts from. */
    public int countAmendments(Connection conn, long originalInvoiceId) throws SQLException {
        String sql = """
                SELECT COUNT(*) FROM nav_invoice_reports r
                JOIN invoices i ON i.id = r.invoice_id
                WHERE i.credit_for_invoice_id = ? AND r.operation IN ('MODIFY', 'STORNO')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, originalInvoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<NavReport> query(String sql, Binder binder) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<NavReport> reports = new ArrayList<>();
                while (rs.next()) {
                    reports.add(map(rs));
                }
                return reports;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read the NAV reporting queue", ex);
        }
    }

    private static NavReport map(ResultSet rs) throws SQLException {
        return new NavReport(
                rs.getLong("id"),
                rs.getLong("invoice_id"),
                rs.getString("invoice_number"),
                rs.getString("partner_name"),
                NavOperation.valueOf(rs.getString("operation")),
                NavReportState.valueOf(rs.getString("state")),
                rs.getString("request_id"),
                rs.getString("transaction_id"),
                rs.getInt("invoice_index"),
                rs.getString("response_code"),
                rs.getString("message"),
                rs.getInt("attempt_count"),
                time(rs, "next_attempt_at"),
                time(rs, "submitted_at"),
                time(rs, "completed_at"),
                time(rs, "created_at"),
                time(rs, "updated_at"));
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        return raw == null || raw.isBlank() ? null : DaoDateUtil.parse(raw);
    }
}
