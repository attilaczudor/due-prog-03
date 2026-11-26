package hu.due.rewear.config;

import hu.due.rewear.dao.GradeYieldDao.Scope;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.service.SortingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo workspace opens with a week of sorting behind it, because an empty screen teaches nobody anything:
 * grade yield with no mix to report, a daily board of nothing against a target, and not one graded bag to
 * label are all screens a reader cannot judge.
 *
 * <p>What these tests pin is that the seeded week is <em>consistent</em>. Seeded data that does not add up is
 * worse than none: somebody reads a figure off a demo, finds it impossible, and stops trusting the
 * application rather than the sample.
 */
class DemoSortingHistoryTest {

    @TempDir
    Path tmp;

    private AppContext open(String database) {
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        AppContext ctx = new AppContext(new DatabaseManager(tmp.resolve(database)),
                new AppPreferences(tmp.resolve(database + ".properties")), i18n);
        ctx.getSession().login(ctx.getUserDao().findByUsername("manager").orElseThrow());
        return ctx;
    }

    private static long count(AppContext ctx, String sql) {
        try (var conn = ctx.getDatabaseManager().getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException ex) {
            throw new IllegalStateException(sql, ex);
        }
    }

    @Test
    void theWeekBehindTheDemoIsFinishedWorkAndTodayIsStillInProgress() {
        AppContext ctx = open("history.db");
        var workspace = ctx.getSortingService().workspace(ctx.getSortingService().today());

        assertFalse(workspace.requests().isEmpty(), "A demo with no sorting in it cannot show sorting");
        assertTrue(workspace.requests().stream().anyMatch(r -> r.processedGrams() != null),
                "The week behind today is finished work");
        assertTrue(workspace.requests().stream().anyMatch(SortingService.Request::processing),
                "Somebody is part-way through a load, or the screen has nothing to act on");
        assertTrue(workspace.requests().stream().anyMatch(SortingService.Request::awaitingDelivery),
                "A driver has a call waiting, or the supply queue is a blank page");
        assertTrue(workspace.requests().stream().anyMatch(r -> r.cancelledAt() != null),
                "Calls do come to nothing and the history should say so");

        assertTrue(workspace.bags().stream().anyMatch(b -> b.packedAt() != null), "Some bags are packed");
        assertTrue(workspace.bags().stream().anyMatch(b -> b.packedAt() == null),
                "The last two days are still loose, which is the helper's work");
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM sorting_bags WHERE registered_at IS NOT NULL"),
                "Registering a bag into stock is where the seeder stops and the station manager starts");
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM sorting_bags b JOIN sorting_requests r ON r.id=b.request_id"
                + " WHERE r.processed_grams IS NOT NULL AND b.weight_grams % 10 <> 0"),
                "A bag weight the QR label cannot carry is a bag the application would refuse");
    }

    @Test
    void everyGramIsStillAccountedForOnTheCollectionItCameOutOf() {
        AppContext ctx = open("accounted.db");
        // What a collection still holds, plus what was sorted out of it, plus what is on a bench right now,
        // has to be exactly what arrived. Nothing may be owed and nothing invented.
        assertEquals(0, count(ctx, """
                SELECT COUNT(*) FROM sorting_raw_lots l WHERE l.received_grams <> l.available_grams
                    + COALESCE((SELECT SUM(COALESCE(r.processed_grams, r.delivered_grams))
                                FROM sorting_requests r WHERE r.lot_id = l.id), 0)
                """), "A seeded collection that does not add up is worse than no seeded data at all");
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM sorting_raw_lots WHERE available_grams < 0"));
        assertEquals(0, count(ctx, """
                SELECT COUNT(*) FROM sorting_requests r WHERE COALESCE(
                    (SELECT SUM(b.weight_grams) FROM sorting_bags b WHERE b.request_id = r.id), 0)
                    > COALESCE(r.processed_grams, r.delivered_grams)
                """), "No run produced more in bags than it was given to sort");
    }

    @Test
    void theMixHasAResidueInItAndTheSharesAddUpToTheWholeInput() {
        AppContext ctx = open("mix.db");
        var report = ctx.getGradeYieldService().report(Scope.all());

        assertFalse(report.isEmpty());
        assertEquals(10_000, report.lines().stream().mapToInt(l -> l.shareBasisPoints()).sum());
        var residue = report.lines().stream().filter(l -> l.isResidue()).findFirst().orElseThrow();
        assertTrue(residue.grams() > 0, "Material that went in and came out as nothing is the honest line");
        assertTrue(report.recoveryPercent().compareTo(new java.math.BigDecimal("100")) < 0);
        assertTrue(report.lines().stream().filter(l -> !l.isResidue()).count() >= 4,
                "The demo shows the whole cascade, not one grade");
        assertTrue(report.valueEur().signum() > 0, "Graded output is worth something");
    }

    @Test
    void theDailyBoardIsMeasuredAgainstATargetSomebodyCouldActuallyMeet() {
        AppContext ctx = open("board.db");
        var daily = ctx.getSortingService().workspace(ctx.getSortingService().today()).daily();

        assertFalse(daily.isEmpty());
        assertTrue(daily.stream().anyMatch(d -> d.processedGrams() > 0), "Today has work on the board");
        assertTrue(daily.stream().anyMatch(d -> d.reachedTarget()), "Somebody made their day");
        assertTrue(daily.stream().noneMatch(d -> d.targetGrams() == 1_000_000L),
                "A tonne a head is the schema's placeholder, not a day's work for a hand sorter");
    }

    @Test
    void openingTheWorkspaceAgainDoesNotSortTheWeekTwice() {
        AppContext first = open("twice.db");
        long requests = count(first, "SELECT COUNT(*) FROM sorting_requests");
        long bags = count(first, "SELECT COUNT(*) FROM sorting_bags");

        AppContext second = open("twice.db");

        assertEquals(requests, count(second, "SELECT COUNT(*) FROM sorting_requests"));
        assertEquals(bags, count(second, "SELECT COUNT(*) FROM sorting_bags"));
    }

    @Test
    void aWorkspaceThatAlreadyHasSortingInItIsLeftAlone() {
        AppContext ctx = open("real.db");
        long requests = count(ctx, "SELECT COUNT(*) FROM sorting_requests");
        // Rewind the seed marker, as an older installation would have it, and ask for the upgrade again. A
        // company that has sorted anything at all must not find a seeded week mixed in with its own records.
        ctx.getDatabaseManager().runInTransaction(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE app_settings SET setting_value = '4'"
                        + " WHERE setting_key = 'demo.sorting.seeded'");
            }
        });
        new DemoDataSeeder(ctx.getDatabaseManager()).upgradeDemoWorkspace();

        assertEquals(requests, count(ctx, "SELECT COUNT(*) FROM sorting_requests"));
    }

    /** Rewinds the seed marker to before the worked week, as an older installation would have it. */
    private void rewindToBeforeTheWeek(AppContext ctx) {
        hu.due.rewear.TestWorkspaces.clearSeededSortingHistory(ctx);
        ctx.getDatabaseManager().runInTransaction(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE app_settings SET setting_value = '4'"
                        + " WHERE setting_key = 'demo.sorting.seeded'");
            }
        });
    }

    @Test
    void aCollectionTooSmallToCoverTheWeekIsNotOverdrawn() {
        AppContext ctx = open("thin.db");
        rewindToBeforeTheWeek(ctx);
        // Leave the collections with a few grams on them. Every run the week would seed is then more than the
        // material can cover, and material that is not there must not be sorted anyway.
        ctx.getDatabaseManager().runInTransaction(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE sorting_raw_lots SET available_grams = 500");
            }
        });
        new DemoDataSeeder(ctx.getDatabaseManager()).upgradeDemoWorkspace();

        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM sorting_raw_lots WHERE available_grams < 0"),
                "A demo that owes material it never received is a demo nobody can read a figure off");
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM sorting_requests WHERE delivered_grams IS NOT NULL"),
                "Nothing was delivered, because there was nothing on the shelf to deliver");
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM sorting_bags"));
    }

    @Test
    void aTargetSomebodyHasAlreadySetIsNotOverwritten() {
        AppContext ctx = open("target.db");
        rewindToBeforeTheWeek(ctx);
        ctx.getDatabaseManager().runInTransaction(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE sorting_teams SET target_grams = 94000");
            }
        });
        new DemoDataSeeder(ctx.getDatabaseManager()).upgradeDemoWorkspace();

        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM sorting_teams WHERE target_grams <> 94000"),
                "A figure somebody chose for their own line is theirs, not the seeder's to replace");
    }
}
