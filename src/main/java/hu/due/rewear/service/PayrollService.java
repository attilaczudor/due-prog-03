package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.RoleCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Draft gross payroll inputs and observed production for a selected warehouse work day. */
public final class PayrollService {
    private static final ZoneId WAREHOUSE_ZONE = ZoneId.of("Europe/Budapest");

    public record Worker(long id, String name, RoleCode role, boolean active, Long monthlyGross, String effectiveFrom) { }
    public record Department(long id, String name, String sortingTeam) { }
    public record SortingTeam(long id, String name) { }
    public record Rate(long departmentId, String department, RoleCode role, String effectiveFrom,
                       long dailyGross, int bonusPoints) { }
    public record Shift(long id, long workerId, String worker, long departmentId, String department, RoleCode role,
                        int shiftPoints, boolean bonusApproved, Long dailyRate, Integer bonusRate) {
        public Long baseGross() {
            return dailyRate == null ? null : portion(dailyRate, shiftPoints);
        }

        public Long bonusGross() {
            return baseGross() == null ? null : bonusApproved ? portion(baseGross(), bonusRate) : 0L;
        }

        public Long draftGross() {
            return baseGross() == null ? null : Math.addExact(baseGross(), bonusGross());
        }
    }
    public record Performance(long workerId, String worker, String department, RoleCode role,
                              long sortedGrams, long packedGrams, long bags, Long targetGrams) { }
    public record Workspace(List<Worker> workers, List<Department> departments, List<SortingTeam> unlinkedTeams,
                            List<Rate> rates, List<Shift> shifts, List<Performance> performance) { }

    private final DatabaseManager db;
    private final SessionContext session;

    public PayrollService(DatabaseManager db, SessionContext session) {
        this.db = db;
        this.session = session;
    }

    public LocalDate today() {
        return LocalDate.now(WAREHOUSE_ZONE);
    }

    public void initializeDepartments() {
        db.runInTransaction(conn -> update(conn, """
                INSERT OR IGNORE INTO payroll_departments(name, sorting_team_id)
                SELECT name,id FROM sorting_teams WHERE id NOT IN (
                    SELECT sorting_team_id FROM payroll_departments WHERE sorting_team_id IS NOT NULL)
                """));
    }

    public Workspace workspace(LocalDate day) {
        actor();
        String date = day.toString();
        return db.executeInTransaction(conn -> new Workspace(
                query(conn, """
                        SELECT u.id,u.full_name,r.code,u.active,b.monthly_gross_huf,b.effective_from
                        FROM users u JOIN roles r ON r.id=u.role_id
                        LEFT JOIN payroll_worker_base b ON b.worker_id=u.id AND b.effective_from=(
                            SELECT MAX(effective_from) FROM payroll_worker_base WHERE worker_id=u.id AND effective_from<=?)
                        ORDER BY u.full_name,u.id
                        """, r -> new Worker(r.getLong("id"), r.getString("full_name"), role(r), r.getBoolean("active"),
                        nullableLong(r,"monthly_gross_huf"), r.getString("effective_from")), date),
                query(conn, """
                        SELECT d.*, t.name AS sorting_team FROM payroll_departments d
                        LEFT JOIN sorting_teams t ON t.id=d.sorting_team_id ORDER BY d.name
                        """, r -> new Department(r.getLong("id"), r.getString("name"), r.getString("sorting_team"))),
                query(conn, """
                        SELECT t.id,t.name FROM sorting_teams t WHERE NOT EXISTS
                        (SELECT 1 FROM payroll_departments d WHERE d.sorting_team_id=t.id) ORDER BY t.name
                        """, r -> new SortingTeam(r.getLong(1), r.getString(2))),
                query(conn, """
                        SELECT rate.*,d.name AS department,rate.role_code AS code
                        FROM payroll_department_rates rate JOIN payroll_departments d ON d.id=rate.department_id
                        WHERE rate.effective_from=(SELECT MAX(effective_from) FROM payroll_department_rates
                            WHERE department_id=rate.department_id AND role_code=rate.role_code AND effective_from<=?)
                        ORDER BY d.name,rate.role_code
                        """, r -> new Rate(r.getLong("department_id"), r.getString("department"), role(r),
                        r.getString("effective_from"), r.getLong("daily_gross_huf"), r.getInt("bonus_basis_points")), date),
                query(conn, """
                        SELECT s.*,u.full_name,d.name AS department,s.role_code AS code,
                               rate.daily_gross_huf,rate.bonus_basis_points
                        FROM payroll_shifts s JOIN users u ON u.id=s.worker_id
                        JOIN payroll_departments d ON d.id=s.department_id
                        LEFT JOIN payroll_department_rates rate ON rate.department_id=s.department_id
                          AND rate.role_code=s.role_code AND rate.effective_from=(
                            SELECT MAX(effective_from) FROM payroll_department_rates
                            WHERE department_id=s.department_id AND role_code=s.role_code AND effective_from<=s.work_day)
                        WHERE s.work_day=? ORDER BY u.full_name,d.name,s.role_code
                        """, r -> new Shift(r.getLong("id"), r.getLong("worker_id"), r.getString("full_name"),
                        r.getLong("department_id"), r.getString("department"), role(r), r.getInt("shift_basis_points"),
                        r.getBoolean("approved_bonus"), nullableLong(r,"daily_gross_huf"), nullableInt(r,"bonus_basis_points")), date),
                performance(conn, date)));
    }

