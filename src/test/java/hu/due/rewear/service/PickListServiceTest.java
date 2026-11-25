package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.PackageFilter;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.User;
import hu.due.rewear.model.WarehouseLocation;
import hu.due.rewear.service.PickListService.PickLine;
import hu.due.rewear.service.PickListService.PickList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pick list must put the packages in a route a person can walk, and its progress must be the real
 * state of the warehouse rather than a separate tally.
 */
class PickListServiceTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private User manager;
    private User forklift;        // supply driver: collects raw material
    private User deliveryDriver;  // delivery driver: walks the pick list for customer orders
    private WarehouseLocation dock;
    private long customerId;

    private void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("picklist.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(db, prefs, i18n);
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        forklift = ctx.getUserDao().findByUsername("forklift").orElseThrow();
        deliveryDriver = ctx.getUserDao().findByUsername("forklift2").orElseThrow();
        dock = ctx.getLocationDao().findByType(LocationType.DISPATCH_ZONE, true).get(0);
        customerId = ctx.getCustomerDao().findAll(true).get(0).id();
    }

    private WarehouseLocation shelf(String code, String zone, String aisle, String shelf) {
        long id = ctx.getLocationDao().insert(code, zone, aisle, shelf, LocationType.STORAGE, new BigDecimal("5000"));
        return ctx.getLocationDao().findById(id).orElseThrow();
    }

    /** Free demo packages, moved onto the given shelves one each, in the order given. */
    private List<InventoryPackage> placedOn(WarehouseLocation... shelves) {
        var storageIds = ctx.getLocationDao().findByType(LocationType.STORAGE, true).stream()
                .map(WarehouseLocation::id).collect(java.util.stream.Collectors.toSet());
        List<InventoryPackage> free = ctx.getPackageDao().findByFilter(PackageFilter.empty()).stream()
                .filter(p -> p.status() == PackageStatus.IN_STOCK)
                .filter(p -> storageIds.contains(p.locationId()))
                .toList();
        List<InventoryPackage> placed = new ArrayList<>();
        for (int i = 0; i < shelves.length; i++) {
            ctx.getInventoryService().relocatePackage(free.get(i).id(), shelves[i].id(), "test layout", manager.id());
            placed.add(ctx.getPackageDao().findById(free.get(i).id()).orElseThrow());
        }
        return placed;
    }

    private Order orderWith(List<InventoryPackage> packages) {
        Order order = ctx.getOrderService().createOrder(customerId, "EUR", null, manager.id());
        for (InventoryPackage p : packages) {
            ctx.getOrderService().addPackageToOrder(order.id(), p.id(), new BigDecimal("5.00"), manager.id());
        }
        return order;
    }

    private static List<String> locations(List<PickLine> lines) {
        return lines.stream().map(PickLine::locationCode).toList();
    }

    @Test
    void naturalOrderReadsNumbersByValueSoAisleTenComesAfterAisleTwo() {
        assertTrue(PickListService.naturalCompare("2", "10") < 0, "text order would put 10 first");
        assertTrue(PickListService.naturalCompare("02", "2") == 0, "leading zeros do not change the value");
        assertTrue(PickListService.naturalCompare("A", "a") == 0);
        assertTrue(PickListService.naturalCompare("A", "B") < 0);
        assertTrue(PickListService.naturalCompare("9", "A") < 0, "numbers before letters");
        assertTrue(PickListService.naturalCompare("1", "1A") < 0, "a shorter label sorts first");
        assertTrue(PickListService.naturalCompare("12", "9") > 0);
    }

    @Test
    void pendingPackagesFollowTheWalkingRouteByZoneAisleAndShelf() {
        setUp();
        // Unpadded numbers on purpose: as plain text "10" sorts before "2", so only a numeric-aware sort passes.
        WarehouseLocation a2s3 = shelf("Z-2-3", "Z", "2", "3");
        WarehouseLocation a10s1 = shelf("Z-10-1", "Z", "10", "1");
        WarehouseLocation a2s1 = shelf("Z-2-1", "Z", "2", "1");
        WarehouseLocation b1s1 = shelf("Y-01-01", "Y", "01", "01");
        // Added to the order in an order that is deliberately not the route.
        Order order = orderWith(placedOn(a10s1, a2s3, b1s1, a2s1));

        PickList list = ctx.getPickListService().forOrder(order.id());

        assertEquals(List.of("Y-01-01", "Z-2-1", "Z-2-3", "Z-10-1"), locations(list.pending()),
                "Zone first, then aisle by value (2 before 10), then shelf");
        assertEquals(4, list.total());
        assertEquals(0, list.picked());
        assertEquals(0, list.percentDone());
        assertFalse(list.isComplete());
    }

    @Test
    void progressIsTheRealStateAndStagedPackagesMoveToTheEnd() {
        setUp();
        List<InventoryPackage> packages = placedOn(
                shelf("Z-01-01", "Z", "01", "01"), shelf("Z-01-02", "Z", "01", "02"), shelf("Z-02-01", "Z", "02", "01"));
        Order order = orderWith(packages);
        BigDecimal totalKg = packages.stream().map(InventoryPackage::weightKg).reduce(BigDecimal.ZERO, BigDecimal::add);

        // The driver collects the package from the first shelf the list asked for.
        InventoryPackage first = packages.get(0);
        ctx.getTransportService().scanAndCollectForOrder(first.qrCode(), dock.id(), forklift.id(), false);

        PickList list = ctx.getPickListService().forOrder(order.id());
        assertEquals(1, list.picked());
        assertEquals(2, list.remaining());
        assertEquals(33, list.percentDone());
        assertEquals(List.of("Z-01-02", "Z-02-01"), locations(list.pending()));
        assertTrue(list.lines().get(2).picked(), "The staged package is listed last");
        assertEquals(dock.code(), list.lines().get(2).locationCode());
        assertEquals(0, totalKg.compareTo(list.totalWeightKg()));
        assertEquals(0, totalKg.subtract(first.weightKg()).compareTo(list.remainingWeightKg()));
        assertEquals(1, list.byOrder().size());
        assertEquals(1, list.byOrder().get(0).picked());

        for (InventoryPackage rest : packages.subList(1, 3)) {
            ctx.getTransportService().scanAndCollectForOrder(rest.qrCode(), dock.id(), forklift.id(), false);
        }
        PickList done = ctx.getPickListService().forOrder(order.id());
        assertTrue(done.isComplete());
        assertEquals(100, done.percentDone());
        assertTrue(done.pending().isEmpty());
        assertTrue(done.byOrder().get(0).complete());
    }

    @Test
    void severalOrdersCanBeWalkedAsOneListWhileEachKeepsItsOwnProgress() {
        setUp();
        List<InventoryPackage> six = placedOn(
                shelf("Z-01-01", "Z", "01", "01"), shelf("Z-01-02", "Z", "01", "02"), shelf("Z-01-03", "Z", "01", "03"),
                shelf("Z-02-01", "Z", "02", "01"), shelf("Z-02-02", "Z", "02", "02"), shelf("Z-03-01", "Z", "03", "01"));
        Order first = orderWith(List.of(six.get(0), six.get(3), six.get(5)));
        Order second = orderWith(List.of(six.get(1), six.get(2), six.get(4)));
        ctx.getTransportService().scanAndCollectForOrder(six.get(0).qrCode(), dock.id(), forklift.id(), false);

        PickList both = ctx.getPickListService().forOrders(List.of(first.id(), second.id()));

        assertEquals(6, both.total());
        assertEquals(List.of("Z-01-02", "Z-01-03", "Z-02-01", "Z-02-02", "Z-03-01"), locations(both.pending()),
                "One walk interleaves the two orders by location");
        var progress = both.byOrder();
        assertEquals(2, progress.size());
        assertEquals(1, progress.stream().filter(o -> o.orderNumber().equals(first.orderNumber())).findFirst().orElseThrow().picked());
        assertEquals(0, progress.stream().filter(o -> o.orderNumber().equals(second.orderNumber())).findFirst().orElseThrow().picked());

        assertEquals(3, ctx.getPickListService().forOrder(second.id()).total(), "A single-order list holds only that order");
        assertTrue(ctx.getPickListService().forOrders(null).total() >= 6, "No selection means every open order");
    }

    @Test
    void aDriverOnlySeesTheOrdersAssignedToThem() {
        setUp();
        List<InventoryPackage> four = placedOn(
                shelf("Z-01-01", "Z", "01", "01"), shelf("Z-01-02", "Z", "01", "02"));
        Order mine = orderWith(List.of(four.get(0)));
        Order notMine = orderWith(List.of(four.get(1)));
        ctx.getOrderService().assignForkliftAndDock(mine.id(), deliveryDriver.id(), dock.id());

        PickList driversList = ctx.getPickListService().forDriver(deliveryDriver.id());

        // The driver sees the orders assigned to them (the demo already gives them one) and nobody else's.
        var mineNumbers = driversList.lines().stream().map(PickLine::orderNumber).distinct().toList();
        assertTrue(mineNumbers.contains(mine.orderNumber()), "Their own order should be on the list: " + mineNumbers);
        assertFalse(driversList.lines().stream().anyMatch(l -> l.orderNumber().equals(notMine.orderNumber())),
                "An order assigned to nobody must not appear on a driver's list");
        assertTrue(ctx.getPickListService().forDriver(null).lines().stream()
                .anyMatch(l -> l.orderNumber().equals(notMine.orderNumber())), "Management sees every open order");
    }

    @Test
    void anOrderWithNothingReservedGivesAnEmptyListRatherThanAnError() {
        setUp();
        Order empty = ctx.getOrderService().createOrder(customerId, "EUR", null, manager.id());

        PickList list = ctx.getPickListService().forOrder(empty.id());

        assertTrue(list.isEmpty());
        assertEquals(0, list.percentDone());
        assertFalse(list.isComplete());
        assertEquals(0, list.totalWeightKg().signum());
    }

    @Test
    void theDocumentPaginatesPaintsAndExportsAValidPdf() throws IOException {
        setUp();
        assertEquals(1, PickListPdfExporter.pageCount(0));
        assertEquals(1, PickListPdfExporter.pageCount(20));
        assertEquals(2, PickListPdfExporter.pageCount(21));
        assertEquals(2, PickListPdfExporter.pageCount(52));
        assertEquals(3, PickListPdfExporter.pageCount(53));

        List<InventoryPackage> packages = placedOn(
                shelf("Z-01-01", "Z", "01", "01"), shelf("Z-01-02", "Z", "01", "02"), shelf("Z-02-01", "Z", "02", "01"));
        orderWith(packages);
        ctx.getTransportService().scanAndCollectForOrder(packages.get(0).qrCode(), dock.id(), forklift.id(), false);
        PickList list = ctx.getPickListService().forDriver(null);

        DocumentPageSource source = ctx.getPickListPdfExporter().createPageSource(list);
        assertTrue(source.getDefaultFileName().startsWith("pick-list-") && source.getDefaultFileName().endsWith(".pdf"));
        for (int page = 0; page < source.getPageCount(); page++) {
            BufferedImage image = new BufferedImage(595, 842, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                source.paintPage(g, page, 595f, 842f);
            } finally {
                g.dispose();
            }
        }

        Path pdf = tempDir.resolve("pick.pdf");
        ctx.getPickListPdfExporter().export(list, pdf);
        assertTrue(Files.size(pdf) > 1000);
        assertEquals("%PDF", new String(Files.readAllBytes(pdf), 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }
}
