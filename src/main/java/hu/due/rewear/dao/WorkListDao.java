package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.RoleCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The narrow queries a role's work list is built from.
 *
 * <p>Every method takes the person and the day it is asked about rather than reading them from the session,
 * because a work list is built on somebody's behalf and the caller is the one who knows whose it is. The
 * queries are deliberately small: the sidebar badge rebuilds this on a timer, so a work list may not drag a
 * whole screen's worth of data behind it.
 *
 * <p>Two things about the sorting tables matter here. A request's {@code work_day} is written when the run
 * is finished, so an open call for material has none — anything still waiting is therefore looked up without
 * a date, which is also the honest thing to do, since a call left unanswered from yesterday is exactly what
 * needs surfacing. And sorting timestamps are instants, not local date-times, so the age of a call is worked
 * out from {@link java.time.Instant} rather than by subtracting text.
 */
public final class WorkListDao {

    /** One worker's own day: what they got through, and what is still in front of them. */
    public record OwnShift(
            long processedGrams,
            long targetGrams,
            int awaitingDelivery,
            String oldestCallAt,
            int onBench,
            int unpackedBags) {

        public boolean nothingToShow() {
            return processedGrams == 0 && targetGrams == 0 && awaitingDelivery == 0
                    && onBench == 0 && unpackedBags == 0;
        }
    }

    /** A sorter has called for material and nobody has brought it yet. */
    public record SupplyCall(
            long requestId,
            String sorter,
            String station,
            String lotReference,
            String material,
            Long requestedGrams,
            String requestedAt) { }

    /** Material that arrived and has not been put anywhere, so no sorter can call for it. */
    public record UnstoredLot(long id, String reference, String material, long receivedGrams) { }

    /** Bags that have stopped halfway: reported and not packed, or packed and not registered into stock. */
    public record BagBacklog(int unpacked, int unregistered) {

        public boolean isEmpty() {
            return unpacked == 0 && unregistered == 0;
        }
    }

    /** A payroll day that cannot be computed, because the department and role have no rate yet. */
    public record MissingRate(long workerId, String worker, long departmentId, String department, RoleCode role) { }

    /** Somebody sorted on the day and has no payroll shift row for it, so the draft is short of them. */
    public record UnpaidDay(long workerId, String worker, long processedGrams) { }

    private final DatabaseManager db;

    public WorkListDao(DatabaseManager db) {
        this.db = db;
    }

    // ------------------------------------------------------------- the floor