    public void addDepartment(String name, Long sortingTeam) {
        actor();
        if (name == null || name.isBlank() || name.strip().length() > 80) {
            throw new IllegalArgumentException("Enter a department name of up to 80 characters.");
        }
        db.runInTransaction(conn -> update(conn, "INSERT INTO payroll_departments(name,sorting_team_id) VALUES (?,?)",
                name.strip(), sortingTeam));
    }

    public void saveMonthlyGross(long worker, LocalDate effectiveFrom, long huf) {
        long actor = actor();
        validateMoney(huf);
        db.runInTransaction(conn -> update(conn, """
                INSERT INTO payroll_worker_base(worker_id,effective_from,monthly_gross_huf,changed_by) VALUES(?,?,?,?)
                ON CONFLICT(worker_id,effective_from) DO UPDATE
                    SET monthly_gross_huf=excluded.monthly_gross_huf,changed_by=excluded.changed_by
                """, worker, effectiveFrom.toString(), huf, actor));
    }

    public void saveRate(long department, RoleCode role, LocalDate effectiveFrom, long huf, BigDecimal bonusPercent) {
        long actor = actor();
        validateMoney(huf);
        int bonus = points(bonusPercent, true);
        db.runInTransaction(conn -> update(conn, """
                INSERT INTO payroll_department_rates(department_id,role_code,effective_from,daily_gross_huf,bonus_basis_points,changed_by)
                VALUES(?,?,?,?,?,?) ON CONFLICT(department_id,role_code,effective_from) DO UPDATE SET
                    daily_gross_huf=excluded.daily_gross_huf,bonus_basis_points=excluded.bonus_basis_points,changed_by=excluded.changed_by
                """, department, role.name(), effectiveFrom.toString(), huf, bonus, actor));
    }

    public void saveShift(long shift, long worker, LocalDate date, long department, RoleCode role,
                          BigDecimal shiftPercent, boolean approvedBonus) {
        long actor = actor();
        int share = points(shiftPercent, false);
        db.runInTransaction(conn -> {
            if (shift == 0) {
                update(conn, """
                        INSERT INTO payroll_shifts(worker_id,work_day,department_id,role_code,shift_basis_points,approved_bonus,saved_by)
                        VALUES(?,?,?,?,?,?,?)
                        """, worker, date.toString(), department, role.name(), share, approvedBonus ? 1 : 0, actor);
            } else if (update(conn, """
                    UPDATE payroll_shifts SET worker_id=?,work_day=?,department_id=?,role_code=?,
                      shift_basis_points=?,approved_bonus=?,saved_by=? WHERE id=?
                    """, worker, date.toString(), department, role.name(), share, approvedBonus ? 1 : 0, actor, shift) != 1) {
                throw new IllegalStateException("Shift no longer exists.");
            }
            int total = query(conn, """
                    SELECT COALESCE(SUM(shift_basis_points),0) FROM payroll_shifts WHERE worker_id=? AND work_day=?
                    """, r -> r.getInt(1), worker, date.toString()).get(0);
            if (total > 10_000) throw new IllegalArgumentException("A worker's shifts can total at most 100% in one day.");
        });
    }

