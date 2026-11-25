package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CollectionChannel;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.LotLegalStatus;
import hu.due.rewear.dao.OrderDemandDao.Product;
import hu.due.rewear.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provenance has to survive the whole journey, or it is not provenance.
 *
 * <p>A package leaving on a customer order is several steps away from the bale it was sorted out of, and each
 * step is a different table. This walks the real chain — purchase lot, raw lot, request, bag, package, order
 * — and asks at the end where the goods came from. It is also where the mixed-consignment check earns its
 * keep: mixing collection channels destroys the classification of a consignment, and that is the sort of
 * thing somebody else discovers at a border.
 */
class ProvenanceChainTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private PurchaseLotService lots;
    private SortingService sorting;
    private OrderService orders;
    private User manager;
    private User sorter;
    private User supply;
    private User helper;
    private User station;

    @BeforeEach
    void setUp() {
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(new DatabaseManager(tempDir.resolve("trace.db")),
                new AppPreferences(tempDir.resolve("trace.properties")), i18n);
        lots = ctx.getPurchaseLotService();
        sorting = ctx.getSortingService();
        orders = ctx.getOrderService();
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        sorter = ctx.getUserDao().findByUsername("sorter01").orElseThrow();
        supply = ctx.getUserDao().findByUsername("forklift").orElseThrow();
        helper = ctx.getUserDao().findByUsername("helper01").orElseThrow();
        station = ctx.getUserDao().findByUsername("operator").orElseThrow();
        ctx.getSession().login(manager);
    }

    /**
     * Takes one purchase lot all the way to a package in stock and returns the package's id.
     *
     * @param gradeCode which quality grade the sorter reports, so two runs can produce different products
     */
    private long sortOnePackage(String reference, CollectionChannel channel, String gradeCode) {
        ctx.getSession().login(manager);
        long purchase = lots.record(new PurchaseLotService.NewLot(reference, null, "HU", channel,
                "6309 00 00", "Textile waste from the population", new BigDecimal("0.53"), "EUR",
                new BigDecimal("1100"), new BigDecimal("1000"), null, null, LocalDate.now()));
        lots.assess(purchase, LotLegalStatus.WASTE, "Separately collected", "Dunaújváros");
        long rawLot = sorting.receive("RAW-" + reference, "Vegyes használt ruha", null,
                new BigDecimal("900"), purchase);
        long shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0).id();
        sorting.storeLot(rawLot, shelf);

        ctx.getSession().login(sorter);
        long request = sorting.requestSupply(rawLot);
        ctx.getSession().login(supply);
        sorting.deliver(request, new BigDecimal("400"));

        var dict = ctx.getDictionaryDao();
        var target = dict.findTargetGroups(true).stream().filter(t -> t.code().equals("B")).findFirst().orElseThrow();
        var garment = dict.findGarmentTypes(true).stream().filter(t -> t.code().equals("MIX")).findFirst().orElseThrow();
        var grade = dict.findQualityGrades(true).stream().filter(t -> t.code().equals(gradeCode)).findFirst().orElseThrow();
        var packaging = dict.findPackageTypes(true).stream().filter(t -> t.code().equals("B")).findFirst().orElseThrow();

        ctx.getSession().login(sorter);
        long bag = sorting.reportBag(request, target.id(), garment.id(), grade.id(), new BigDecimal("100"));
        sorting.finish(request, new BigDecimal("400"));
        ctx.getSession().login(helper);
        sorting.pack(bag, packaging.id());
        ctx.getSession().login(station);
        var pkg = sorting.registerBag(bag);
        ctx.getSession().login(manager);
        ctx.getTransportService().putawayToStorage(List.of(pkg.id()), shelf, supply.id());
        return pkg.id();
    }

    private long orderFor(String gradeCode, int packages) {
        var dict = ctx.getDictionaryDao();
        var buyer = ctx.getCustomerDao().findBuyers(true).get(0);
        var dock = ctx.getLocationDao().findByType(LocationType.DISPATCH_ZONE, true).get(0);
        var fulfilment = ctx.getUserDao().findByUsername("forklift2").orElseThrow();
        var target = dict.findTargetGroups(true).stream().filter(t -> t.code().equals("B")).findFirst().orElseThrow();
        var garment = dict.findGarmentTypes(true).stream().filter(t -> t.code().equals("MIX")).findFirst().orElseThrow();
        var grade = dict.findQualityGrades(true).stream().filter(t -> t.code().equals(gradeCode)).findFirst().orElseThrow();
        var packaging = dict.findPackageTypes(true).stream().filter(t -> t.code().equals("B")).findFirst().orElseThrow();
        var order = orders.createScheduledOrder(buyer.id(), "EUR", sorting.today().plusDays(1), manager.id());
        orders.setRequestedProduct(order.id(),
                new Product(target.id(), garment.id(), grade.id(), packaging.id()), packages);
        orders.assignForkliftAndDock(order.id(), fulfilment.id(), dock.id());
        orders.allocateRequestedProducts(order.id(), manager.id());
        return order.id();
    }

    @Test
    void aPackageOnAnOrderCanBeTracedBackToTheBaleItWasSortedFrom() {
        sortOnePackage("PL-TRACE", CollectionChannel.IMPORT, "CR");
        long order = orderFor("CR", 1);

        List<PurchaseLotService.Provenance> trace = lots.traceOrder(order);

        assertEquals(1, trace.size());
        PurchaseLotService.Provenance line = trace.get(0);
        assertTrue(line.isKnown(), "The whole point is that this is answerable");
        assertEquals("PL-TRACE", line.purchaseLotReference());
        assertEquals("RAW-PL-TRACE", line.rawLotReference());
        assertEquals("HU", line.originCountry());
        assertEquals(CollectionChannel.IMPORT, line.channel());
        assertEquals(LotLegalStatus.WASTE, line.legalStatus());
        assertFalse(lots.isMixedConsignment(order), "One channel throughout is not a mixed consignment");
    }

    @Test
    void anOrderDrawingOnTwoChannelsIsFlaggedAsMixed() {
        sortOnePackage("PL-MIX-A", CollectionChannel.IMPORT, "CR");
        sortOnePackage("PL-MIX-B", CollectionChannel.HOUSEHOLD_COLLECTION, "CR");
        long order = orderFor("CR", 2);

        assertEquals(2, lots.traceOrder(order).size());
        assertEquals(java.util.Set.of(CollectionChannel.IMPORT, CollectionChannel.HOUSEHOLD_COLLECTION),
                lots.channelsOnOrder(order));
        assertTrue(lots.isMixedConsignment(order),
                "Mixing destroys the classification of the consignment, so it has to be visible beforehand");
    }

    @Test
    void materialWithNoPurchaseLotMakesAConsignmentUntraceableAndSaysSo() {
        // Received the old way, with a typed area and no lot behind it.
        ctx.getSession().login(manager);
        long rawLot = sorting.receive("RAW-LEGACY", "Vegyes", "Budapest", new BigDecimal("900"));
        long shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0).id();
        sorting.storeLot(rawLot, shelf);
        ctx.getSession().login(sorter);
        long request = sorting.requestSupply(rawLot);
        ctx.getSession().login(supply);
        sorting.deliver(request, new BigDecimal("400"));
        var dict = ctx.getDictionaryDao();
        var target = dict.findTargetGroups(true).stream().filter(t -> t.code().equals("B")).findFirst().orElseThrow();
        var garment = dict.findGarmentTypes(true).stream().filter(t -> t.code().equals("MIX")).findFirst().orElseThrow();
        var grade = dict.findQualityGrades(true).stream().filter(t -> t.code().equals("CR")).findFirst().orElseThrow();
        var packaging = dict.findPackageTypes(true).stream().filter(t -> t.code().equals("B")).findFirst().orElseThrow();
        ctx.getSession().login(sorter);
        long bag = sorting.reportBag(request, target.id(), garment.id(), grade.id(), new BigDecimal("100"));
        sorting.finish(request, new BigDecimal("400"));
        ctx.getSession().login(helper);
        sorting.pack(bag, packaging.id());
        ctx.getSession().login(station);
        var pkg = sorting.registerBag(bag);
        ctx.getSession().login(manager);
        ctx.getTransportService().putawayToStorage(List.of(pkg.id()), shelf, supply.id());

        long order = orderFor("CR", 1);
        PurchaseLotService.Provenance line = lots.traceOrder(order).get(0);

        assertFalse(line.isKnown(), "A package with no lot behind it has no provenance to report");
        assertEquals(null, line.channel());
        assertTrue(lots.isMixedConsignment(order),
                "Unknown provenance is as much of a problem for a consignment as mixed provenance");
    }
}
