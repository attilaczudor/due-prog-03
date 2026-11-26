package hu.due.rewear.service;

import hu.due.rewear.TestWorkspaces;
import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.WorkListDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.RotaShift.ShiftCode;
import hu.due.rewear.model.SystemTodoItem;
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
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every role gets a work list.
 *
 * <p>Five of eleven did not: the list branched on the roles the company had when it was written — drivers, a
 * station manager, an office — and the roles added afterwards fell through all of them. A sorter, a helper, a
 * production lead, a shift leader and the payroll role opened the application to an empty screen, which is a
 * defect even though nothing throws.
 *
 * <p>These tests hold two lines. Each role is told the things it alone can act on, and each is told them
 * about its own work rather than about everybody's: a sorter is not shown another bench's stoppage, and the
 * payroll role is asked about the last completed day rather than about a day still being worked.
 */
class RoleWorkListTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private TodoService todos;
    private SortingService sorting;
    private I18nManager i18n;
    private User manager;
    private User sorter;
    private User helper;
    private User supply;
    private Team team;
    private long shelf;

    @BeforeEach
    void setUp() {
        // A Thursday afternoon: the morning shift has ended, so a handover is already owed for it.
        pinClockAt(15, 30);
        i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(new DatabaseManager(tempDir.resolve("worklist.db")),
                new AppPreferences(tempDir.resolve("worklist.properties")), i18n);
        todos = ctx.getTodoService();
        sorting = ctx.getSortingService();
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        sorter = ctx.getUserDao().findByUsername("sorter01").orElseThrow();
        supply = ctx.getUserDao().findByUsername("forklift").orElseThrow();
        shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0).id();
        ctx.getSession().login(manager);
        // These lists are read against work these tests create, so the seeded week is cleared first.
        TestWorkspaces.clearSeededSortingHistory(ctx);
        team = sorting.workspace(sorting.today()).teams().stream()
                .filter(row -> row.sorterIds().contains(sorter.id())).findFirst().orElseThrow();
        helper = ctx.getUserDao().findById(team.helperIds().get(0)).orElseThrow();
    }

    private static void pinClockAt(int hour, int minute) {
        BusinessClock.useForTesting(Clock.fixed(
                LocalDate.of(2026, 11, 26).atTime(hour, minute).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));
    }

    @AfterEach
    void tearDown() {
        BusinessClock.resetForTesting();
    }

    // ------------------------------------------------------------------ helpers

    private List<SystemTodoItem> listFor(User user) {
        return todos.buildSystemTodosFor(user, i18n);
    }

    private static boolean mentions(List<SystemTodoItem> items, String fragment) {
        return items.stream().anyMatch(item -> item.summary().contains(fragment)
                || (item.detail() != null && item.detail().contains(fragment)));
    }

    private static Optional<SystemTodoItem> inCategory(List<SystemTodoItem> items, String category) {
        return items.stream().filter(item -> category.equals(item.category())).findFirst();
    }

    /** A stored collection with stock on it, ready for somebody to call for. */
    private long storedLot(String reference, String kg) {
        ctx.getSession().login(manager);
        long lot = sorting.receive(reference, "Vegyes", "Dunaújváros", new BigDecimal(kg));
        sorting.storeLot(lot, shelf);
        return lot;
    }

    /** A call for material that nobody has answered. */
    private long openCall(User who, long lot) {
        ctx.getSession().login(who);
        long request = sorting.requestSupply(lot);
        ctx.getSession().login(manager);
        return request;
    }

    /** One finished run, so that a day has a figure on it. */
    private void finishedRun(User who, long lot, String kg, String gradeCode, String gradeKg) {
        long request = openCall(who, lot);
        ctx.getSession().login(supply);
        sorting.deliver(request, new BigDecimal(kg));
        var dict = ctx.getDictionaryDao();
        long target = dict.findTargetGroups(true).get(0).id();
        long garment = dict.findGarmentTypes(true).stream().filter(g -> g.code().equals("MIX"))
                .findFirst().orElseThrow().id();
        long grade = dict.findQualityGrades(true).stream().filter(g -> g.code().equals(gradeCode))
                .findFirst().orElseThrow().id();
        ctx.getSession().login(who);
        sorting.reportBag(request, target, garment, grade, new BigDecimal(gradeKg));
        sorting.finish(request, new BigDecimal(kg));
        ctx.getSession().login(manager);
    }

    /** Makes the day's finished runs yesterday's, which is the day the payroll role is asked about. */
    private void moveSortedDaysBackOneDay() {
        ctx.getDatabaseManager().runInTransaction(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE sorting_requests SET work_day = date(work_day, '-1 day') "
                        + "WHERE work_day IS NOT NULL");
            }
        });
    }

    private User withRole(String username, RoleCode role) {
        return ctx.getUserDao().findByUsername(username).orElseGet(() -> {
            long roleId = ctx.getRoleDao().findByCode(role).orElseThrow().id();
            ctx.getUserDao().insert(username, BCrypt.hashpw("x", BCrypt.gensalt(4)),
                    "Teszt " + role.name(), roleId, true);
            return ctx.getUserDao().findByUsername(username).orElseThrow();
        });
    }

    // ------------------------------------------------------- every role, and resolved text

    @Test
    void everyRoleGetsAListAndEveryLineOfItIsRealText() {
        storedLot("RAW-WL-ALL", "900");
        openCall(sorter, storedLot("RAW-WL-ALL2", "900"));
        i18n.forgetMissingKeys();

        for (RoleCode role : RoleCode.values()) {
            User user = withRole("wl_" + role.name().toLowerCase(java.util.Locale.ROOT), role);
            List<SystemTodoItem> items = listFor(user);
            assertNotNull(items, "Every role gets a list, even if it is empty: " + role);
            for (SystemTodoItem item : items) {
                assertFalse(item.summary() == null || item.summary().isBlank(),
                        role + " has an item with nothing written on it");
                assertFalse(item.summary().contains("[") || (item.detail() != null && item.detail().contains("[")),
                        role + " has an item showing a raw translation key: " + item.summary()
                                + " / " + item.detail());
                assertNotNull(item.targetView(), role + " has an item that leads nowhere");
            }
        }
        assertTrue(i18n.missingKeys().isEmpty(),
                "A work list asked for keys that do not exist: " + i18n.missingKeys());
    }

    @Test
    void theFiveRolesThatUsedToSeeNothingNowSeeTheirOwnWork() {
        long lot = storedLot("RAW-WL-FIVE", "900");
        openCall(sorter, lot);
        finishedRun(sorter, lot, "100", "A", "40");
        moveSortedDaysBackOneDay();
        finishedRun(sorter, lot, "100", "A", "40");

        assertFalse(listFor(sorter).isEmpty(), "A sorter opens the application to their own bench");
        assertFalse(listFor(helper).isEmpty(), "A helper is on the runs they worked");
        assertFalse(listFor(withRole("prodlead2", RoleCode.PRODUCTION_LEAD)).isEmpty(),
                "A production lead answers for the floor");
        assertFalse(listFor(withRole("shiftlead2", RoleCode.SHIFT_LEAD)).isEmpty(),
                "A shift leader owes a handover for the shift that ended");
        assertFalse(listFor(withRole("payroll2", RoleCode.HR_PAYROLL)).isEmpty(),
                "The payroll role is told what it cannot compute");
    }

    @Test
    void theDemoHasSomebodyToRunThePayrollAs() {
        User payroll = ctx.getUserDao().findByUsername("payroll").orElseThrow();
        assertEquals(RoleCode.HR_PAYROLL, payroll.roleCode(),
                "A role with a menu and no account cannot be shown to anybody");
        assertTrue(payroll.active());
    }

    // ------------------------------------------------------------------ the bench

    @Test
    void aSorterIsToldHowLongTheyHaveBeenWaitingForMaterial() {
        long lot = storedLot("RAW-WL-WAIT", "900");
        pinClockAt(14, 0);
        openCall(sorter, lot);
        pinClockAt(14, 25);

        List<SystemTodoItem> items = listFor(sorter);
        assertTrue(mentions(items, "25"), "Twenty-five minutes of standing still is the whole point: " + items);
        assertEquals("P1", inCategory(items, i18n.t("todo.cat.own_bench")).orElseThrow().priorityBadge());
    }

    @Test
    void anotherSortersCallIsNotThisSortersProblem() {
        User other = ctx.getUserDao().findAll().stream()
                .filter(u -> u.roleCode() == RoleCode.SORTER && u.id() != sorter.id())
                .findFirst().orElseThrow();
        openCall(other, storedLot("RAW-WL-OTHER", "900"));

        assertFalse(mentions(listFor(sorter), i18n.t("todo.sys.own_waiting_detail")),
                "A sorter waiting at their own bench is not every sorter's item");
        assertTrue(mentions(listFor(other), i18n.t("todo.sys.own_waiting_detail")));
    }

    @Test
    void aStoppageAgainstTheTeamReachesEverySorterOnIt() {
        ctx.getSession().login(manager);
        ctx.getDowntimeService().open(team.id(), null, null, BusinessClock.now().minusMinutes(20), null, null);
        var elsewhere = sorting.workspace(sorting.today()).teams().stream()
                .filter(row -> row.id() != team.id()).findFirst().orElseThrow();
        User farAway = ctx.getUserDao().findAll().stream()
                .filter(u -> elsewhere.sorterIds().contains(u.id())).findFirst().orElseThrow();

        assertTrue(inCategory(listFor(sorter), i18n.t("todo.cat.stoppage")).isPresent(),
                "A stoppage with nobody named on it covers the team");
        assertTrue(inCategory(listFor(farAway), i18n.t("todo.cat.stoppage")).isEmpty(),
                "Another bench's stoppage is another bench's item");
    }

    @Test
    void anOpenStoppageWithNoReasonYetStillSaysSo() {
        ctx.getDowntimeService().open(team.id(), null, null, BusinessClock.now().minusMinutes(10), null, null);

        SystemTodoItem item = inCategory(listFor(sorter), i18n.t("todo.cat.stoppage")).orElseThrow();
        assertTrue(item.detail().contains(i18n.t("todo.sys.no_reason")),
                "Why it stopped is not always known in the first minute, and the item must not pretend it is");
    }

    @Test
    void theProgressLineIsFlooredSoAlmostFinishedNeverReadsAsFinished() {
        long lot = storedLot("RAW-WL-FLOOR", "900");
        // The team target is a round number of kilograms; one gram short of it must not read as 100%.
        long targetGrams = team.targetGrams();
        BigDecimal justShort = BigDecimal.valueOf(targetGrams - 1, 3);
        finishedRun(sorter, lot, justShort.toPlainString(), "A", "10");

        List<SystemTodoItem> items = listFor(sorter);
        assertTrue(mentions(items, "99%"), "One gram short of target is 99%, not 100%: " + items);
    }

    @Test
    void aHelperIsNotMeasuredAgainstSomebodyElsesTarget() {
        long lot = storedLot("RAW-WL-HELP", "900");
        finishedRun(sorter, lot, "120", "A", "40");

        List<SystemTodoItem> items = listFor(helper);
        assertTrue(mentions(items, i18n.t("todo.sys.own_helped_detail")),
                "A helper is told what they worked: " + items);
        assertFalse(mentions(items, i18n.t("todo.sys.own_target_detail")),
                "A helper has no target of their own, so they are not scored against one");
    }

    @Test
    void aRunLeftOpenOnTheBenchIsTheItemThatMattersMost() {
        long lot = storedLot("RAW-WL-BENCH", "900");
        long request = openCall(sorter, lot);
        ctx.getSession().login(supply);
        sorting.deliver(request, new BigDecimal("150"));
        ctx.getSession().login(manager);

        List<SystemTodoItem> items = listFor(sorter);
        assertTrue(mentions(items, i18n.t("todo.sys.own_bench_open_detail")),
                "The input weight is what the day is measured from, and only the sorter knows it");
        assertFalse(mentions(items, i18n.t("todo.sys.own_waiting_detail")),
                "Material that has arrived is no longer being waited for");
        assertFalse(mentions(listFor(helper), i18n.t("todo.sys.own_bench_open_detail")),
                "The helper is on the same run and cannot close it, so it is not their item");
    }

    @Test
    void aBagWaitingToBePackedIsTheHelpersItem() {
        long lot = storedLot("RAW-WL-BAG", "900");
        finishedRun(sorter, lot, "100", "A", "40");

        assertTrue(mentions(listFor(helper), i18n.t("todo.sys.own_unpacked_detail")),
                "Packing is the helper's job, and a bag is not stock until it is done");
        assertFalse(mentions(listFor(sorter), i18n.t("todo.sys.own_unpacked_detail")),
                "Telling a sorter about work they cannot do is how a list starts being ignored");
    }

    // ------------------------------------------------------------------ the supply driver

    @Test
    void theSupplyDriverIsToldWhoIsStandingAtAnEmptyBench() {
        long lot = storedLot("RAW-WL-SUP", "900");
        pinClockAt(14, 0);
        openCall(sorter, lot);
        pinClockAt(14, 40);

        List<SystemTodoItem> items = listFor(supply);
        SystemTodoItem call = inCategory(items, i18n.t("todo.cat.supply")).orElseThrow();
        assertEquals("P1", call.priorityBadge(), "A sorter with nothing to sort is the first thing on the list");
        assertTrue(call.summary().contains(sorter.displayName()), "By name: " + call.summary());
        assertTrue(call.summary().contains("40"), "And for how long: " + call.summary());
        assertTrue(call.detail().contains("RAW-WL-SUP"), "And which collection: " + call.detail());
    }

    @Test
    void anAnsweredCallLeavesNobodyWaiting() {
        long lot = storedLot("RAW-WL-ANS", "900");
        long request = openCall(sorter, lot);
        assertTrue(mentions(listFor(supply), sorter.displayName()),
                "Somebody is waiting, and the driver is told who");

        ctx.getSession().login(supply);
        sorting.deliver(request, new BigDecimal("200"));
        ctx.getSession().login(manager);

        assertFalse(mentions(listFor(supply), sorter.displayName()),
                "Material that has been delivered is nobody's outstanding job");
    }

    @Test
    void aCollectionNobodyStoredIsTheDriversJobToo() {
        ctx.getSession().login(manager);
        sorting.receive("RAW-WL-UNSTORED", "Pamut", "Dunaújváros", new BigDecimal("700"));

        List<SystemTodoItem> items = listFor(supply);
        assertTrue(mentions(items, "RAW-WL-UNSTORED"),
                "A collection without a location cannot be called for by anybody: " + items);
    }

    @Test
    void theDeliveryDriverIsNotGivenTheSortingFloorsWork() {
        openCall(sorter, storedLot("RAW-WL-DEL", "900"));
        User delivery = ctx.getUserDao().findByUsername("forklift2").orElseThrow();

        assertTrue(inCategory(listFor(delivery), i18n.t("todo.cat.supply")).isEmpty(),
                "The delivery driver serves customer orders, not the benches");
    }

    // ------------------------------------------------------------------ the leaders

    @Test
    void theShiftThatEndedIsTheShiftAHandoverIsOwedFor() {
        User lead = withRole("shiftlead3", RoleCode.SHIFT_LEAD);

        SystemTodoItem item = inCategory(listFor(lead), i18n.t("todo.cat.handover")).orElseThrow();
        assertEquals("P1", item.priorityBadge());
        assertTrue(item.summary().contains(i18n.t("rota.shift.MORNING")),
                "At half past three the morning shift is the one that has ended: " + item.summary());
    }

    @Test
    void aHandoverNobodyPickedUpBecomesTheNextItemAndThenGoesAway() {
        User lead = withRole("shiftlead4", RoleCode.SHIFT_LEAD);
        ctx.getSession().login(lead);
        var slot = ShiftHandoverService.previousSlot(
                ShiftHandoverService.currentSlot(BusinessClock.now()));
        long id = ctx.getShiftHandoverService().submit(new ShiftHandoverService.NewHandover(
                slot.workDay(), slot.shift(), "A 2-es mérleg hitelesítése lejár",
                "Négy fő, egy beteg", "Semmi", "Indítás előtt a 2-es mérleget cseréld le", List.of()));
        ctx.getSession().login(manager);

        List<SystemTodoItem> written = listFor(lead);
        assertFalse(mentions(written, i18n.t("todo.sys.handover_missing_detail")),
                "The sheet exists now");
        assertTrue(mentions(written, lead.displayName()),
                "What is left is that nobody has picked it up: " + written);

        User incoming = withRole("shiftlead5", RoleCode.SHIFT_LEAD);
        ctx.getSession().login(incoming);
        ctx.getShiftHandoverService().acknowledge(id);
        ctx.getSession().login(manager);

        assertTrue(inCategory(listFor(incoming), i18n.t("todo.cat.handover")).isEmpty(),
                "A handover written and picked up is finished business");
    }

    @Test
    void anIssueLeftWithYouOutranksOneLeftWithSomebodyElse() {
        User lead = withRole("shiftlead6", RoleCode.SHIFT_LEAD);
        User other = withRole("shiftlead7", RoleCode.SHIFT_LEAD);
        ctx.getSession().login(lead);
        var slot = ShiftHandoverService.previousSlot(
                ShiftHandoverService.currentSlot(BusinessClock.now()));
        ctx.getShiftHandoverService().submit(new ShiftHandoverService.NewHandover(
                slot.workDay(), slot.shift(), "Rendben", "Teljes létszám", "Semmi",
                "A 3-as padot takarítsd ki", List.of(
                new ShiftHandoverService.NewIssue("A 2-es mérleg hitelesítése lejár", lead.id(),
                        BusinessClock.today().plusDays(3)),
                new ShiftHandoverService.NewIssue("A szalag leállt kétszer", other.id(),
                        BusinessClock.today().plusDays(3)))));
        ctx.getSession().login(manager);

        List<SystemTodoItem> mine = listFor(lead).stream()
                .filter(item -> i18n.t("todo.cat.handover").equals(item.category()))
                .filter(item -> item.summary().contains("mérleg") || item.summary().contains("szalag"))
                .toList();
        assertEquals(2, mine.size(), "Both issues are the floor's business: " + mine);
        SystemTodoItem own = mine.stream().filter(item -> item.summary().contains("mérleg")).findFirst().orElseThrow();
        SystemTodoItem theirs = mine.stream().filter(item -> item.summary().contains("szalag")).findFirst().orElseThrow();
        assertEquals("P1", own.priorityBadge(), "The one with your name on it is yours to do");
        assertEquals("P2", theirs.priorityBadge(), "Somebody else's is worth knowing about, not doing first");
    }

    @Test
    void anIssueThatWentUpATierIsPressingWhoeverItBelongsTo() {
        User lead = withRole("shiftlead8", RoleCode.SHIFT_LEAD);
        User owner = withRole("shiftlead9", RoleCode.SHIFT_LEAD);
        ctx.getSession().login(lead);
        var slot = ShiftHandoverService.previousSlot(
                ShiftHandoverService.currentSlot(BusinessClock.now()));
        ctx.getShiftHandoverService().submit(new ShiftHandoverService.NewHandover(
                slot.workDay(), slot.shift(), "Rendben", "Teljes létszám", "Semmi",
                "Semmi", List.of(new ShiftHandoverService.NewIssue("A hegesztés félbemaradt", owner.id(),
                BusinessClock.today().plusDays(5)))));

        // Three days later nobody has closed it, so the record escalates it by itself.
        BusinessClock.useForTesting(Clock.fixed(
                LocalDate.of(2026, 11, 29).atTime(15, 30).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));
        ctx.getShiftHandoverService().escalateStaleIssues();
        ctx.getSession().login(manager);

        SystemTodoItem item = listFor(lead).stream()
                .filter(row -> row.summary().contains("hegesztés")).findFirst().orElseThrow();
        assertEquals("P1", item.priorityBadge(),
                "An escalated issue is pressing for everybody who runs the floor, not only its owner");
        assertTrue(item.detail().contains(i18n.t("todo.sys.issue_escalated")), item.detail());
    }

    @Test
    void theLeadersAreToldAboutTheFloorsBacklogAndTheMissingBaseline() {
        long lot = storedLot("RAW-WL-LEAD", "900");
        finishedRun(sorter, lot, "100", "A", "40");
        User lead = withRole("prodlead3", RoleCode.PRODUCTION_LEAD);

        List<SystemTodoItem> items = listFor(lead);
        assertTrue(mentions(items, i18n.t("todo.sys.bag_backlog_detail")),
                "A bag halfway through the line is the floor's backlog: " + items);
        SystemTodoItem baseline = inCategory(items, i18n.t("todo.cat.yield")).orElseThrow();
        assertEquals("P4", baseline.priorityBadge(),
                "Asking for a baseline is a prompt, not an emergency");

        ctx.getSession().login(manager);
        ctx.getGradeYieldService().captureBaseline("Első alapvonal", BusinessClock.today().minusDays(6),
                BusinessClock.today(), null, true);

        assertTrue(inCategory(listFor(lead), i18n.t("todo.cat.yield")).isEmpty(),
                "Once a baseline exists there is nothing to ask for");
    }

    // ------------------------------------------------------------------ the payroll role

    @Test
    void thePayrollRoleIsAskedAboutTheLastCompletedDayNotTodaysWork() {
        long lot = storedLot("RAW-WL-PAY", "900");
        finishedRun(sorter, lot, "100", "A", "40");
        User payroll = withRole("payroll3", RoleCode.HR_PAYROLL);

        assertFalse(mentions(listFor(payroll), sorter.displayName()),
                "A day still being worked has nothing to pay yet");

        // The same work, a day earlier: now it is a day the draft is short of.
        moveSortedDaysBackOneDay();

        assertTrue(mentions(listFor(payroll), sorter.displayName()),
                "Yesterday's sorting with no payroll day against it is exactly what the draft is missing");
    }

    @Test
    void aWholeShiftMissingFromTheDraftIsOneItemWithTheNamesInIt() {
        User second = ctx.getUserDao().findAll().stream()
                .filter(u -> u.roleCode() == RoleCode.SORTER && u.id() != sorter.id())
                .findFirst().orElseThrow();
        long lot = storedLot("RAW-WL-MANY", "900");
        finishedRun(sorter, lot, "100", "A", "40");
        finishedRun(second, lot, "80", "A", "30");
        moveSortedDaysBackOneDay();
        User payroll = withRole("payroll5", RoleCode.HR_PAYROLL);

        List<SystemTodoItem> items = listFor(payroll).stream()
                .filter(item -> i18n.t("todo.cat.payroll").equals(item.category()))
                .toList();
        assertEquals(1, items.size(), "A day missing from the draft is one job, not one per person: " + items);
        assertTrue(items.get(0).summary().contains("2"), items.get(0).summary());
        assertTrue(items.get(0).detail().contains(sorter.displayName())
                        && items.get(0).detail().contains(second.displayName()),
                "The names are still there, because somebody has to be asked about them: "
                        + items.get(0).detail());
    }

    @Test
    void aPayrollDayWithNoRateBehindItCannotBeComputedAndSaysSo() {
        User payroll = withRole("payroll4", RoleCode.HR_PAYROLL);
        LocalDate yesterday = BusinessClock.today().minusDays(1);
        User office = ctx.getUserDao().findByUsername("admin").orElseThrow();
        ctx.getSession().login(office);
        ctx.getPayrollService().addDepartment("Éjszakai műszak", null);
        long department = ctx.getPayrollService().workspace(yesterday).departments().stream()
                .filter(d -> d.name().equals("Éjszakai műszak")).findFirst().orElseThrow().id();
        ctx.getPayrollService().saveShift(0, sorter.id(), yesterday, department, RoleCode.SORTER,
                new BigDecimal("100"), false);

        SystemTodoItem item = inCategory(listFor(payroll), i18n.t("todo.cat.payroll")).orElseThrow();
        assertEquals("P1", item.priorityBadge(), "A day that cannot be computed is the first thing to fix");
        assertTrue(item.summary().contains("Éjszakai műszak"), item.summary());

        // A rate that starts next week says nothing about last night.
        ctx.getPayrollService().saveRate(department, RoleCode.SORTER, BusinessClock.today().plusDays(7),
                12_000, new BigDecimal("5"));
        assertTrue(listFor(payroll).stream().anyMatch(row -> row.summary().contains("Éjszakai műszak")),
                "A rate effective later does not make an earlier day computable");

        ctx.getPayrollService().saveRate(department, RoleCode.SORTER, yesterday.minusDays(1), 12_000,
                new BigDecimal("5"));

        assertTrue(listFor(payroll).stream()
                        .noneMatch(row -> row.summary().contains("Éjszakai műszak")),
                "With a rate effective by then, the day computes");
    }

    // ------------------------------------------------------------------ the queries underneath

    @Test
    void aRoleWithNoBenchHasNoOwnShiftEvenWhileTheFloorIsBusy() {
        long lot = storedLot("RAW-WL-NONE", "900");
        finishedRun(sorter, lot, "100", "A", "40");
        openCall(sorter, lot);

        WorkListDao.OwnShift bench = ctx.getWorkListDao()
                .ownShift(sorter.id(), RoleCode.SORTER, BusinessClock.today());
        assertFalse(bench.nothingToShow(), "The sorter who did the work has a day to show");

        WorkListDao.OwnShift none = ctx.getWorkListDao()
                .ownShift(manager.id(), RoleCode.MANAGER, BusinessClock.today());
        assertTrue(none.nothingToShow(),
                "A manager does not have a bench, and is not handed somebody else's");
        assertEquals(0, none.targetGrams());
        assertEquals(0, none.processedGrams());
        assertEquals(0, none.awaitingDelivery());
    }

    @Test
    void theBagBacklogCountsBothHalvesOfTheHandling() {
        long lot = storedLot("RAW-WL-BACKLOG", "900");
        finishedRun(sorter, lot, "100", "A", "40");
        assertEquals(1, ctx.getWorkListDao().bagBacklog().unpacked());
        assertEquals(0, ctx.getWorkListDao().bagBacklog().unregistered());

        ctx.getSession().login(helper);
        long bag = sorting.workspace(sorting.today()).bags().get(0).id();
        sorting.pack(bag, ctx.getDictionaryDao().findPackageTypes(true).get(0).id());
        ctx.getSession().login(manager);

        assertEquals(0, ctx.getWorkListDao().bagBacklog().unpacked(),
                "A packed bag has left the first half");
        assertEquals(1, ctx.getWorkListDao().bagBacklog().unregistered(),
                "And is in the second until it is registered into stock");

        ctx.getSession().login(ctx.getUserDao().findByUsername("operator").orElseThrow());
        sorting.registerBag(bag);
        ctx.getSession().login(manager);

        assertTrue(ctx.getWorkListDao().bagBacklog().isEmpty(),
                "A bag that is stock is nobody's backlog any more");
    }

    @Test
    void anOpenCallIsCountedWhateverDayItWasMadeOn() {
        long lot = storedLot("RAW-WL-YDAY", "900");
        openCall(sorter, lot);
        ctx.getDatabaseManager().runInTransaction(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE sorting_requests SET requested_at = "
                        + "strftime('%Y-%m-%dT%H:%M:%SZ', requested_at, '-1 day')");
            }
        });

        assertEquals(1, ctx.getWorkListDao().openSupplyCalls().size(),
                "A request has no work day until it is finished, and a call left over from yesterday "
                        + "is the one that most needs answering");
        assertTrue(mentions(listFor(supply), sorter.displayName()));
    }
}
