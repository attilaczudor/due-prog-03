package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.RotaShift;
import hu.due.rewear.model.RotaShift.ShiftCode;
import hu.due.rewear.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rota: a calendar anyone may look at, which only the planning roles may write, and where a worker
 * sees their own days rather than the whole company's.
 */
class RotaServiceTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private RotaService rota;
    private User manager;
    private User sorter;

    private void setUp() {
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(new DatabaseManager(tempDir.resolve("rota.db")),
                new AppPreferences(tempDir.resolve("app.properties")), i18n);
        rota = ctx.getRotaService();
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        sorter = ctx.getUserDao().findByUsername("sorter01").orElseThrow();
        ctx.getSession().login(manager);
    }

    private RotaShift shiftOf(LocalDate day, long userId) {
        return rota.day(day).shifts().stream().filter(s -> s.userId() == userId).findFirst().orElseThrow();
    }

    @Test
    void planningADayStoresTheShiftAndPlanningItAgainCorrectsIt() {
        setUp();
        LocalDate day = rota.today().plusDays(40);           // beyond the seeded month, so it starts empty

        rota.planShift(sorter.id(), day, ShiftCode.NIGHT, "Covering for Anna");

        RotaShift planned = shiftOf(day, sorter.id());
        assertEquals(ShiftCode.NIGHT, planned.shift());
        assertEquals(22 * 60, planned.startMinute());
        assertEquals("Covering for Anna", planned.note());
        assertEquals(8 * 60, planned.lengthMinutes(), "A night shift runs past midnight");
        assertEquals("22:00–06:00", planned.timeRange());
        assertTrue(planned.working());

        // The same day again replaces it: one person has one shift a day.
        rota.planShift(sorter.id(), day, ShiftCode.OFF, null);
        RotaShift corrected = shiftOf(day, sorter.id());
        assertEquals(ShiftCode.OFF, corrected.shift());
        assertFalse(corrected.working());
        assertEquals(0, corrected.lengthMinutes());
        assertEquals(1, rota.day(day).shifts().stream().filter(s -> s.userId() == sorter.id()).count());

        rota.clearShift(sorter.id(), day);
        assertTrue(rota.day(day).shifts().stream().noneMatch(s -> s.userId() == sorter.id()));
    }

    @Test
    void theMonthGridCoversWholeWeeksAndTheWeekStartsOnMonday() {
        setUp();
        List<RotaService.Day> month = rota.month(YearMonth.of(2026, 11));

        assertEquals(42, month.size(), "Six whole weeks fill the grid");
        assertEquals(java.time.DayOfWeek.MONDAY, month.get(0).date().getDayOfWeek());
        assertEquals(LocalDate.of(2026, 10, 26), month.get(0).date(), "November 2026 starts on a Sunday");
        assertEquals(java.time.DayOfWeek.SUNDAY, month.get(41).date().getDayOfWeek());

        List<RotaService.Day> week = rota.week(LocalDate.of(2026, 11, 25));
        assertEquals(7, week.size());
        assertEquals(LocalDate.of(2026, 11, 23), week.get(0).date());
        assertEquals(LocalDate.of(2026, 11, 29), week.get(6).date());
    }

    @Test
    void aWorkerSeesOnlyTheirOwnDaysWhileAPlannerSeesEveryone() {
        setUp();
        LocalDate day = rota.today().plusDays(45);
        User helper = ctx.getUserDao().findByUsername("helper01").orElseThrow();
        rota.planShift(sorter.id(), day, ShiftCode.MORNING, null);
        rota.planShift(helper.id(), day, ShiftCode.MORNING, null);
        assertEquals(2, rota.day(day).shifts().size(), "A planner sees the whole company");

        ctx.getSession().login(sorter);
        List<RotaShift> mine = rota.day(day).shifts();
        assertEquals(1, mine.size());
        assertEquals(sorter.id(), mine.get(0).userId());
        assertFalse(rota.mayPlan());
        assertEquals(1, rota.week(day).stream().mapToLong(d -> d.shifts().size()).sum());
    }

    @Test
    void onlyThePlanningRolesMayWriteTheRota() {
        setUp();
        LocalDate day = rota.today().plusDays(50);

        for (String username : List.of("sorter01", "helper01", "operator", "forklift")) {
            User worker = ctx.getUserDao().findByUsername(username).orElseThrow();
            ctx.getSession().login(worker);
            assertFalse(rota.mayPlan(), username + " must not plan shifts");
            assertThrows(IllegalStateException.class,
                    () -> rota.planShift(worker.id(), day, ShiftCode.MORNING, null), username);
            assertThrows(IllegalStateException.class, () -> rota.clearShift(worker.id(), day), username);
            assertThrows(IllegalStateException.class, rota::plannableWorkers, username);
        }

        ctx.getSession().login(manager);
        assertTrue(rota.mayPlan());
        rota.planShift(sorter.id(), day, ShiftCode.MORNING, null);
        assertEquals(ShiftCode.MORNING, shiftOf(day, sorter.id()).shift());
        assertThrows(IllegalArgumentException.class, () -> rota.planShift(999_999L, day, ShiftCode.MORNING, null));
        assertThrows(IllegalArgumentException.class, () -> rota.planShift(sorter.id(), day, ShiftCode.MORNING, 1500, 60, null));
        assertThrows(IllegalArgumentException.class, () -> rota.planShift(sorter.id(), day, ShiftCode.MORNING, "x".repeat(201)));
    }

    @Test
    void theDemoRotaCoversTheCurrentMonthWithRealWorkersAndRestDays() {
        setUp();
        List<RotaService.Day> month = rota.month(YearMonth.from(rota.today()));
        long planned = month.stream().mapToLong(d -> d.shifts().size()).sum();

        assertTrue(planned > 100, "The demo rota should be populated: " + planned);
        RotaService.Day today = rota.day(rota.today());
        assertTrue(today.working() > 0, "Someone is working today");
        assertTrue(month.stream().anyMatch(d -> d.off() > 0), "Rest days are planned, not just absent");
        for (ShiftCode code : List.of(ShiftCode.MORNING, ShiftCode.AFTERNOON, ShiftCode.NIGHT)) {
            assertTrue(month.stream().anyMatch(d -> !d.inShift(code).isEmpty()), "No one is on " + code);
        }
        assertTrue(today.shifts().stream().anyMatch(s -> s.roleCode() == RoleCode.SORTER));
        assertTrue(today.shifts().stream().allMatch(s -> s.workerName() != null && !s.workerName().isBlank()));
        // Office-only roles are not put on a floor shift pattern.
        assertTrue(today.shifts().stream().noneMatch(s -> s.roleCode() == RoleCode.HR_PAYROLL));
    }
}