    /**
     * One sorter's or helper's own day.
     *
     * @param role whose work is being counted: a sorter owns a request, a helper is named on one. Any other
     *             role has no own shift and gets zeros.
     */
    public OwnShift ownShift(long userId, RoleCode role, LocalDate day) {
        String mine = switch (role) {
            case SORTER -> "r.sorter_id = ?";
            case SORTING_HELPER -> "EXISTS (SELECT 1 FROM sorting_request_helpers h"
                    + " WHERE h.request_id = r.id AND h.helper_id = ?)";
            default -> null;
        };
        if (mine == null) {
            return new OwnShift(0, 0, 0, null, 0, 0);
        }
        // A helper has no target of their own: they work across teams, and the target belongs to the sorter
        // whose bench they are on.
        String target = role == RoleCode.SORTER
                ? """
                COALESCE((SELECT MAX(r.target_grams) FROM sorting_requests r
                          WHERE r.work_day = ? AND %s),
                         (SELECT MAX(t.target_grams) FROM sorting_team_members m
                          JOIN sorting_teams t ON t.id = m.team_id WHERE m.sorter_id = ?), 0)""".formatted(mine)
                : "0";
        String sql = """
                SELECT
                  (SELECT COALESCE(SUM(r.processed_grams), 0) FROM sorting_requests r
                   WHERE r.work_day = ? AND %1$s) AS processed,
                  %2$s AS target,
                  (SELECT COUNT(*) FROM sorting_requests r
                   WHERE r.delivered_at IS NULL AND r.cancelled_at IS NULL AND %1$s) AS awaiting,
                  (SELECT MIN(r.requested_at) FROM sorting_requests r
                   WHERE r.delivered_at IS NULL AND r.cancelled_at IS NULL AND %1$s) AS oldest_call,
                  (SELECT COUNT(*) FROM sorting_requests r
                   WHERE r.delivered_at IS NOT NULL AND r.finished_at IS NULL AND r.cancelled_at IS NULL
                     AND %1$s) AS on_bench,
                  (SELECT COUNT(*) FROM sorting_bags b JOIN sorting_requests r ON r.id = b.request_id
                   WHERE b.packed_at IS NULL AND %1$s) AS unpacked
                """.formatted(mine, target);

        List<Object> params = new ArrayList<>();
        params.add(day.toString());
        params.add(userId);
        if (role == RoleCode.SORTER) {
            params.add(day.toString());
            params.add(userId);
            params.add(userId);
        }
        params.add(userId);
        params.add(userId);
        params.add(userId);
        params.add(userId);

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new OwnShift(0, 0, 0, null, 0, 0);
                }
                return new OwnShift(rs.getLong("processed"), rs.getLong("target"), rs.getInt("awaiting"),
                        rs.getString("oldest_call"), rs.getInt("on_bench"), rs.getInt("unpacked"));
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read the day's own work for user " + userId, ex);
        }
    }

    /** Every call for material nobody has answered yet, oldest first. */
    public List<SupplyCall> openSupplyCalls() {
        String sql = """
                SELECT r.id, u.full_name AS sorter, s.code AS station, l.reference, l.material,
                       r.requested_grams, r.requested_at
                FROM sorting_requests r
                JOIN users u ON u.id = r.sorter_id
                LEFT JOIN warehouse_locations s ON s.id = r.station_id
                LEFT JOIN sorting_raw_lots l ON l.id = r.requested_lot_id
                WHERE r.delivered_at IS NULL AND r.cancelled_at IS NULL
                ORDER BY r.requested_at, r.id
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<SupplyCall> calls = new ArrayList<>();
            while (rs.next()) {
                Long grams = rs.getLong("requested_grams");
                if (rs.wasNull()) {
                    grams = null;
                }
                calls.add(new SupplyCall(rs.getLong("id"), rs.getString("sorter"), rs.getString("station"),
                        rs.getString("reference"), rs.getString("material"), grams, rs.getString("requested_at")));
            }
            return calls;
        } catch (SQLException ex) {
            throw new DaoException("Failed to read open supply calls", ex);
        }
    }

    /** Collections that have arrived and have not been stored, oldest first. */
    public List<UnstoredLot> unstoredLots() {
        String sql = """
                SELECT id, reference, material, received_grams FROM sorting_raw_lots
                WHERE location_id IS NULL ORDER BY received_at, id
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<UnstoredLot> lots = new ArrayList<>();
            while (rs.next()) {
                lots.add(new UnstoredLot(rs.getLong("id"), rs.getString("reference"),
                        rs.getString("material"), rs.getLong("received_grams")));
            }
            return lots;
        } catch (SQLException ex) {
            throw new DaoException("Failed to read unstored raw lots", ex);
        }
    }

    /**
     * Bags halfway through the line, whatever day they were reported on. A bag reported last week and never
     * packed is still somebody's work, so this is counted without a date.
     */
    public BagBacklog bagBacklog() {
        String sql = """
                SELECT (SELECT COUNT(*) FROM sorting_bags WHERE packed_at IS NULL) AS unpacked,
                       (SELECT COUNT(*) FROM sorting_bags
                        WHERE packed_at IS NOT NULL AND registered_at IS NULL) AS unregistered
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new BagBacklog(rs.getInt("unpacked"), rs.getInt("unregistered"))
                    : new BagBacklog(0, 0);
        } catch (SQLException ex) {
            throw new DaoException("Failed to read the bag backlog", ex);
        }
    }

    // ----------------------------------------------------------- the payroll

    /** Saved payroll days whose department and role have no rate effective by then. */
    public List<MissingRate> daysWithoutRate(LocalDate day) {
        String sql = """
                SELECT s.worker_id, u.full_name, s.department_id, d.name AS department, s.role_code
                FROM payroll_shifts s
                JOIN users u ON u.id = s.worker_id
                JOIN payroll_departments d ON d.id = s.department_id
                WHERE s.work_day = ? AND NOT EXISTS (
                    SELECT 1 FROM payroll_department_rates rate
                    WHERE rate.department_id = s.department_id AND rate.role_code = s.role_code
                      AND rate.effective_from <= s.work_day)
                ORDER BY u.full_name, d.name
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<MissingRate> gaps = new ArrayList<>();
                while (rs.next()) {
                    gaps.add(new MissingRate(rs.getLong("worker_id"), rs.getString("full_name"),
                            rs.getLong("department_id"), rs.getString("department"),
                            RoleCode.valueOf(rs.getString("role_code"))));
                }
                return gaps;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read payroll days without a rate", ex);
        }
    }

    /** People who sorted on the day and have no payroll shift row for it. */
    public List<UnpaidDay> sortedWithoutPayrollDay(LocalDate day) {
        String sql = """
                SELECT r.sorter_id, u.full_name, COALESCE(SUM(r.processed_grams), 0) AS processed
                FROM sorting_requests r
                JOIN users u ON u.id = r.sorter_id
                WHERE r.work_day = ? AND r.processed_grams IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM payroll_shifts s
                    WHERE s.worker_id = r.sorter_id AND s.work_day = r.work_day)
                GROUP BY r.sorter_id, u.full_name
                ORDER BY u.full_name
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<UnpaidDay> days = new ArrayList<>();
                while (rs.next()) {
                    days.add(new UnpaidDay(rs.getLong("sorter_id"), rs.getString("full_name"),
                            rs.getLong("processed")));
                }
                return days;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read sorted days missing from payroll", ex);
        }
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }
}
