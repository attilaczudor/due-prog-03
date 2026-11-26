package hu.due.rewear.service;

import hu.due.rewear.TestWorkspaces;
import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.HandoverIssue;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.RotaShift.ShiftCode;
import hu.due.rewear.model.User;
import hu.due.rewear.service.ShiftHandoverService.NewHandover;
import hu.due.rewear.service.ShiftHandoverService.NewIssue;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mindrot.jbcrypt.BCrypt;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one shift hands to the next.
 *
 * <p>The template this follows is six fields written in ten minutes before the outgoing supervisor leaves,
 * and four of those six are not figures. These tests pin the parts that are easy to get wrong: that no field
 * may be left blank, that every issue carries a name and a date, that the figures are frozen rather than
 * recomputed, that the screen opens on the last shift rather than the current one, and that an issue nobody
 * closes within forty-eight hours goes up a tier on its own.
 */
class ShiftHandoverTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private ShiftHandoverService handovers;
    private SortingService sorting;
    private User manager;
    private User sorter;
    private User supply;
    private long shelf;

    @BeforeEach
    void setUp() {
        // Pinned to a Thursday evening, inside the afternoon shift, so that "this shift" means something
        // definite whatever hour the suite runs at.
        pinClockAt(19, 0);
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(new DatabaseManager(tempDir.resolve("handover.db")),
                new AppPreferences(tempDir.resolve("handover.properties")), i18n);
        handovers = ctx.getShiftHandoverService();
        sorting = ctx.getSortingService();
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        sorter = ctx.getUserDao().findByUsername("sorter01").orElseThrow();
        supply = ctx.getUserDao().findByUsername("forklift").orElseThrow();
        shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0).id();
        ctx.getSession().login(manager);
        // The demo opens with a week of sorting, stoppages and handovers behind it; these tests write their
        // own.
        TestWorkspaces.clearSeededSortingHistory(ctx);
    }

    @AfterEach
    void tearDown() {
        BusinessClock.resetForTesting();
    }

    private static void pinClockAt(int hour, int minute) {
        BusinessClock.useForTesting(java.time.Clock.fixed(
                LocalDate.of(2026, 11, 26).atTime(hour, minute).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));
    }

    private NewHandover sheet(ShiftCode shift, List<NewIssue> issues) {
        return new NewHandover(BusinessClock.today(), shift, "Scale calibrated", "Full crew",
                "Nothing to report", "Put paper in the label printer before the line starts", issues);
    }

    /** Sorts a load so the shift has figures of its own, finished at whatever the clock says now. */
    private void sortSomething(String reference, String kg) {
        ctx.getSession().login(manager);
        long lot = sorting.receive(reference, "Vegyes", "Pest", new BigDecimal("500"));
        sorting.storeLot(lot, shelf);
        ctx.getSession().login(sorter);
        long request = sorting.requestSupply(lot);
        ctx.getSession().login(supply);
        sorting.deliver(request, new BigDecimal(kg));
        var dict = ctx.getDictionaryDao();
        ctx.getSession().login(sorter);
        sorting.reportBag(request, dict.findTargetGroups(true).get(0).id(),
                dict.findGarmentTypes(true).get(0).id(), dict.findQualityGrades(true).get(0).id(),
                new BigDecimal("40"));
        sorting.finish(request, new BigDecimal(kg));
        ctx.getSession().login(manager);
    }

    private User withRole(String username, RoleCode role) {
        return ctx.getUserDao().findByUsername(username).orElseGet(() -> {
            long roleId = ctx.getRoleDao().findByCode(role).orElseThrow().id();
            ctx.getUserDao().insert(username, BCrypt.hashpw("x", BCrypt.gensalt(4)),
                    "Teszt " + role.name(), roleId, true);
            return ctx.getUserDao().findByUsername(username).orElseThrow();
        });
    }

    // ------------------------------------------------------------------ the six fields

    @Test
    void noneOfTheWrittenFieldsMayBeLeftBlank() {
        var day = BusinessClock.today();
        assertThrows(IllegalArgumentException.class, () -> handovers.submit(new NewHandover(day,
                ShiftCode.AFTERNOON, "  ", "Full crew", "Nothing", "Check the printer", List.of())));
        assertThrows(IllegalArgumentException.class, () -> handovers.submit(new NewHandover(day,
                ShiftCode.AFTERNOON, "Fine", "", "Nothing", "Check the printer", List.of())));
        assertThrows(IllegalArgumentException.class, () -> handovers.submit(new NewHandover(day,
                ShiftCode.AFTERNOON, "Fine", "Full crew", null, "Check the printer", List.of())));
        assertThrows(IllegalArgumentException.class, () -> handovers.submit(new NewHandover(day,
                ShiftCode.AFTERNOON, "Fine", "Full crew", "Nothing", " ", List.of())),
                "The one thing the next shift must do is the field that matters most");

        long id = handovers.submit(sheet(ShiftCode.AFTERNOON, List.of()));
        var written = handovers.find(day, ShiftCode.AFTERNOON).orElseThrow();
        assertEquals(id, written.id());
        assertEquals(manager.id(), written.outgoingBy());
        assertFalse(written.acknowledged(), "Nobody has picked it up in the same second they wrote it");
    }

    @Test
    void theMustKnowLineIsOneSentenceAndNotAnEssay() {
        assertThrows(IllegalArgumentException.class, () -> handovers.submit(new NewHandover(
                BusinessClock.today(), ShiftCode.AFTERNOON, "Fine", "Full crew", "Nothing",
                "x".repeat(400), List.of())),
                "A handover is read in ten minutes at a shift change");
    }

    @Test
    void aShiftThatHasNotBeenWorkedYetCannotBeHandedOver() {
        assertThrows(IllegalArgumentException.class, () -> handovers.submit(new NewHandover(
                BusinessClock.today().plusDays(1), ShiftCode.MORNING, "Fine", "Full crew", "Nothing",
                "Check it", List.of())));
        assertThrows(IllegalArgumentException.class, () -> handovers.submit(new NewHandover(
                BusinessClock.today(), ShiftCode.OFF, "Fine", "Full crew", "Nothing", "Check it",
                List.of())), "A day off has nothing to hand over");
    }

    @Test
    void oneHandoverPerShiftAndNoMore() {
        handovers.submit(sheet(ShiftCode.AFTERNOON, List.of()));

        assertThrows(IllegalStateException.class, () -> handovers.submit(sheet(ShiftCode.AFTERNOON,
                List.of())), "Two sheets for one shift is two accounts of the same hours");
        // A different shift on the same day is a different handover, though.
        assertNotNull(handovers.submit(sheet(ShiftCode.MORNING, List.of())));
    }

    @Test
    void theTableItselfRefusesASecondSheetForTheSameShift() {
        // The service checks first and says so in words; the table refuses anyway, because two accounts of
        // the same hours is the one thing this record cannot survive.
        handovers.submit(sheet(ShiftCode.AFTERNOON, List.of()));
        var figures = hu.due.rewear.dao.ShiftHandoverDao.ShiftFigures.none();
        assertThrows(RuntimeException.class, () -> ctx.getDatabaseManager().runInTransaction(conn ->
                ctx.getShiftHandoverDao().insert(conn, BusinessClock.today(), ShiftCode.AFTERNOON,
                        manager.id(), "Fine", "Full crew", "Nothing", "Check it", figures,
                        BusinessClock.now())));
    }

    // ------------------------------------------------------------------ the frozen figures

    @Test
    void theFiguresAreFrozenAtCloseAndDoNotMoveAfterwards() {
        sortSomething("HO-FREEZE", "300");

        long id = handovers.submit(sheet(ShiftCode.AFTERNOON, List.of()));
        var atClose = handovers.find(BusinessClock.today(), ShiftCode.AFTERNOON).orElseThrow();
        assertEquals(300_000L, atClose.processedGrams(), "What the shift actually put through");
        assertEquals(40_000L, atClose.outputGrams());

        // More work is entered after the handover was written, as it always is.
        sortSomething("HO-FREEZE-2", "200");

        var later = handovers.find(BusinessClock.today(), ShiftCode.AFTERNOON).orElseThrow();
        assertEquals(id, later.id());
        assertEquals(300_000L, later.processedGrams(),
                "A handover is a document of what was said at that moment; recomputing it later rewrites it");
    }

    @Test
    void theFiguresCoverTheShiftsOwnHoursAndNotTheWholeDay() {
        sortSomething("HO-SHIFT", "250");

        long morning = handovers.submit(sheet(ShiftCode.MORNING, List.of()));
        long afternoon = handovers.submit(sheet(ShiftCode.AFTERNOON, List.of()));

        assertEquals(0, handovers.find(BusinessClock.today(), ShiftCode.MORNING).orElseThrow()
                .processedGrams(), "Nothing was finished between six and two");
        assertEquals(250_000L, handovers.find(BusinessClock.today(), ShiftCode.AFTERNOON).orElseThrow()
                .processedGrams(), "It was finished at seven in the evening, which is the afternoon shift");
        assertTrue(morning != afternoon);
    }

    @Test
    void theShiftsMainCauseOfStoppageIsTheOneThatCostTheMost() {
        var downtime = ctx.getDowntimeService();
        long scale = downtime.reasons(true).stream().filter(r -> r.code().equals("EQP-SCALE")).findFirst()
                .orElseThrow().id();
        long wait = downtime.reasons(true).stream().filter(r -> r.code().equals("SUP-WAIT")).findFirst()
                .orElseThrow().id();
        var day = BusinessClock.today();
        downtime.log(null, null, null, day.atTime(15, 0), day.atTime(15, 20), wait, null);
        downtime.log(null, null, null, day.atTime(16, 0), day.atTime(16, 50), scale, null);

        handovers.submit(sheet(ShiftCode.AFTERNOON, List.of()));

        var written = handovers.find(day, ShiftCode.AFTERNOON).orElseThrow();
        assertEquals(70, written.downtimeMinutes(), "Both stoppages, because both are in these hours");
        assertEquals("EQP-SCALE", written.primaryCauseCode(),
                "One cause, the biggest: a sheet read in ten minutes cannot carry a list");
    }

    // ------------------------------------------------------------------ issues

    @Test
    void everyIssueCarriesANameAndADate() {
        var day = BusinessClock.today();
        assertThrows(IllegalArgumentException.class, () -> handovers.submit(sheet(ShiftCode.AFTERNOON,
                List.of(new NewIssue("Printer paper", 9_999L, day.plusDays(1))))),
                "An issue with nobody's name on it is a note");
        assertThrows(IllegalArgumentException.class, () -> handovers.submit(sheet(ShiftCode.AFTERNOON,
                List.of(new NewIssue("Printer paper", manager.id(), null)))),
                "And one with no date is a wish");
        assertThrows(IllegalArgumentException.class, () -> handovers.submit(sheet(ShiftCode.AFTERNOON,
                List.of(new NewIssue("Printer paper", manager.id(), day.minusDays(2))))),
                "Wanted before the shift it came out of is not a date anybody can work to");
        assertThrows(IllegalArgumentException.class, () -> handovers.submit(sheet(ShiftCode.AFTERNOON,
                List.of(new NewIssue("  ", manager.id(), day.plusDays(1))))));

        handovers.submit(sheet(ShiftCode.AFTERNOON,
                List.of(new NewIssue("Printer paper", manager.id(), day.plusDays(2)))));
        var issue = handovers.openIssues().get(0);
        assertEquals(manager.id(), issue.ownerId());
        assertEquals(day.plusDays(2), issue.dueOn());
        assertTrue(issue.open());
    }

    @Test
    void anIssueIsClosedWithWhatWasDoneAboutIt() {
        handovers.submit(sheet(ShiftCode.AFTERNOON,
                List.of(new NewIssue("Printer paper", manager.id(), BusinessClock.today().plusDays(1)))));
        long issueId = handovers.openIssues().get(0).id();

        assertThrows(IllegalArgumentException.class, () -> handovers.resolveIssue(issueId, "  "),
                "Closing an issue without saying what happened is how it comes back next week");

        handovers.resolveIssue(issueId, "Ordered, arrived the next morning");
        assertTrue(handovers.openIssues().isEmpty());
        assertThrows(IllegalStateException.class,
                () -> handovers.resolveIssue(issueId, "Again"), "An issue closes once");
    }

    @Test
    void anIssueLeftOpenForTwoDaysGoesUpATierWithoutAnybodyPushingIt() {
        // The demo has a production lead of its own now, and that is the tier an issue goes up to.
        var lead = ctx.getUserDao().findByUsername("prodlead").orElseThrow();
        handovers.submit(sheet(ShiftCode.AFTERNOON,
                List.of(new NewIssue("Forklift fork is bent", manager.id(),
                        BusinessClock.today().plusDays(1)))));
        long issueId = handovers.openIssues().get(0).id();

        assertEquals(0, handovers.escalateStaleIssues(), "Nothing has waited yet");

        // Two days later, with nobody having closed it.
        BusinessClock.useForTesting(java.time.Clock.fixed(
                LocalDate.of(2026, 11, 28).atTime(19, 1).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));

        assertEquals(1, handovers.escalateStaleIssues());
        var escalated = handovers.openIssues().stream().filter(i -> i.id() == issueId).findFirst()
                .orElseThrow();
        assertTrue(escalated.escalated());
        assertEquals(lead.id(), escalated.escalatedTo(), "It goes to the tier above the shift");
        assertTrue(ctx.getNotificationService().listForUser(lead.id(), 20).stream()
                .anyMatch(n -> n.message().contains("Forklift fork is bent")),
                "The point of the rule is that the person who raised it does not have to chase it");

        assertEquals(0, handovers.escalateStaleIssues(), "It goes up once, not every time anybody looks");
    }

    @Test
    void anIssueSomebodyClosedIsNeverEscalated() {
        handovers.submit(sheet(ShiftCode.AFTERNOON,
                List.of(new NewIssue("Scale drifting", manager.id(), BusinessClock.today().plusDays(1)))));
        handovers.resolveIssue(handovers.openIssues().get(0).id(), "Recalibrated");

        BusinessClock.useForTesting(java.time.Clock.fixed(
                LocalDate.of(2026, 12, 5).atTime(10, 0).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));

        assertEquals(0, handovers.escalateStaleIssues());
    }

    @Test
    void theEscalationRuleAndTheWriteThatCarriesItBothRefuseWhatTheyShould() {
        // The service reads only open issues and only unescalated ones, so these two guards sit behind that
        // filter. They are checked here directly, because a guard nothing exercises is a guard nobody can
        // rely on the day a second caller appears.
        handovers.submit(sheet(ShiftCode.AFTERNOON,
                List.of(new NewIssue("Scale drifting", manager.id(), BusinessClock.today().plusDays(1)))));
        var issue = handovers.openIssues().get(0);
        var longAfter = BusinessClock.now().plusDays(5);

        assertTrue(issue.dueForEscalation(longAfter));
        handovers.resolveIssue(issue.id(), "Recalibrated");
        var closed = ctx.getShiftHandoverDao().findIssue(issue.id()).orElseThrow();
        assertFalse(closed.dueForEscalation(longAfter),
                "Something somebody has already dealt with is not escalated to their manager");

        // And the write itself only ever marks an issue once.
        var now = BusinessClock.now();
        int marked = ctx.getDatabaseManager().executeInTransaction(conn ->
                ctx.getShiftHandoverDao().markEscalated(conn, issue.id(), manager.id(), now));
        assertEquals(0, marked, "A closed issue cannot be escalated at all");
    }

    @Test
    void anIssueGoesUpOnlyOnceEvenIfSomethingAsksTwice() {
        handovers.submit(sheet(ShiftCode.AFTERNOON,
                List.of(new NewIssue("Fork bent", manager.id(), BusinessClock.today().plusDays(1)))));
        long issueId = handovers.openIssues().get(0).id();
        var now = BusinessClock.now();

        int first = ctx.getDatabaseManager().executeInTransaction(conn ->
                ctx.getShiftHandoverDao().markEscalated(conn, issueId, manager.id(), now));
        int second = ctx.getDatabaseManager().executeInTransaction(conn ->
                ctx.getShiftHandoverDao().markEscalated(conn, issueId, manager.id(), now));
        assertEquals(1, first);
        assertEquals(0, second,
                "Escalating twice would tell the tier above the same thing twice and move the clock on it");
    }

    @Test
    void anIssueCanBeAddedAfterTheSheetIsWrittenBecauseSomebodyAlwaysRemembersOneMore() {
        long id = handovers.submit(sheet(ShiftCode.AFTERNOON, List.of()));

        handovers.addIssue(id, new NewIssue("Spare scanner battery", manager.id(),
                BusinessClock.today().plusDays(3)));

        assertEquals(1, handovers.find(BusinessClock.today(), ShiftCode.AFTERNOON).orElseThrow()
                .issues().size());
        assertThrows(IllegalArgumentException.class, () -> handovers.addIssue(id,
                new NewIssue("No owner", 9_999L, BusinessClock.today().plusDays(1))));
    }

    // ------------------------------------------------------------------ picking it up

    @Test
    void theScreenOpensOnTheLastShiftRatherThanTheOneBeingWorked() {
        long afternoon = handovers.submit(sheet(ShiftCode.AFTERNOON, List.of()));
        // An hour later, catching up on the morning's paperwork: typed later, earlier shift.
        pinClockAt(20, 0);
        handovers.submit(sheet(ShiftCode.MORNING, List.of()));

        assertEquals(afternoon, handovers.latest().orElseThrow().id(),
                "The board has to be current before the next shift's meeting, so the handover is a closing"
                        + " task and the last shift is what the incoming supervisor reads first");
    }

    @Test
    void aHandoverIsPickedUpOnceAndTheRecordSaysByWhom() {
        long id = handovers.submit(sheet(ShiftCode.AFTERNOON, List.of()));
        var incoming = withRole("shiftlead-test", RoleCode.SHIFT_LEAD);

        ctx.getSession().login(incoming);
        handovers.acknowledge(id);

        var picked = handovers.latest().orElseThrow();
        assertTrue(picked.acknowledged());
        assertEquals(incoming.id(), picked.acknowledgedBy());
        assertNotNull(picked.acknowledgedAt());
        assertThrows(IllegalStateException.class, () -> handovers.acknowledge(id),
                "A handover nobody acknowledged did not happen; one acknowledged twice did not happen twice");
    }

    @Test
    void beforeSixInTheMorningTheShiftBeingWrittenUpIsLastNights() {
        var earlyMorning = LocalDate.of(2026, 11, 26).atTime(5, 40);
        var slot = ShiftHandoverService.currentSlot(earlyMorning);

        assertEquals(ShiftCode.NIGHT, slot.shift());
        assertEquals(LocalDate.of(2026, 11, 25), slot.workDay(),
                "The night shift belongs to the day it started, which is the one case a date picker gets"
                        + " wrong every time");
        assertEquals(ShiftCode.MORNING, ShiftHandoverService.currentSlot(
                LocalDate.of(2026, 11, 26).atTime(9, 0)).shift());
        assertEquals(ShiftCode.AFTERNOON, ShiftHandoverService.currentSlot(
                LocalDate.of(2026, 11, 26).atTime(15, 0)).shift());
        assertEquals(ShiftCode.NIGHT, ShiftHandoverService.currentSlot(
                LocalDate.of(2026, 11, 26).atTime(23, 0)).shift());
    }

    // ------------------------------------------------------------------ who may do what

    @Test
    void handingAShiftOverIsTheShiftLeadersJob() {
        var shiftLead = withRole("shiftlead-test2", RoleCode.SHIFT_LEAD);
        ctx.getSession().login(shiftLead);
        long id = handovers.submit(sheet(ShiftCode.AFTERNOON, List.of()));
        assertNotNull(handovers.latest().orElseThrow());

        ctx.getSession().login(sorter);
        assertThrows(IllegalStateException.class, () -> handovers.submit(sheet(ShiftCode.MORNING, List.of())),
                "A sorter does not hand the shift over");
        assertThrows(IllegalStateException.class, () -> handovers.acknowledge(id));
        assertThrows(IllegalStateException.class, () -> handovers.latest(),
                "Nor read what the leaders wrote to each other");
    }

    // ------------------------------------------------------------------ the demo

    @Test
    void theDemoOpensWithAWeekOfHandoversAndOneWaitingToBePickedUp() {
        I18nManager i18n = I18nManager.getInstance();
        var fresh = new AppContext(new DatabaseManager(tempDir.resolve("demo-handover.db")),
                new AppPreferences(tempDir.resolve("demo-handover.properties")), i18n);
        var leader = fresh.getUserDao().findByUsername("shiftlead").orElseThrow();
        fresh.getSession().login(fresh.getUserDao().findByUsername("manager").orElseThrow());
        var service = fresh.getShiftHandoverService();

        var recent = service.recent(20);
        assertTrue(recent.size() >= 4, "A week of shifts, not one sheet");
        assertEquals(leader.id(), recent.get(0).outgoingBy(), "Written by the shift leader, who now exists");
        assertFalse(recent.get(0).acknowledged(),
                "The newest is the one the person opening the demo is taking over from");
        assertTrue(recent.stream().anyMatch(ShiftHandoverLike::picked), "The older ones were picked up");
        assertFalse(service.latest().orElseThrow().mustKnow().isBlank());

        var open = service.openIssues();
        assertFalse(open.isEmpty(), "A week with nothing left open is not a week");
        assertTrue(open.stream().anyMatch(HandoverIssue::escalated),
                "One has been waiting long enough that the application pushed it up at startup");
        assertTrue(open.stream().allMatch(issue -> issue.ownerName() != null),
                "Every one of them has somebody's name on it");
    }

    /** Lets the demo test read "was it picked up" without importing the whole model. */
    private interface ShiftHandoverLike {
        static boolean picked(hu.due.rewear.model.ShiftHandover handover) {
            return handover.acknowledged();
        }
    }
}
