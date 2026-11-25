package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.DaoDateUtil;
import hu.due.rewear.dao.DaoException;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.RotaShift;
import hu.due.rewear.model.RotaShift.ShiftCode;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shift rota: who works which shift on which day.
 * <p>
 * Everyone can look at the rota, but what they see depends on their role: the people who plan shifts
 * ({@link RoleCode#ROTA_PLANNERS}) see the whole company, and everyone else sees their own days only.
 * Writing a shift is limited to the planners.
 */
public final class RotaService {

    private static final Logger LOG = LoggerFactory.getLogger(RotaService.class);

    private static final String SELECT = """
            SELECT s.*, u.full_name AS worker, r.code AS role_code
            FROM rota_shifts s JOIN users u ON u.id=s.user_id JOIN roles r ON r.id=u.role_id
            """;

    /** One day of the calendar, with everything planned for it. */
    public record Day(LocalDate date, List<RotaShift> shifts) {
        public Day {
            shifts = List.copyOf(shifts);
        }

        public long working() {
            return shifts.stream().filter(RotaShift::working).count();
        }

        public long off() {
            return shifts.size() - working();
        }

        public List<RotaShift> inShift(ShiftCode code) {
            return shifts.stream().filter(s -> s.shift() == code).toList();
        }
    }

    private final DatabaseManager db;
    private final SessionContext session;
    private final UserDao userDao;

    public RotaService(DatabaseManager db, SessionContext session, UserDao userDao) {
        this.db = db;
        this.session = session;
        this.userDao = userDao;
    }

    public LocalDate today() {
        return BusinessClock.today();
    }

    public boolean mayPlan() {
        return session.getCurrentUser().map(user -> user.roleCode().plansRota()).orElse(false);
    }

    /** The days of one calendar month, including the leading and trailing days that fill the grid. */
    public List<Day> month(YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate gridStart = first.minusDays((first.getDayOfWeek().getValue() + 6) % 7);
        LocalDate gridEnd = gridStart.plusDays(41);
        return range(gridStart, gridEnd);
    }

    /** The Monday-to-Sunday week that contains {@code anyDay}. */
    public List<Day> week(LocalDate anyDay) {
        LocalDate monday = anyDay.minusDays((anyDay.getDayOfWeek().getValue() + 6) % 7);
        return range(monday, monday.plusDays(6));
    }

    public Day day(LocalDate date) {
        return range(date, date).get(0);
    }

    private List<Day> range(LocalDate from, LocalDate to) {
        User actor = requireViewer();
        boolean everyone = actor.roleCode().plansRota();
        String sql = SELECT + " WHERE s.work_day BETWEEN ? AND ?"
                + (everyone ? "" : " AND s.user_id=?")
                + " ORDER BY s.work_day, s.start_minute, u.full_name";
        Map<LocalDate, List<RotaShift>> byDay = new LinkedHashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            if (!everyone) {
                ps.setLong(3, actor.id());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RotaShift shift = map(rs);
                    byDay.computeIfAbsent(shift.day(), key -> new ArrayList<>()).add(shift);
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to load the rota", e);
        }
        List<Day> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            days.add(new Day(date, byDay.getOrDefault(date, List.of())));
        }
        return List.copyOf(days);
    }

    /**
     * Plans one person's day, replacing whatever they had on it. A worker has at most one shift a day, so
     * planning the same day again is a correction rather than a second shift.
     */
    public void planShift(long userId, LocalDate day, ShiftCode shift, String note) {
        planShift(userId, day, shift, shift.defaultStart(), shift.defaultEnd(), note);
    }

    public void planShift(long userId, LocalDate day, ShiftCode shift, int startMinute, int endMinute, String note) {
        User actor = requirePlanner();
        if (day == null || shift == null) {
            throw new IllegalArgumentException("Choose a day and a shift.");
        }
        if (startMinute < 0 || startMinute > 1439 || endMinute < 0 || endMinute > 1439) {
            throw new IllegalArgumentException("Shift times must fall inside a day.");
        }
        String comment = note == null || note.isBlank() ? null : note.strip();
        if (comment != null && comment.length() > 200) {
            throw new IllegalArgumentException("Keep the note under 200 characters.");
        }
        db.runInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT active FROM users WHERE id=?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) != 1) {
                        throw new IllegalArgumentException("Choose an active worker.");
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO rota_shifts (user_id, work_day, shift_code, start_minute, end_minute, note, created_by, created_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    ON CONFLICT(user_id, work_day) DO UPDATE SET
                        shift_code=excluded.shift_code, start_minute=excluded.start_minute,
                        end_minute=excluded.end_minute, note=excluded.note,
                        created_by=excluded.created_by, created_at=excluded.created_at
                    """)) {
                ps.setLong(1, userId);
                ps.setString(2, day.toString());
                ps.setString(3, shift.name());
                ps.setInt(4, startMinute);
                ps.setInt(5, endMinute);
                ps.setString(6, comment);
                ps.setLong(7, actor.id());
                ps.setString(8, DaoDateUtil.format(BusinessClock.now()));
                ps.executeUpdate();
            }
        });
        LOG.info("Rota: {} planned for {} on {} by {}", userId, shift, day, actor.username());
    }

    /** Removes a planned day entirely, leaving it blank rather than marking it off. */
    public void clearShift(long userId, LocalDate day) {
        requirePlanner();
        db.runInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM rota_shifts WHERE user_id=? AND work_day=?")) {
                ps.setLong(1, userId);
                ps.setString(2, day.toString());
                ps.executeUpdate();
            }
        });
    }

    /** Active people a planner may put on the rota, the floor roles first. */
    public List<User> plannableWorkers() {
        requirePlanner();
        List<User> workers = new ArrayList<>(userDao.findAll().stream().filter(User::active).toList());
        workers.sort((a, b) -> {
            boolean aFloor = RoleCode.FLOOR.contains(a.roleCode());
            boolean bFloor = RoleCode.FLOOR.contains(b.roleCode());
            if (aFloor != bFloor) {
                return aFloor ? -1 : 1;
            }
            return a.displayName().compareToIgnoreCase(b.displayName());
        });
        return List.copyOf(workers);
    }

    private User requireViewer() {
        if (!session.canAccessModule(AppModule.ROTA)) {
            throw new IllegalStateException("Rota access is required.");
        }
        return session.requireCurrentUser();
    }

    private User requirePlanner() {
        User actor = requireViewer();
        if (!actor.roleCode().plansRota()) {
            throw new IllegalStateException("Only a shift leader, production manager, HR or management may plan shifts.");
        }
        return actor;
    }

    private static RotaShift map(ResultSet rs) throws SQLException {
        return new RotaShift(rs.getLong("id"), rs.getLong("user_id"), rs.getString("worker"),
                RoleCode.valueOf(rs.getString("role_code")), LocalDate.parse(rs.getString("work_day")),
                ShiftCode.valueOf(rs.getString("shift_code")), rs.getInt("start_minute"), rs.getInt("end_minute"),
                rs.getString("note"));
    }
}
