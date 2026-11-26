package hu.due.rewear.service;

import hu.due.rewear.TestWorkspaces;
import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.DowntimeDao.Scope;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.DowntimeReason;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.LossGroup;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.RotaShift.ShiftCode;
import hu.due.rewear.model.User;
import hu.due.rewear.service.SortingService.Team;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mindrot.jbcrypt.BCrypt;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the hours went that were not spent sorting.
 *
 * <p>These tests pin the three things the record exists for. Nothing closes without a reason, because a
 * stoppage closed blank is the row that comes back as "unknown" when somebody adds the hours up. The crew's
 * own time is separated from everybody else's, because that is what makes a bonus figure survive being
 * challenged. And the waiting time the application works out for itself is reported beside the log and never
 * added to it, because the shift leader may well have logged the same wait by hand.
 */
class DowntimeTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private DowntimeService downtime;
    private SortingService sorting;
    private User manager;
    private User sorter;
    private User supply;
    private Team team;
    private long shelf;

    @BeforeEach
    void setUp() {
        // Pinned to a Thursday evening so that the times these tests write — "it stopped at 09:20" — are in
        // the past whatever hour the suite actually runs at, and so the demo's seeded week is the same week
        // every time.
        pinClockAt(19, 0);
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(new DatabaseManager(tempDir.resolve("downtime.db")),
                new AppPreferences(tempDir.resolve("downtime.properties")), i18n);
        downtime = ctx.getDowntimeService();
        sorting = ctx.getSortingService();
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        sorter = ctx.getUserDao().findByUsername("sorter01").orElseThrow();
        supply = ctx.getUserDao().findByUsername("forklift").orElseThrow();
        shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0).id();
        ctx.getSession().login(manager);
        // The demo opens with a week of work and the stoppages that went with it; these tests measure only
        // what they record themselves.
        TestWorkspaces.clearSeededSortingHistory(ctx);
        team = sorting.workspace(sorting.today()).teams().stream()
                .filter(row -> row.sorterIds().contains(sorter.id())).findFirst().orElseThrow();
    }

    /** Moves the pinned clock to a time on the same Thursday, for a test that needs time to pass. */
    private static void pinClockAt(int hour, int minute) {
        BusinessClock.useForTesting(java.time.Clock.fixed(
                LocalDate.of(2026, 11, 26).atTime(hour, minute).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));
    }

    @AfterEach
    void tearDown() {
        BusinessClock.resetForTesting();
    }

    private long reason(String code) {
        return downtime.reasons(true).stream().filter(row -> row.code().equals(code)).findFirst()
                .orElseThrow().id();
    }

    private static LocalDateTime at(int hour, int minute) {
        return BusinessClock.today().atTime(hour, minute);
    }

    private User withRole(String username, RoleCode role) {
        return ctx.getUserDao().findByUsername(username).orElseGet(() -> {
            long roleId = ctx.getRoleDao().findByCode(role).orElseThrow().id();
            ctx.getUserDao().insert(username, BCrypt.hashpw("x", BCrypt.gensalt(4)),
                    "Teszt " + role.name(), roleId, true);
            return ctx.getUserDao().findByUsername(username).orElseThrow();
        });
    }

    // ------------------------------------------------------------------ the record itself

    @Test
    void aStoppageIsRecordedWithTheMinutesTakenFromTheClockRatherThanTyped() {
        long id = downtime.log(team.id(), null, null, at(9, 20), at(9, 55), reason("SUP-WAIT"), "No bale left");

        var event = downtime.find(id).orElseThrow();
        assertEquals(35, event.minutes(), "09:20 to 09:55 is thirty-five minutes, whatever anybody types");
        assertEquals(LossGroup.SUPPLY, event.lossGroup());
        assertFalse(event.running());
        assertEquals(manager.id(), event.openedBy(), "A stoppage is somebody's record");
        assertEquals(4, event.peopleAffected(), "The whole team was standing there, not one person");
    }

    @Test
    void aStoppageThatRunsBackwardsOrForeverIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> downtime.log(team.id(), null, null, at(10, 0), at(9, 0), reason("SUP-WAIT"), null),
                "A stoppage cannot end before it started");
        assertThrows(IllegalArgumentException.class,
                () -> downtime.log(team.id(), null, null, at(10, 0), at(10, 0), reason("SUP-WAIT"), null),
                "A stoppage of no length is not a stoppage");
        assertThrows(IllegalArgumentException.class,
                () -> downtime.log(team.id(), null, null,
                        BusinessClock.now().minusDays(3), BusinessClock.now(), reason("SUP-WAIT"), null),
                "Longer than a day is a shift that did not run, recorded a day at a time");
        assertThrows(IllegalArgumentException.class,
                () -> downtime.log(team.id(), null, null, BusinessClock.now().plusHours(2),
                        BusinessClock.now().plusHours(3), reason("SUP-WAIT"), null),
                "Nothing is recorded before it has happened");
    }

    @Test
    void nothingClosesWithoutAReason() {
        long id = downtime.open(team.id(), null, null, BusinessClock.now().minusMinutes(20), null, null);
        var open = downtime.find(id).orElseThrow();
        assertTrue(open.running());
        assertNull(open.reasonId(), "Why it stopped is not always known in the first minute");
        assertNull(open.minutes(), "A stoppage still running has no length yet");

        assertThrows(IllegalArgumentException.class, () -> downtime.close(id, 0, null),
                "Closing with no reason at all is what produces a log full of 'unknown'");

        downtime.close(id, reason("EQP-SCALE"), "Restarted it");
        var closed = downtime.find(id).orElseThrow();
        assertEquals("EQP-SCALE", closed.reasonCode());
        assertNotNull(closed.minutes());
        assertEquals(manager.id(), closed.closedBy());
        assertThrows(IllegalStateException.class, () -> downtime.close(id, reason("EQP-SCALE"), null),
                "A stoppage closes once");
    }

    @Test
    void oneStoppageAtATimeForTheSamePeople() {
        long open = downtime.open(team.id(), null, null, null, null, null);

        assertThrows(IllegalStateException.class,
                () -> downtime.open(team.id(), null, null, null, null, null),
                "Two open stoppages over the same people would count the same minutes twice");
        assertThrows(IllegalStateException.class,
                () -> downtime.open(null, sorter.id(), null, null, null, null),
                "This sorter's team is already stopped, so their minutes are already being counted");

        pinClockAt(19, 10);
        downtime.close(open, reason("SUP-WAIT"), null);
        downtime.open(null, null, null, null, null, null);
        assertThrows(IllegalStateException.class,
                () -> downtime.open(team.id(), null, null, null, null, null),
                "The whole line is stopped and this team is on it");
    }

    @Test
    void aStoppageOnOneTeamDoesNotBlockRecordingOneOnAnother() {
        var other = sorting.workspace(sorting.today()).teams().stream()
                .filter(row -> row.id() != team.id()).findFirst().orElseThrow();
        var third = sorting.workspace(sorting.today()).teams().stream()
                .filter(row -> row.id() != team.id() && row.id() != other.id()).findFirst().orElseThrow();
        var elsewhere = ctx.getUserDao().findAll().stream()
                .filter(user -> third.sorterIds().contains(user.id())).findFirst().orElseThrow();
        downtime.open(team.id(), null, null, null, null, null);

        // Benches stop at once for different reasons, and each one has to be recordable.
        downtime.open(other.id(), null, null, null, null, null);
        downtime.open(null, elsewhere.id(), null, null, null, null);

        assertEquals(3, downtime.running().size(),
                "A stoppage at another bench is not this bench's stoppage");
    }

    // ------------------------------------------------------------------ what it adds up to

    @Test
    void theCrewsOwnTimeIsSeparatedFromWhatWasDoneToThem() {
        // A break that ran over is theirs. Waiting for a forklift is not, and holding that against them is
        // how a bonus scheme stops being believed.
        downtime.log(team.id(), null, null, at(8, 0), at(8, 30), reason("SUP-WAIT"), null);
        downtime.log(team.id(), null, null, at(12, 0), at(12, 20), reason("STA-BREAK"), null);

        var report = downtime.report(Scope.day(BusinessClock.today()));

        assertEquals(50, report.stoppedMinutes());
        assertEquals(200, report.personMinutes(), "Four people stood still for fifty minutes between them");
        assertEquals(80, report.attributablePersonMinutes(), "Only the twenty minutes of break overrun");
        assertEquals(120, report.unattributablePersonMinutes());
        assertEquals(10_000, report.lines().stream().mapToInt(l -> l.shareBasisPoints()).sum(),
                "The reasons account for the whole stoppage");
    }

    @Test
    void aStoppageThatStopsFourPeopleCostsFourTimesOneThatStopsOne() {
        downtime.log(team.id(), null, null, at(9, 0), at(9, 30), reason("EQP-SCALE"), null);
        downtime.log(null, sorter.id(), null, at(14, 0), at(14, 30), reason("EQP-SCALE"), null);

        var report = downtime.report(Scope.day(BusinessClock.today()));

        assertEquals(60, report.stoppedMinutes(), "An hour of wall clock either way");
        assertEquals(150, report.personMinutes(), "But one stopped four people and the other stopped one");
    }

    @Test
    void onePersonsOwnTimeIsTheirOwnMinutesAndNotTheWholeBenchs() {
        downtime.log(team.id(), null, null, at(9, 0), at(9, 30), reason("SUP-WAIT"), null);

        var line = downtime.report(Scope.day(BusinessClock.today()));
        var hers = downtime.report(new Scope(BusinessClock.today(), BusinessClock.today(), null, sorter.id()));

        assertEquals(120, line.personMinutes(), "Four people lost half an hour each");
        assertEquals(30, hers.stoppedMinutes());
        assertEquals(30, hers.personMinutes(),
                "Her half hour is a half hour. The other three at the same bench are a fact about the team's"
                        + " loss, not about hers, and adding them in is what makes somebody distrust a figure");
    }

    @Test
    void aStoppageOfTheWholeLineCountsForEveryTeamOnIt() {
        var other = sorting.workspace(sorting.today()).teams().stream()
                .filter(row -> row.id() != team.id()).findFirst().orElseThrow();
        downtime.log(null, null, null, at(10, 0), at(10, 45), reason("FAC-POWER"), "Whole hall");

        assertEquals(45, downtime.report(Scope.day(BusinessClock.today()).forTeam(team.id())).stoppedMinutes());
        assertEquals(45, downtime.report(Scope.day(BusinessClock.today()).forTeam(other.id())).stoppedMinutes(),
                "A power cut stopped them too, and a team report that left it out answers a different question");
    }

    @Test
    void availabilityIsMeasuredAgainstTheHoursThatWereRostered() {
        ctx.getRotaService().planShift(sorter.id(), BusinessClock.today(), ShiftCode.MORNING, null);
        downtime.log(null, sorter.id(), null, at(9, 0), at(10, 0), reason("EQP-SCALE"), null);

        var report = downtime.report(new Scope(BusinessClock.today(), BusinessClock.today(), null, sorter.id()));

        assertEquals(480, report.rosteredMinutes(), "One eight-hour morning shift");
        assertEquals(60, report.personMinutes());
        assertEquals(8750, report.availabilityBasisPoints(), "420 of 480 minutes were available to sort in");
        assertEquals(0, new BigDecimal("87.50").compareTo(report.availabilityPercent()));
    }

    @Test
    void withNoRosteredHoursThereIsNoAvailabilityFigureRatherThanAZero() {
        var lead = withRole("leadtest", RoleCode.PRODUCTION_LEAD);
        downtime.log(null, lead.id(), null, at(9, 0), at(9, 30), reason("ADM-MEET"), null);

        var report = downtime.report(new Scope(BusinessClock.today(), BusinessClock.today(), null, lead.id()));

        assertEquals(0, report.rosteredMinutes());
        assertNull(report.availabilityBasisPoints(),
                "A loss divided by no hours at all is not a percentage anybody can act on");
    }

    @Test
    void waitingForMaterialIsWorkedOutFromTheRequestsAndKeptApartFromTheLog() {
        // The application has always been able to work this one out for itself: a sorter called for material
        // at one timestamp and a driver delivered it at another.
        ctx.getSession().login(manager);
        long lot = sorting.receive("RAW-DOWN-1", "Vegyes", "Pest", new BigDecimal("500"));
        sorting.storeLot(lot, shelf);
        ctx.getSession().login(sorter);
        long request = sorting.requestSupply(lot);
        // The clock moves on while the sorter waits at an empty bench, which is the whole point of the figure.
        pinClockAt(19, 25);
        ctx.getSession().login(supply);
        sorting.deliver(request, new BigDecimal("200"));
        ctx.getSession().login(manager);
        downtime.log(team.id(), null, null, at(8, 0), at(8, 30), reason("SUP-WAIT"), null);

        var report = downtime.report(Scope.day(BusinessClock.today()));

        assertEquals(25, report.waitingForStockMinutes(), "Called for at 19:00, delivered at 19:25");
        assertEquals(30, report.stoppedMinutes(),
                "The derived wait is reported beside the log, never added into it: the shift leader may well"
                        + " have logged that same wait by hand, and adding both would count it twice");
        assertEquals(120, report.personMinutes(), "Which is the logged half hour for four people, and no more");
        assertFalse(report.isEmpty());
    }

    @Test
    void theDatabaseItselfRefusesAStoppageClosedWithNoReason() {
        // The service will not let one through, and neither will the table: this rule is the whole record,
        // so it is written down in both places rather than trusted to the layer above.
        var now = BusinessClock.now();
        assertThrows(RuntimeException.class, () -> ctx.getDatabaseManager().runInTransaction(conn ->
                ctx.getDowntimeDao().insertEvent(conn, BusinessClock.today(), team.id(), null, null,
                        at(9, 0), at(9, 30), 30, 4, null, "no reason given", manager.id(), now,
                        manager.id(), now)));
    }

    @Test
    void theLinesCapacityIsSorterHoursAndNobodyElses() {
        // A day beyond the demo's rota, so only what this test plans is on it.
        var day = BusinessClock.today().plusDays(25);
        var helper = ctx.getUserDao().findByUsername("helper01").orElseThrow();
        ctx.getRotaService().planShift(sorter.id(), day, ShiftCode.MORNING, null);
        ctx.getRotaService().planShift(helper.id(), day, ShiftCode.MORNING, null);

        var report = downtime.report(new Scope(day, day, null, null));

        assertEquals(480, report.rosteredMinutes(),
                "A helper packs what comes off the bench and a driver feeds it; the line's capacity is the"
                        + " time of the people who sort on it, and counting the rest in would make every"
                        + " stoppage look smaller than it was");
    }

    // ------------------------------------------------------------------ the reason list

    @Test
    void theListIsShortEnoughToBeUsedHonestly() {
        var reasons = downtime.reasons(true);

        assertTrue(reasons.size() <= 40, "A long list gets the first plausible option, not the real one");
        for (LossGroup group : LossGroup.values()) {
            long inGroup = reasons.stream().filter(row -> row.lossGroup() == group).count();
            assertTrue(inGroup < 10, group + " has " + inGroup + " reasons; fewer than ten is the point");
        }
        assertTrue(reasons.stream().anyMatch(DowntimeReason::attributable), "Some losses are the crew's own");
        assertTrue(reasons.stream().anyMatch(row -> !row.attributable()), "Most are not");
    }

    @Test
    void retiringAReasonKeepsTheStoppagesLoggedUnderIt() {
        long scale = reason("EQP-SCALE");
        downtime.log(team.id(), null, null, at(9, 0), at(9, 40), scale, null);

        downtime.retireReason(scale);

        assertTrue(downtime.reasons(true).stream().noneMatch(row -> row.id() == scale),
                "It is not offered any more");
        assertThrows(IllegalArgumentException.class,
                () -> downtime.log(team.id(), null, null, at(11, 0), at(11, 10), scale, null),
                "And nothing new can be logged against it");
        var report = downtime.report(Scope.day(BusinessClock.today()));
        assertEquals(40, report.stoppedMinutes(),
                "But last week happened under that wording, and rewriting it would make the report fiction");
        assertEquals("EQP-SCALE", report.lines().get(0).code());

        downtime.reinstateReason(scale);
        assertTrue(downtime.reasons(true).stream().anyMatch(row -> row.id() == scale));
    }

    @Test
    void rewordingAReasonIsAFortnightlyReviewAndNotARelease() {
        long wait = reason("SUP-WAIT");
        downtime.log(team.id(), null, null, at(9, 0), at(9, 15), wait, null);

        downtime.updateReason(wait, LossGroup.SUPPLY, "Bench ran dry", "Kifogyott a munkahelyen", false);

        var reworded = downtime.reasons(true).stream().filter(row -> row.id() == wait).findFirst()
                .orElseThrow();
        assertEquals("Bench ran dry", reworded.nameEn());
        assertEquals("Kifogyott a munkahelyen", reworded.nameHu());
        assertEquals(15, downtime.report(Scope.day(BusinessClock.today())).stoppedMinutes(),
                "The stoppage is still the same stoppage, under better words");
    }

    @Test
    void anInventedReasonNeedsBothLanguagesAndAGroup() {
        assertThrows(IllegalArgumentException.class,
                () -> downtime.createReason("", LossGroup.SUPPLY, "Something", "Valami", false));
        assertThrows(IllegalArgumentException.class,
                () -> downtime.createReason("NEW-1", LossGroup.SUPPLY, "Something", "  ", false),
                "A floor that reads Hungarian needs the Hungarian wording");
        assertThrows(IllegalArgumentException.class,
                () -> downtime.createReason("NEW-1", null, "Something", "Valami", false));

        long id = downtime.createReason("new-2", LossGroup.EQUIPMENT, "Trolley wheel broke",
                "Eltört a kocsi kereke", false);
        assertEquals("NEW-2", downtime.reasons(true).stream().filter(row -> row.id() == id).findFirst()
                .orElseThrow().code(), "Codes are written one way so the list can be read at a glance");
    }

    // ------------------------------------------------------------------ who may do what

    @Test
    void recordingIsTheShiftLeadersJobAndTheListIsTheProductionLeadsCall() {
        var shiftLead = withRole("shiftlead", RoleCode.SHIFT_LEAD);
        long wait = reason("SUP-WAIT");

        ctx.getSession().login(shiftLead);
        long id = downtime.log(team.id(), null, null, at(9, 0), at(9, 20), wait, null);
        assertNotNull(downtime.find(id).orElseThrow().reasonCode(), "Running the shift includes its stoppages");
        assertThrows(IllegalStateException.class,
                () -> downtime.createReason("SL-1", LossGroup.SUPPLY, "Mine", "Enyém", false),
                "What everybody's figures are reported against is a level up");
        assertThrows(IllegalStateException.class, () -> downtime.retireReason(wait));

        ctx.getSession().login(sorter);
        assertThrows(IllegalStateException.class,
                () -> downtime.log(team.id(), null, null, at(13, 0), at(13, 10), wait, null),
                "A sorter does not write the record their own bonus is measured from");
    }

    @Test
    void aSorterSeesTheirOwnNonProductiveTimeAndNobodyElses() {
        var other = ctx.getUserDao().findByUsername("sorter05").orElseThrow();
        downtime.log(null, sorter.id(), null, at(9, 0), at(9, 30), reason("SUP-WAIT"), null);
        downtime.log(null, other.id(), null, at(10, 0), at(11, 0), reason("SUP-WAIT"), null);

        ctx.getSession().login(sorter);
        var own = downtime.report(Scope.day(BusinessClock.today()));

        assertEquals(30, own.stoppedMinutes(), "Their half hour, not the other sorter's hour");
        assertThrows(IllegalStateException.class,
                () -> downtime.report(new Scope(BusinessClock.today(), BusinessClock.today(), null,
                        other.id())),
                "Somebody else's figures are not theirs to read row by row");
        assertEquals(1, downtime.events(Scope.day(BusinessClock.today())).size());
    }

    @Test
    void thePackingFloorHasNoBusinessInTheStoppageFigures() {
        var helper = ctx.getUserDao().findByUsername("helper01").orElseThrow();
        ctx.getSession().login(helper);

        assertThrows(IllegalStateException.class, () -> downtime.report(Scope.all()));
    }

    // ------------------------------------------------------------------ the demo

    @Test
    void theDemoWorkspaceOpensWithStoppagesAndOneStillRunning() {
        // Deliberately against a second workspace: this one had its week cleared in setUp.
        I18nManager i18n = I18nManager.getInstance();
        var fresh = new AppContext(new DatabaseManager(tempDir.resolve("demo-downtime.db")),
                new AppPreferences(tempDir.resolve("demo-downtime.properties")), i18n);
        fresh.getSession().login(fresh.getUserDao().findByUsername("manager").orElseThrow());

        var report = fresh.getDowntimeService().report(Scope.all());

        assertFalse(report.isEmpty(), "A stoppage screen with nothing on it teaches nobody anything");
        assertTrue(report.stoppedMinutes() > 0);
        assertTrue(report.attributablePersonMinutes() > 0, "Some of the week was the crew's own doing");
        assertTrue(report.unattributablePersonMinutes() > report.attributablePersonMinutes(),
                "And most of it was not, which is the honest shape of a week");
        assertTrue(report.waitingForStockMinutes() > 0, "The derived wait has something to show as well");
        assertEquals(1, fresh.getDowntimeService().running().size(),
                "One is left open, so the first thing a reader does is close it and say why");
        Integer availability = report.availabilityBasisPoints();
        assertNotNull(availability, "The demo rosters shifts, so availability is a figure");
        assertTrue(availability > 5000 && availability < 10_000, "Plausible, not perfect: " + availability);
    }

    @Test
    void anEmptyPeriodReportsNothingRatherThanAZeroShapedReport() {
        var report = downtime.report(new Scope(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 31),
                null, null));

        assertTrue(report.isEmpty());
        assertTrue(report.lines().isEmpty());
        assertEquals(0, report.stoppedMinutes());
    }
}