    public void deleteShift(long shift) {
        actor();
        db.runInTransaction(conn -> update(conn, "DELETE FROM payroll_shifts WHERE id=?", shift));
    }

    private static List<Performance> performance(Connection conn, String date) throws SQLException {
        return query(conn, """
                WITH totals AS (
                    SELECT r.sorter_id AS worker_id,r.team_id,'SORTER' AS code,
                           SUM(r.processed_grams) AS sorted_grams,0 AS packed_grams,0 AS bags,
                           MAX(r.target_grams) AS target_grams
                    FROM sorting_requests r WHERE r.work_day=? GROUP BY r.sorter_id,r.team_id
                    UNION ALL
                    SELECT b.packed_by,r.team_id,'SORTING_HELPER',0,SUM(b.weight_grams),COUNT(*),NULL
                    FROM sorting_bags b JOIN sorting_requests r ON r.id=b.request_id
                    WHERE b.pack_day=? AND b.packed_by IS NOT NULL GROUP BY b.packed_by,r.team_id
                )
                SELECT totals.*,u.full_name,COALESCE(d.name,t.name) AS department
                FROM totals JOIN users u ON u.id=totals.worker_id JOIN sorting_teams t ON t.id=totals.team_id
                LEFT JOIN payroll_departments d ON d.sorting_team_id=t.id
                ORDER BY u.full_name, department, totals.code
                """, r -> new Performance(r.getLong("worker_id"), r.getString("full_name"),
                r.getString("department"), role(r), r.getLong("sorted_grams"), r.getLong("packed_grams"),
                r.getLong("bags"), nullableLong(r,"target_grams")), date, date);
    }

    private long actor() {
        if (!session.canAccessModule(AppModule.PAYROLL)) throw new SecurityException("Payroll access is required.");
        return session.requireCurrentUser().id();
    }

    private static void validateMoney(long huf) {
        if (huf < 0 || huf > 1_000_000_000L) throw new IllegalArgumentException("Enter a gross amount from 0 to 1,000,000,000 HUF.");
    }

    private static int points(BigDecimal pct, boolean allowZero) {
        try {
            int value = pct.movePointRight(2).intValueExact();
            if (value >= (allowZero ? 0 : 1) && value <= 10000) return value;
        } catch (ArithmeticException ignored) { }
        throw new IllegalArgumentException("Enter a percentage up to 100 with at most two decimal places.");
    }

    private static long portion(long huf, int points) {
        return BigDecimal.valueOf(huf).multiply(BigDecimal.valueOf(points, 4)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static RoleCode role(ResultSet r) throws SQLException { return RoleCode.valueOf(r.getString("code")); }

    private static Long nullableLong(ResultSet r, String col) throws SQLException {
        long value = r.getLong(col);
        return r.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet r, String col) throws SQLException {
        int value = r.getInt(col);
        return r.wasNull() ? null : value;
    }

    @FunctionalInterface private interface Row<T> { T read(ResultSet r) throws SQLException; }

    private static <T> List<T> query(Connection conn, String sql, Row<T> read, Object... params) throws SQLException {
        try (var ps = statement(conn, sql, params); var rs = ps.executeQuery()) {
            var rows = new ArrayList<T>();
            while (rs.next()) rows.add(read.read(rs));
            return List.copyOf(rows);
        }
    }

    private static int update(Connection conn, String sql, Object... params) throws SQLException {
        try (var ps = statement(conn, sql, params)) { return ps.executeUpdate(); }
    }

    private static PreparedStatement statement(Connection conn, String sql, Object... params) throws SQLException {
        var ps = conn.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps;
        } catch (SQLException ex) {
            ps.close();
            throw ex;
        }
    }
}
