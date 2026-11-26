package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.YieldBaseline;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The aggregates behind grade-yield reporting, and the stored baselines it is measured against.
 *
 * <p>Weights are summed as integer grams rather than as decimals: grams are what the sorting tables hold, the
 * sums are exact in SQL, and a share worked out from integers adds up without anybody having to argue about
 * the last digit.
 */
public final class GradeYieldDao {

    /** One grade-and-product combination within a scope, with the weight sorted into it. */
    public record OutputRow(long qualityGradeId, long garmentTypeId, long grams) { }

    /** How a yield report is narrowed. Every field is optional; the nulls simply do not filter. */
    public record Scope(
            Long purchaseLotId,
            Long rawLotId,
            Long teamId,
            Long sorterId,
            LocalDate from,
            LocalDate to) {

        public static Scope all() {
            return new Scope(null, null, null, null, null, null);
        }

        public Scope withPeriod(LocalDate from, LocalDate to) {
            return new Scope(purchaseLotId, rawLotId, teamId, sorterId, from, to);
        }
    }

    private final DatabaseManager db;

    public GradeYieldDao(DatabaseManager db) {
        this.db = db;
    }

    /**
     * The input weight that was actually worked through in this scope.
     *
     * <p>Only finished requests count. A request still on the bench has bags reported against it but no
     * settled input figure, and counting it would make the line look as though it produced output from
     * nothing.
     */
    public long processedGrams(Scope scope) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COALESCE(SUM(r.processed_grams), 0) FROM sorting_requests r"
                + joins() + where(scope, params);
        return scalar(sql, params);
    }

    /** The graded output of this scope, by grade and product. */
    public List<OutputRow> output(Scope scope) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT b.quality_grade_id, b.garment_type_id, COALESCE(SUM(b.weight_grams), 0) AS grams"
                + " FROM sorting_bags b JOIN sorting_requests r ON r.id = b.request_id"
                + joins() + where(scope, params)
                + " GROUP BY b.quality_grade_id, b.garment_type_id";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<OutputRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new OutputRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
                }
                return rows;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read graded output", ex);
        }
    }

    /** The raw lot joins are only needed when the scope reaches back to where the material came from. */
    private static String joins() {
        return " LEFT JOIN sorting_raw_lots rl ON rl.id = r.lot_id";
    }

    private static String where(Scope scope, List<Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE r.processed_grams IS NOT NULL");
        if (scope.purchaseLotId() != null) {
            sql.append(" AND rl.purchase_lot_id = ?");
            params.add(scope.purchaseLotId());
        }
        if (scope.rawLotId() != null) {
            sql.append(" AND r.lot_id = ?");
            params.add(scope.rawLotId());
        }
        if (scope.teamId() != null) {
            sql.append(" AND r.team_id = ?");
            params.add(scope.teamId());
        }
        if (scope.sorterId() != null) {
            sql.append(" AND r.sorter_id = ?");
            params.add(scope.sorterId());
        }
        if (scope.from() != null) {
            sql.append(" AND r.work_day >= ?");
            params.add(scope.from().toString());
        }
        if (scope.to() != null) {
            sql.append(" AND r.work_day <= ?");
            params.add(scope.to().toString());
        }
        return sql.toString();
    }

    // ---------------------------------------------------------------- baselines

    public long insertBaseline(Connection conn, String name, LocalDate from, LocalDate to, long processedGrams,
                               Map<Long, Integer> shares, long capturedBy, LocalDateTime at, String notes)
            throws SQLException {
        long id;
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO grade_yield_baselines
                    (name, period_from, period_to, processed_grams, active, captured_by, captured_at, notes)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            ps.setLong(4, processedGrams);
            ps.setLong(5, capturedBy);
            ps.setString(6, DaoDateUtil.format(at));
            ps.setString(7, notes);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated key returned for grade_yield_baselines insert");
                }
                id = keys.getLong(1);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO grade_yield_baseline_shares (baseline_id, quality_grade_id, share_basis_points)
                VALUES (?, ?, ?)
                """)) {
            for (Map.Entry<Long, Integer> share : shares.entrySet()) {
                ps.setLong(1, id);
                if (share.getKey() == null) {
                    ps.setNull(2, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(2, share.getKey());
                }
                ps.setInt(3, share.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return id;
    }

    /** Makes one baseline the one variance is measured against, and only one. */
    public void activate(Connection conn, long baselineId) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE grade_yield_baselines SET active = 0");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE grade_yield_baselines SET active = 1 WHERE id = ?")) {
            ps.setLong(1, baselineId);
            ps.executeUpdate();
        }
    }

    public Optional<YieldBaseline> findActive() {
        List<YieldBaseline> found = baselines(" WHERE b.active = 1", List.of());
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public List<YieldBaseline> findAll() {
        return baselines("", List.of());
    }

    private List<YieldBaseline> baselines(String filter, List<Object> params) {
        String sql = """
                SELECT b.id, b.name, b.period_from, b.period_to, b.processed_grams, b.active,
                       b.captured_by, u.full_name, b.captured_at, b.notes
                FROM grade_yield_baselines b
                LEFT JOIN users u ON u.id = b.captured_by
                """ + filter + " ORDER BY b.id DESC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            List<YieldBaseline> baselines = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    baselines.add(new YieldBaseline(
                            rs.getLong(1), rs.getString(2),
                            LocalDate.parse(rs.getString(3)), LocalDate.parse(rs.getString(4)),
                            rs.getLong(5), rs.getInt(6) == 1, rs.getLong(7), rs.getString(8),
                            DaoDateUtil.parse(rs.getString(9)), rs.getString(10), Map.of()));
                }
            }
            List<YieldBaseline> complete = new ArrayList<>();
            for (YieldBaseline baseline : baselines) {
                complete.add(new YieldBaseline(baseline.id(), baseline.name(), baseline.periodFrom(),
                        baseline.periodTo(), baseline.processedGrams(), baseline.active(),
                        baseline.capturedBy(), baseline.capturedByName(), baseline.capturedAt(),
                        baseline.notes(), shares(conn, baseline.id())));
            }
            return complete;
        } catch (SQLException ex) {
            throw new DaoException("Failed to read yield baselines", ex);
        }
    }

    private static Map<Long, Integer> shares(Connection conn, long baselineId) throws SQLException {
        Map<Long, Integer> shares = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT quality_grade_id, share_basis_points FROM grade_yield_baseline_shares"
                        + " WHERE baseline_id = ?")) {
            ps.setLong(1, baselineId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long gradeId = rs.getLong(1);
                    // Asked straight after the read it belongs to: the residue is held against a null grade.
                    boolean residue = rs.wasNull();
                    shares.put(residue ? null : gradeId, rs.getInt(2));
                }
            }
        }
        return shares;
    }

    private long scalar(String sql, List<Object> params) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to total processed input", ex);
        }
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }
}
