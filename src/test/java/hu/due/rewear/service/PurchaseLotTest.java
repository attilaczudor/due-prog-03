package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CollectionChannel;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.LotLegalStatus;
import hu.due.rewear.model.PurchaseLot;
import hu.due.rewear.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The purchase lot is where provenance starts.
 *
 * <p>The rule these tests protect hardest is the legal one: separately collected used textiles are waste from
 * the moment of collection unless the end user handed them over directly and somebody professionally assessed
 * them as fit for re-use at the collection point. It decides what may be shipped where, so it is a rule the
 * application enforces rather than a field somebody fills in.
 */
class PurchaseLotTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private PurchaseLotService lots;
    private SortingService sorting;
    private User manager;

    @BeforeEach
    void setUp() {
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(new DatabaseManager(tempDir.resolve("lots.db")),
                new AppPreferences(tempDir.resolve("lots.properties")), i18n);
        lots = ctx.getPurchaseLotService();
        sorting = ctx.getSortingService();
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        ctx.getSession().login(manager);
    }

    private PurchaseLotService.NewLot entry(String reference, CollectionChannel channel,
                                            String gross, String net, String price) {
        return new PurchaseLotService.NewLot(reference, null, "hu", channel, "6309 00 00",
                "Textile waste from the population", new BigDecimal(price), "EUR",
                new BigDecimal(gross), new BigDecimal(net), null, null, LocalDate.now());
    }

    // ------------------------------------------------------------------ recording

    @Test
    void aLotRecordsWhatWasBoughtAndWhatItCost() {
        long id = lots.record(entry("PL-1", CollectionChannel.COMMERCIAL_PURCHASE, "10400", "10000", "0.53"));

        PurchaseLot lot = lots.find(id).orElseThrow();
        assertEquals("HU", lot.originCountry(), "The country is normalised to an upper-case code");
        assertEquals(new BigDecimal("400"), lot.tareWeightKg(), "Gross less net is packaging");
        // 10 000 kg at €0.53 is €5 300, and it is the net weight that is paid for: the pallet is not cloth.
        assertEquals(new BigDecimal("5300.00"), lot.totalCost());
        assertEquals(LotLegalStatus.UNASSESSED, lot.legalStatus(), "Nobody has judged it yet");
        assertFalse(lot.isAssessed());
        assertEquals(1, lots.history(id).size(), "Receiving the lot is itself part of its history");
    }

    @Test
    void theWeightsAndTheReferenceHaveToMakeSense() {
        assertThrows(IllegalArgumentException.class,
                () -> lots.record(entry("PL-BAD", CollectionChannel.IMPORT, "900", "1000", "0.50")),
                "Net above gross would mean the packaging weighed less than nothing");
        assertThrows(IllegalArgumentException.class,
                () -> lots.record(entry("PL-BAD", CollectionChannel.IMPORT, "1000", "900", "-1")),
                "A negative purchase price is not a discount");
        lots.record(entry("PL-DUP", CollectionChannel.IMPORT, "1000", "900", "0.50"));
        assertThrows(IllegalArgumentException.class,
                () -> lots.record(entry("PL-DUP", CollectionChannel.IMPORT, "500", "400", "0.50")),
                "Two lots under one reference would make provenance ambiguous");
    }

    @Test
    void theCountryOfOriginIsACodeAndNotProse() {
        assertThrows(IllegalArgumentException.class, () -> lots.record(new PurchaseLotService.NewLot(
                "PL-C", null, "Hungary", CollectionChannel.IMPORT, null, null, BigDecimal.ONE, "EUR",
                new BigDecimal("100"), new BigDecimal("90"), null, null, LocalDate.now())));
    }

    // ------------------------------------------------------------------ the legal judgement

    @Test
    void onlyMaterialHandedOverDirectlyCanBeAssessedFitForReuse() {
        long collected = lots.record(entry("PL-HH", CollectionChannel.HOUSEHOLD_COLLECTION, "100", "95", "0.50"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> lots.assess(collected, LotLegalStatus.FIT_FOR_REUSE, "Looks wearable", "Dunaújváros"));
        assertTrue(refused.getMessage().contains("waste from the moment"),
                "The message must say why, not merely refuse: " + refused.getMessage());

        // The same material can of course be recorded as what it is.
        lots.assess(collected, LotLegalStatus.WASTE, "Separately collected from drop-off boxes", "Dunaújváros");
        assertEquals(LotLegalStatus.WASTE, lots.find(collected).orElseThrow().legalStatus());
    }

    @Test
    void aDirectHandoverCanBeAssessedAndTheAssessmentNamesWhoWhereAndWhy() {
        long handed = lots.record(entry("PL-DH", CollectionChannel.DIRECT_HANDOVER, "100", "95", "0.50"));

        lots.assess(handed, LotLegalStatus.FIT_FOR_REUSE, "Sorted and checked with the donor present",
                "Dunaújváros collection point");

        PurchaseLot lot = lots.find(handed).orElseThrow();
        assertEquals(LotLegalStatus.FIT_FOR_REUSE, lot.legalStatus());
        assertEquals(manager.id(), lot.assessedBy(), "An assessment belongs to a person");
        assertEquals("Dunaújváros collection point", lot.assessmentPlace());
        assertTrue(lot.assessmentBasis().startsWith("Sorted and checked"));
        assertTrue(lot.isAssessed());
        assertTrue(lots.history(handed).stream().anyMatch(e -> "ASSESSED".equals(e.eventType())),
                "A judgement that can be revised without trace is not evidence");
    }

    @Test
    void anAssessmentWithoutAPlaceOrAReasonIsNotAnAssessment() {
        long handed = lots.record(entry("PL-DH2", CollectionChannel.DIRECT_HANDOVER, "100", "95", "0.50"));

        assertThrows(IllegalArgumentException.class,
                () -> lots.assess(handed, LotLegalStatus.FIT_FOR_REUSE, "  ", "Dunaújváros"));
        assertThrows(IllegalArgumentException.class,
                () -> lots.assess(handed, LotLegalStatus.FIT_FOR_REUSE, "Checked", "   "));
        assertThrows(IllegalArgumentException.class,
                () -> lots.assess(handed, LotLegalStatus.UNASSESSED, "Checked", "Dunaújváros"),
                "An assessment has to reach a conclusion");
    }

    @Test
    void theChannelCannotBeChangedOutFromUnderAnAssessmentThatDependedOnIt() {
        long handed = lots.record(entry("PL-DH3", CollectionChannel.DIRECT_HANDOVER, "100", "95", "0.50"));
        lots.assess(handed, LotLegalStatus.FIT_FOR_REUSE, "Checked with the donor", "Dunaújváros");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> lots.update(handed, entry("PL-DH3", CollectionChannel.IMPORT, "100", "95", "0.50")));
        assertTrue(refused.getMessage().contains("Re-assess"),
                "The message must say what to do about it: " + refused.getMessage());
        assertEquals(CollectionChannel.DIRECT_HANDOVER,
                lots.find(handed).orElseThrow().collectionChannel());
    }

    // ------------------------------------------------------------------ into the warehouse

    @Test
    void materialIsTakenIntoStockAgainstItsLotAndTheAreaStopsBeingFreeText() {
        long id = lots.record(entry("PL-IN", CollectionChannel.IMPORT, "5200", "5000", "0.60"));
        lots.assess(id, LotLegalStatus.WASTE, "Imported baled textiles", "Dunaújváros");

        long rawLot = sorting.receive("RAW-IN-1", "Vegyes használt ruha", null, new BigDecimal("2000"), id);

        var received = sorting.workspace(sorting.today()).lots().stream()
                .filter(row -> row.id() == rawLot).findFirst().orElseThrow();
        assertEquals("HU · IMPORT", received.sourceArea(),
                "The area is read from the lot rather than typed, so it cannot disagree with it");
        assertEquals(new BigDecimal("3000.000"), lots.remainingKg(id));
        assertTrue(lots.history(id).stream().anyMatch(e -> "TAKEN_INTO_STOCK".equals(e.eventType())));
    }

    @Test
    void unassessedMaterialCannotBeTakenIntoSorting() {
        long id = lots.record(entry("PL-UN", CollectionChannel.IMPORT, "1000", "900", "0.60"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> sorting.receive("RAW-UN-1", "Vegyes", null, new BigDecimal("100"), id));
        assertTrue(refused.getMessage().contains("has not been assessed"),
                "What the material legally is gets decided where it was collected, not afterwards");
    }

    @Test
    void moreCannotBeTakenOutOfALotThanWasBought() {
        long id = lots.record(entry("PL-OVER", CollectionChannel.IMPORT, "1100", "1000", "0.60"));
        lots.assess(id, LotLegalStatus.WASTE, "Imported", "Dunaújváros");
        sorting.receive("RAW-OVER-1", "Vegyes", null, new BigDecimal("800"), id);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> sorting.receive("RAW-OVER-2", "Vegyes", null, new BigDecimal("300"), id));
        assertTrue(refused.getMessage().contains("has left"), refused.getMessage());
        // What does fit is still accepted.
        sorting.receive("RAW-OVER-2", "Vegyes", null, new BigDecimal("200"), id);
        assertEquals(0, lots.remainingKg(id).compareTo(BigDecimal.ZERO));
    }

    @Test
    void aLotWithNothingLeftIsNoLongerOffered() {
        long id = lots.record(entry("PL-EMPTY", CollectionChannel.IMPORT, "110", "100", "0.60"));
        lots.assess(id, LotLegalStatus.WASTE, "Imported", "Dunaújváros");
        assertTrue(lots.openLots().stream().anyMatch(row -> row.id() == id));

        sorting.receive("RAW-EMPTY-1", "Vegyes", null, new BigDecimal("100"), id);

        assertFalse(lots.openLots().stream().anyMatch(row -> row.id() == id));
    }

    @Test
    void materialReceivedWithoutALotStillWorksButCarriesNoProvenance() {
        // The application predates purchase lots and existing installations have rows without one. They are
        // shown as what they are rather than being given a lot they never came from.
        long rawLot = sorting.receive("RAW-OLD-1", "Vegyes", "Budapest és Pest megye", new BigDecimal("500"));

        var received = sorting.workspace(sorting.today()).lots().stream()
                .filter(row -> row.id() == rawLot).findFirst().orElseThrow();
        assertEquals("Budapest és Pest megye", received.sourceArea());
    }

    @Test
    void aCollectionNeedsEitherALotOrAnArea() {
        // Sorting signals a refused operation with IllegalStateException throughout; this follows its own
        // convention rather than importing the purchase lot service's.
        assertThrows(IllegalStateException.class,
                () -> sorting.receive("RAW-NONE-1", "Vegyes", "   ", new BigDecimal("100"), null));
    }

    // ------------------------------------------------------------------ provenance

    @Test
    void anOrderWithNoSortedHistoryReportsNoProvenanceRatherThanPretending() {
        assertEquals(List.of(), lots.traceOrder(999_999L));
        assertFalse(lots.isMixedConsignment(999_999L), "Nothing to mix is not a mixed consignment");
        assertEquals(Set.of(), lots.channelsOnOrder(999_999L));
    }

    @Test
    void onlyManagementRecordsLotsAndOnlyTheRightRolesAssessThem() {
        long id = lots.record(entry("PL-ROLE", CollectionChannel.DIRECT_HANDOVER, "100", "95", "0.50"));
        ctx.getSession().login(ctx.getUserDao().findByUsername("sorter01").orElseThrow());

        assertThrows(IllegalArgumentException.class,
                () -> lots.record(entry("PL-ROLE-2", CollectionChannel.IMPORT, "100", "95", "0.50")));
        assertThrows(IllegalArgumentException.class,
                () -> lots.assess(id, LotLegalStatus.WASTE, "Looks like waste", "Dunaújváros"));

        // The station manager stands where the goods are, so the assessment is theirs to make.
        ctx.getSession().login(ctx.getUserDao().findByUsername("operator").orElseThrow());
        lots.assess(id, LotLegalStatus.WASTE, "Checked at the gate", "Dunaújváros");
        assertEquals(LotLegalStatus.WASTE, lots.find(id).orElseThrow().legalStatus());
    }
}
