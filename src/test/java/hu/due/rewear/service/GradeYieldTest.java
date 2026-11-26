package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.GradeYieldDao.Scope;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CollectionChannel;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.LotLegalStatus;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grade yield is the measurement the business actually runs on: the premium grade is about a tenth of the
 * weight and over half of the revenue, so a point of mix is worth more than a point of throughput.
 *
 * <p>These tests pin two things in particular. The cascade is a share of what went <em>in</em>, so the
 * residue is a line like any other — a mix that counts only what could be sold flatters itself. And the
 * application ships no target: the only figure variance is measured against is one captured from this site's
 * own history by a named person.
 */
class GradeYieldTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private GradeYieldService yields;
    private SortingService sorting;
    private PurchaseLotService lots;
    private User manager;
    private User sorter;
    private User supply;
    private long shelf;

    @BeforeEach
    void setUp() {
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(new DatabaseManager(tempDir.resolve("yield.db")),
                new AppPreferences(tempDir.resolve("yield.properties")), i18n);
        yields = ctx.getGradeYieldService();
        sorting = ctx.getSortingService();
        lots = ctx.getPurchaseLotService();
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        sorter = ctx.getUserDao().findByUsername("sorter01").orElseThrow();
        supply = ctx.getUserDao().findByUsername("forklift").orElseThrow();
        shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0).id();
        ctx.getSession().login(manager);
        // These tests measure the arithmetic of runs they make themselves, so the line starts empty.
        hu.due.rewear.TestWorkspaces.clearSeededSortingHistory(ctx);
    }


    /**
     * Sorts one load and reports the given grade split.
     *
     * @param split pairs of grade code and kilograms reported into it
     * @return the purchase lot the material came from
     */
    private long sortRun(String reference, String processedKg, String... split) {
        ctx.getSession().login(manager);
        long purchase = lots.record(new PurchaseLotService.NewLot(reference, null, "HU",
                CollectionChannel.COMMERCIAL_PURCHASE, null, null, new BigDecimal("0.50"), "EUR",
                new BigDecimal("2200"), new BigDecimal("2000"), null, null, LocalDate.now()));
        lots.assess(purchase, LotLegalStatus.WASTE, "Bought in", "Dunaújváros");
        long rawLot = sorting.receive("RAW-" + reference, "Vegyes", null, new BigDecimal("1500"), purchase);
        sorting.storeLot(rawLot, shelf);

        ctx.getSession().login(sorter);
        long request = sorting.requestSupply(rawLot);
        ctx.getSession().login(supply);
        sorting.deliver(request, new BigDecimal(processedKg));

        var dict = ctx.getDictionaryDao();
        var target = dict.findTargetGroups(true).stream().filter(t -> t.code().equals("B")).findFirst().orElseThrow();
        var garment = dict.findGarmentTypes(true).stream().filter(t -> t.code().equals("MIX")).findFirst().orElseThrow();
        ctx.getSession().login(sorter);
        for (int i = 0; i < split.length; i += 2) {
            String code = split[i];
            var grade = dict.findQualityGrades(true).stream()
                    .filter(g -> g.code().equals(code)).findFirst().orElseThrow();
            sorting.reportBag(request, target.id(), garment.id(), grade.id(), new BigDecimal(split[i + 1]));
        }
        sorting.finish(request, new BigDecimal(processedKg));
        ctx.getSession().login(manager);
        return purchase;
    }

    // ------------------------------------------------------------------ the cascade

    @Test
    void theCascadeIsAShareOfWhatWentInAndTheResidueIsALineInIt() {
        sortRun("YL-1", "1000", "CR", "100", "A", "400");

        var report = yields.report(Scope.all());

        assertEquals(1_000_000L, report.processedGrams());
        assertEquals(500_000L, report.outputGrams());
        var cream = report.lines().stream().filter(l -> "CR".equals(l.code())).findFirst().orElseThrow();
        assertEquals(1000, cream.shareBasisPoints(), "100 kg out of 1000 is 10%");
        assertEquals(0, new BigDecimal("10.00").compareTo(cream.sharePercent()));

        var residue = report.lines().stream().filter(l -> l.isResidue()).findFirst().orElseThrow();
        assertEquals(500_000L, residue.grams(), "Half went in and came out as nothing");
        assertEquals(5000, residue.shareBasisPoints());
        assertEquals(0, new BigDecimal("50.00").compareTo(report.recoveryPercent()));

        int total = report.lines().stream().mapToInt(l -> l.shareBasisPoints()).sum();
        assertEquals(10_000, total, "The cascade has to add up to the whole input");
    }

    @Test
    void theSharesStillAddUpWhenTheyDoNotDivideEvenly() {
        // Three equal thirds cannot be expressed exactly in basis points; the residue absorbs the remainder
        // rather than any share being rounded up past what was actually sorted.
        sortRun("YL-ODD", "300", "CR", "100", "A", "100", "B", "100");

        var report = yields.report(Scope.all());

        assertEquals(10_000, report.lines().stream().mapToInt(l -> l.shareBasisPoints()).sum());
        for (var line : report.lines()) {
            if (!line.isResidue()) {
                assertEquals(3333, line.shareBasisPoints(), line.code() + " should be floored, not rounded up");
            }
        }
        assertEquals(1, report.lines().stream().filter(l -> l.isResidue()).findFirst()
                .orElseThrow().shareBasisPoints(), "The leftover basis point lands in the residue");
    }

    @Test
    void aShareIsFlooredEvenWhenItWouldRoundUp() {
        // Two hundred of three hundred is 66.6667%, whose fourth digit rounds up. Flooring keeps every share
        // at or below what was really sorted, so the shares can never add up to more than the whole and the
        // residue never has to be negative to make the arithmetic close.
        sortRun("YL-ROUND", "300", "CR", "200");

        var report = yields.report(Scope.all());
        var cream = report.lines().stream().filter(l -> "CR".equals(l.code())).findFirst().orElseThrow();

        assertEquals(6666, cream.shareBasisPoints(), "6666, not 6667: a share is never rounded up");
        assertEquals(10_000, report.lines().stream().mapToInt(l -> l.shareBasisPoints()).sum());
    }

    @Test
    void outputIsValuedAtTheSamePricesTheRestOfTheApplicationUses() {
        sortRun("YL-VAL", "1000", "CR", "100");

        var report = yields.report(Scope.all());
        var cream = report.lines().stream().filter(l -> "CR".equals(l.code())).findFirst().orElseThrow();
        var grade = ctx.getDictionaryDao().findQualityGrades(true).stream()
                .filter(g -> g.code().equals("CR")).findFirst().orElseThrow();
        var garment = ctx.getDictionaryDao().findGarmentTypes(true).stream()
                .filter(g -> g.code().equals("MIX")).findFirst().orElseThrow();
        // The product's own price wins where one is set, exactly as stock valuation resolves it.
        BigDecimal expected = (garment.priceEurPerKg().signum() > 0
                ? garment.priceEurPerKg() : grade.defaultPricePerKg()).multiply(new BigDecimal("100"));

        assertEquals(0, expected.compareTo(cream.valueEur()));
        assertEquals(0, cream.valueEur().compareTo(report.valueEur()), "One grade, so it is the whole value");
        assertTrue(report.revenuePerProcessedKgEur().signum() > 0,
                "A purchase price is judged against what a processed kilogram earns");
    }

    @Test
    void aScopeNarrowsToOneLotOneSorterOrOnePeriod() {
        long first = sortRun("YL-S1", "1000", "CR", "200");
        sortRun("YL-S2", "1000", "B", "500");

        var all = yields.report(Scope.all());
        assertEquals(2_000_000L, all.processedGrams());

        var byLot = yields.report(new Scope(first, null, null, null, null, null));
        assertEquals(1_000_000L, byLot.processedGrams());
        assertEquals(2000, byLot.lines().stream().filter(l -> "CR".equals(l.code()))
                .findFirst().orElseThrow().shareBasisPoints());
        assertTrue(byLot.lines().stream().noneMatch(l -> "B".equals(l.code())),
                "The other lot's output does not belong to this one");

        var bySorter = yields.report(new Scope(null, null, null, sorter.id(), null, null));
        assertEquals(2_000_000L, bySorter.processedGrams(), "One sorter did all of it");

        var future = yields.report(Scope.all()
                .withPeriod(BusinessClock.today().plusDays(1), BusinessClock.today().plusDays(2)));
        assertTrue(future.isEmpty());
        assertEquals(List_of_empty(), future.lines(), "Nothing sorted means no cascade, not a cascade of zeroes");
    }

    private static java.util.List<GradeYieldService.Line> List_of_empty() {
        return java.util.List.of();
    }

    @Test
    void workStillOnTheBenchIsNotCountedAsYield() {
        ctx.getSession().login(manager);
        long purchase = lots.record(new PurchaseLotService.NewLot("YL-OPEN", null, "HU",
                CollectionChannel.IMPORT, null, null, BigDecimal.ONE, "EUR",
                new BigDecimal("1100"), new BigDecimal("1000"), null, null, LocalDate.now()));
        lots.assess(purchase, LotLegalStatus.WASTE, "Imported", "Dunaújváros");
        long rawLot = sorting.receive("RAW-YL-OPEN", "Vegyes", null, new BigDecimal("900"), purchase);
        sorting.storeLot(rawLot, shelf);
        ctx.getSession().login(sorter);
        long request = sorting.requestSupply(rawLot);
        ctx.getSession().login(supply);
        sorting.deliver(request, new BigDecimal("500"));
        var dict = ctx.getDictionaryDao();
        var target = dict.findTargetGroups(true).get(0);
        var garment = dict.findGarmentTypes(true).get(0);
        var grade = dict.findQualityGrades(true).get(0);
        ctx.getSession().login(sorter);
        sorting.reportBag(request, target.id(), garment.id(), grade.id(), new BigDecimal("50"));
        // Not finished: there is output but no settled input figure yet.

        ctx.getSession().login(manager);
        assertTrue(yields.report(Scope.all()).isEmpty(),
                "Counting an unfinished request would show output produced from no input at all");
    }

    @Test
    void outputFromWorkStillOnTheBenchDoesNotInflateAFinishedRunsMix() {
        // One finished run, and a second still in progress whose bags are reported but whose input is not
        // settled. Counting the open run's output against the finished run's input is how a line comes to
        // look as though it sorted more premium grade than it was ever given material for.
        long purchase = sortRun("YL-OPEN2", "1000", "CR", "100");
        ctx.getSession().login(sorter);
        long rawLot = sorting.workspace(sorting.today()).lots().stream()
                .filter(row -> "RAW-YL-OPEN2".equals(row.reference())).findFirst().orElseThrow().id();
        long second = sorting.requestSupply(rawLot);
        ctx.getSession().login(supply);
        sorting.deliver(second, new BigDecimal("200"));
        var dict = ctx.getDictionaryDao();
        var target = dict.findTargetGroups(true).stream().filter(t -> t.code().equals("B")).findFirst().orElseThrow();
        var garment = dict.findGarmentTypes(true).stream().filter(t -> t.code().equals("MIX")).findFirst().orElseThrow();
        var cream = dict.findQualityGrades(true).stream().filter(g -> g.code().equals("CR")).findFirst().orElseThrow();
        ctx.getSession().login(sorter);
        sorting.reportBag(second, target.id(), garment.id(), cream.id(), new BigDecimal("50"));

        ctx.getSession().login(manager);
        var report = yields.report(new Scope(purchase, null, null, null, null, null));

        assertEquals(1_000_000L, report.processedGrams(), "Only the finished run has a settled input");
        assertEquals(100_000L, report.outputGrams(), "The open run's bags are not part of the mix yet");
        assertEquals(1000, report.lines().stream().filter(l -> "CR".equals(l.code()))
                .findFirst().orElseThrow().shareBasisPoints(), "10%, not the 15% the open bags would suggest");
    }

    // ------------------------------------------------------------------ margin

    @Test
    void aLotsMarginComparesWhatItEarnedWithWhatTheSortedPartOfItCost() {
        long lot = sortRun("YL-M", "1000", "CR", "100", "A", "300");

        var margin = yields.marginForPurchaseLot(lot);

        assertEquals("EUR", margin.currency());
        assertEquals(0, new BigDecimal("1000.000").compareTo(margin.processedKg()));
        // Only the sorted part is costed: 1000 kg at €0.50, not the whole 2000 kg lot.
        assertEquals(0, new BigDecimal("500.00").compareTo(margin.cost()));
        assertEquals(0, margin.revenue().subtract(margin.cost()).compareTo(margin.margin()));
        assertNotNull(margin.marginPercent());
        assertEquals(0, margin.yield().processedKg().compareTo(margin.processedKg()));
    }

    @Test
    void aLotThatCostNothingHasNoMarginPercentageRatherThanAnInfiniteOne() {
        ctx.getSession().login(manager);
        long purchase = lots.record(new PurchaseLotService.NewLot("YL-FREE", null, "HU",
                CollectionChannel.OWN_COLLECTION, null, null, BigDecimal.ZERO, "EUR",
                new BigDecimal("1100"), new BigDecimal("1000"), null, null, LocalDate.now()));
        lots.assess(purchase, LotLegalStatus.WASTE, "Own boxes", "Dunaújváros");

        var margin = yields.marginForPurchaseLot(purchase);

        assertEquals(0, BigDecimal.ZERO.compareTo(margin.cost()));
        assertNull(margin.marginPercent(), "Dividing by a cost of nothing is not a number anybody can act on");
    }

    // ------------------------------------------------------------------ the baseline

    @Test
    void noBaselineShipsWithTheApplication() {
        sortRun("YL-B0", "1000", "CR", "100");

        var report = yields.report(Scope.all());

        assertNull(report.baselineName(), "A target taken from somebody else's material is not a target");
        assertTrue(yields.baselines().isEmpty());
        for (var line : report.lines()) {
            assertNull(line.baselineBasisPoints());
            assertNull(line.variancePoints());
        }
    }

    @Test
    void aBaselineIsLearnedFromTheSitesOwnHistoryAndVarianceIsMeasuredAgainstIt() {
        sortRun("YL-B1", "1000", "CR", "100", "A", "400");
        LocalDate today = BusinessClock.today();

        long baselineId = yields.captureBaseline("Autumn line", today.minusDays(7), today, "First full month", true);

        var baseline = yields.activeBaseline().orElseThrow();
        assertEquals(baselineId, baseline.id());
        assertEquals(manager.id(), baseline.capturedBy(), "A baseline is somebody's decision");
        assertEquals(1_000_000L, baseline.processedGrams());
        assertEquals(1000, baseline.shareOf(creamId()), "10% cream is what this line actually did");

        // A later run does better on cream and the variance says so.
        sortRun("YL-B2", "1000", "CR", "150", "A", "350");
        var recent = yields.report(new Scope(null, null, null, null, today, today));
        var cream = recent.lines().stream().filter(l -> "CR".equals(l.code())).findFirst().orElseThrow();

        assertEquals("Autumn line", recent.baselineName());
        assertNotNull(cream.baselineBasisPoints());
        // Both runs are in the period, so cream is 250 kg of 2000 kg: 12.5% against a 10% baseline.
        assertEquals(1250, cream.shareBasisPoints());
        assertEquals(0, new BigDecimal("2.50").compareTo(cream.variancePoints()),
                "Two and a half points better than this line's own history");
    }

    @Test
    void onlyOneBaselineIsEverTheOneBeingMeasuredAgainst() {
        sortRun("YL-B3", "1000", "CR", "100");
        LocalDate today = BusinessClock.today();
        long first = yields.captureBaseline("First", today.minusDays(7), today, null, true);
        long second = yields.captureBaseline("Second", today.minusDays(7), today, null, true);

        assertEquals(second, yields.activeBaseline().orElseThrow().id());
        yields.activateBaseline(first);
        assertEquals(first, yields.activeBaseline().orElseThrow().id());
        assertEquals(1, yields.baselines().stream().filter(b -> b.active()).count(),
                "Variance against two different figures at once is not variance");
    }

    @Test
    void aBaselineNeedsAPeriodWithWorkInItAndCannotBeTakenFromTheFuture() {
        LocalDate today = BusinessClock.today();
        assertThrows(IllegalArgumentException.class,
                () -> yields.captureBaseline("Empty", today.minusDays(7), today, null, true),
                "Nothing was sorted, so there is no mix to learn from");
        sortRun("YL-B4", "1000", "CR", "100");
        assertThrows(IllegalArgumentException.class,
                () -> yields.captureBaseline("Ahead", today, today.plusDays(3), null, true));
        assertThrows(IllegalArgumentException.class,
                () -> yields.captureBaseline("  ", today.minusDays(7), today, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> yields.captureBaseline("Backwards", today, today.minusDays(3), null, true));
    }

    @Test
    void readingYieldAndSettingTheBaselineAreDifferentPermissions() {
        sortRun("YL-R", "1000", "CR", "100");
        LocalDate today = BusinessClock.today();

        ctx.getSession().login(sorter);
        assertThrows(IllegalStateException.class, () -> yields.report(Scope.all()),
                "A sorter's own figures are the daily board, not the line's commercial mix");

        ctx.getSession().login(shiftLead());
        assertFalse(yields.report(Scope.all()).isEmpty(), "A shift leader reads the mix of their own shift");
        assertThrows(IllegalStateException.class,
                () -> yields.captureBaseline("Shift lead's go", today.minusDays(7), today, null, true),
                "What everybody is measured against is the production lead's call");
    }

    /**
     * The demo workspace seeds no shift leader, so the test makes one. That absence is itself worth noting:
     * the role exists in the hierarchy but nobody holds it in the demo data.
     */
    private User shiftLead() {
        return ctx.getUserDao().findByUsername("shiftlead").orElseGet(() -> {
            long roleId = ctx.getRoleDao().findByCode(hu.due.rewear.model.RoleCode.SHIFT_LEAD)
                    .orElseThrow().id();
            ctx.getUserDao().insert("shiftlead", org.mindrot.jbcrypt.BCrypt.hashpw("x", org.mindrot.jbcrypt.BCrypt.gensalt()),
                    "Teszt Műszakvezető", roleId, true);
            return ctx.getUserDao().findByUsername("shiftlead").orElseThrow();
        });
    }

    private long creamId() {
        return ctx.getDictionaryDao().findQualityGrades(true).stream()
                .filter(g -> g.code().equals("CR")).findFirst().orElseThrow().id();
    }
}
