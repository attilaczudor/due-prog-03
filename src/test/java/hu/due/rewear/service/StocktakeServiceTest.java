package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.MovementType;
import hu.due.rewear.model.PackageFilter;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.Stocktake.Count;
import hu.due.rewear.model.Stocktake.Outcome;
import hu.due.rewear.model.Stocktake.Session;
import hu.due.rewear.model.Stocktake.Status;
import hu.due.rewear.model.User;
import hu.due.rewear.model.WarehouseLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Counting the shelves: nothing changes while a session is open, and closing it corrects the record for
 * exactly the differences that were found, each with a movement behind it.
 */
class StocktakeServiceTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private StocktakeService stocktake;
    private User manager;
    private WarehouseLocation shelfA;
    private WarehouseLocation shelfB;

    private void setUp() {
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(new DatabaseManager(tempDir.resolve("stocktake.db")),
                new AppPreferences(tempDir.resolve("app.properties")), i18n);
        stocktake = ctx.getStocktakeService();
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        ctx.getSession().login(manager);
        List<WarehouseLocation> shelves = ctx.getLocationDao().findByType(LocationType.STORAGE, true);
        shelfA = shelves.get(0);
        shelfB = shelves.get(1);
    }

    /** A package currently recorded on {@code shelf}. */
    private InventoryPackage onShelf(WarehouseLocation shelf) {
        return ctx.getPackageDao().findByFilter(PackageFilter.empty()).stream()
                .filter(p -> p.status() == PackageStatus.IN_STOCK && p.locationId() == shelf.id())
                .findFirst().orElseThrow();
    }

    private InventoryPackage reload(long id) {
        return ctx.getPackageDao().findById(id).orElseThrow();
    }

    private List<String> movementNotes(long packageId) {
        return ctx.getMovementDao().findByPackageId(packageId).stream().map(m -> m.movementType() + ":" + m.note()).toList();
    }

    @Test
    void countingAShelfFindsWhatIsThereAndChangesNothingUntilTheSessionIsClosed() {
        setUp();
        InventoryPackage item = onShelf(shelfA);
        Session session = stocktake.open(null, shelfA.id(), "Monday count");

        assertTrue(session.open());
        assertEquals(shelfA.code(), session.scopeLocationCode());
        Count counted = stocktake.count(session.id(), item.qrCode(), item.weightKg(), false, null);

        assertEquals(Outcome.FOUND, counted.outcome());
        assertEquals(item.weightKg().stripTrailingZeros(), counted.expectedKg());
        assertEquals(0, counted.differenceGrams());
        // Nothing has changed on the package itself yet.
        assertEquals(shelfA.id(), reload(item.id()).locationId());
        assertEquals(PackageStatus.IN_STOCK, reload(item.id()).status());

        var applied = stocktake.close(session.id());
        assertEquals(0, applied.relocated());
        assertEquals(0, applied.reweighed());
        assertEquals(0, applied.quarantined());
        assertEquals(Status.CLOSED, stocktake.findSession(session.id()).orElseThrow().status());
        assertTrue(movementNotes(item.id()).stream().anyMatch(n -> n.startsWith("SCAN_VERIFY")), "The count is traceable");
    }

    @Test
    void aPackageFoundOnTheWrongShelfIsMovedToWhereItReallyIs() {
        setUp();
        InventoryPackage strayItem = onShelf(shelfB);
        Session session = stocktake.open(null, shelfA.id(), null);

        // It is scanned while counting shelf A, so that is where it physically is.
        Count counted = stocktake.count(session.id(), strayItem.qrCode(), null, false, null);

        assertEquals(Outcome.MISPLACED, counted.outcome());
        assertEquals(shelfB.code(), counted.expectedLocationCode(), "The record said shelf B");
        assertEquals(shelfA.code(), counted.foundLocationCode(), "It was really on shelf A");
        assertEquals(shelfB.id(), reload(strayItem.id()).locationId(), "Still unchanged while open");

        var applied = stocktake.close(session.id());

        assertEquals(1, applied.relocated());
        assertEquals(shelfA.id(), reload(strayItem.id()).locationId(), "The record now matches the shelf");
        assertTrue(movementNotes(strayItem.id()).stream()
                .anyMatch(n -> n.startsWith(MovementType.RELOCATE.name()) && n.contains(shelfA.code())));
    }

    @Test
    void aWeightOutsideTheToleranceIsCorrectedAndASmallDifferenceIsNot() {
        setUp();
        InventoryPackage item = onShelf(shelfA);
        BigDecimal recorded = item.weightKg();
        Session session = stocktake.open(null, shelfA.id(), null);

        // 50 g lighter: scales are never exact, so this is the same weight.
        Count close = stocktake.count(session.id(), item.qrCode(), recorded.subtract(new BigDecimal("0.050")), false, null);
        assertEquals(Outcome.FOUND, close.outcome());

        // 2 kg lighter: a real difference.
        Count off = stocktake.count(session.id(), item.qrCode(), recorded.subtract(new BigDecimal("2")), false, null);
        assertEquals(Outcome.REWEIGHED, off.outcome());
        assertEquals(-2000, off.differenceGrams());
        assertEquals(1, stocktake.counts(session.id()).size(), "Counting again replaces the earlier line");

        var applied = stocktake.close(session.id());

        assertEquals(1, applied.reweighed());
        assertEquals(0, recorded.subtract(new BigDecimal("2")).compareTo(reload(item.id()).weightKg()));
        assertTrue(movementNotes(item.id()).stream().anyMatch(n -> n.contains("reweighed")));
    }

    @Test
    void damagedStockGoesIntoQuarantineWhenTheSessionCloses() {
        setUp();
        InventoryPackage item = onShelf(shelfA);
        Session session = stocktake.open(null, shelfA.id(), null);

        Count counted = stocktake.count(session.id(), item.qrCode(), null, true, "Water damage");

        assertEquals(Outcome.DAMAGED, counted.outcome());
        assertEquals("Water damage", counted.note());
        assertEquals(PackageStatus.IN_STOCK, reload(item.id()).status(), "Still in stock while the session is open");

        var applied = stocktake.close(session.id());

        assertEquals(1, applied.quarantined());
        assertEquals(PackageStatus.QUARANTINED, reload(item.id()).status(), "Held aside, not written off");
        assertTrue(movementNotes(item.id()).stream()
                .anyMatch(n -> n.startsWith(MovementType.QUARANTINE.name()) && n.contains("Water damage")));
        assertTrue(stocktake.quarantined().stream().anyMatch(p -> p.id() == item.id()));
    }

    @Test
    void quarantinedStockIsEitherReturnedToStockOrWrittenOffByHand() {
        setUp();
        InventoryPackage keep = onShelf(shelfA);
        Session session = stocktake.open(null, shelfA.id(), null);
        stocktake.count(session.id(), keep.qrCode(), null, true, "Looks wet");
        InventoryPackage scrap = ctx.getPackageDao().findByFilter(PackageFilter.empty()).stream()
                .filter(p -> p.status() == PackageStatus.IN_STOCK && p.locationId() == shelfA.id()
                        && p.id() != keep.id())
                .findFirst().orElseThrow();
        stocktake.count(session.id(), scrap.qrCode(), null, true, "Torn open");
        stocktake.close(session.id());
        assertEquals(2, stocktake.quarantined().size());

        stocktake.returnToStock(keep.id(), "Dried out, fine");
        stocktake.writeOffQuarantined(scrap.id(), "Beyond saving");

        assertEquals(PackageStatus.IN_STOCK, reload(keep.id()).status());
        assertEquals(shelfA.id(), reload(keep.id()).locationId(), "It goes back on the shelf it is standing on");
        assertEquals(PackageStatus.DISCARDED, reload(scrap.id()).status());
        assertTrue(stocktake.quarantined().isEmpty());
        assertTrue(movementNotes(keep.id()).stream()
                .anyMatch(n -> n.startsWith(MovementType.QUARANTINE_RELEASE.name()) && n.contains("Dried out")));
        assertTrue(movementNotes(scrap.id()).stream()
                .anyMatch(n -> n.startsWith(MovementType.DISCARD.name()) && n.contains("Beyond saving")));

        // Only quarantined stock can be decided on, and only the right roles may decide.
        assertThrows(IllegalStateException.class, () -> stocktake.returnToStock(keep.id(), "again"));
        ctx.getSession().login(ctx.getUserDao().findByUsername("sorter01").orElseThrow());
        assertThrows(IllegalStateException.class, () -> stocktake.writeOffQuarantined(scrap.id(), "nope"));
    }

    @Test
    void quarantinedStockCannotBeSoldOrCountedTowardsStock() {
        setUp();
        InventoryPackage item = onShelf(shelfA);
        long customer = ctx.getCustomerDao().findAll(true).get(0).id();
        var order = ctx.getOrderService().createOrder(customer, "EUR", null, manager.id());
        Session session = stocktake.open(null, shelfA.id(), null);
        stocktake.count(session.id(), item.qrCode(), null, true, "Mouldy");
        stocktake.close(session.id());

        assertThrows(IllegalStateException.class,
                () -> ctx.getOrderService().addPackageToOrder(order.id(), item.id(), new BigDecimal("5.00"), manager.id()),
                "Quarantined stock must not be sold");
        assertTrue(ctx.getPackageDao().findByFilter(new hu.due.rewear.model.PackageFilter(
                        null, PackageStatus.IN_STOCK, null, null, null, null, null)).stream()
                .noneMatch(p -> p.id() == item.id()));
    }

    @Test
    void whatNobodyCountedIsReportedAsMissingAndIsNeverWrittenOff() {
        setUp();
        InventoryPackage counted = onShelf(shelfA);
        Session session = stocktake.open(null, shelfA.id(), null);
        long expectedOnShelf = ctx.getPackageDao().findByFilter(PackageFilter.empty()).stream()
                .filter(p -> p.locationId() == shelfA.id())
                .filter(p -> p.status() == PackageStatus.IN_STOCK || p.status() == PackageStatus.RESERVED)
                .count();
        assertTrue(expectedOnShelf > 1, "The demo shelf should hold more than one package");

        stocktake.count(session.id(), counted.qrCode(), null, false, null);

        var missing = stocktake.missing(session.id());
        assertEquals(expectedOnShelf - 1, missing.size());
        assertTrue(missing.stream().noneMatch(m -> m.packageId() == counted.id()),
                "What was counted is not missing");

        var applied = stocktake.close(session.id());

        assertEquals(missing.size(), applied.missing());
        for (var gone : missing) {
            // It keeps whatever status it had — in stock, or reserved for an order — and is never written off.
            assertTrue(reload(gone.packageId()).status() != PackageStatus.DISCARDED,
                    "A missing package is reported, never written off on its own");
        }
    }

    @Test
    void onlyOneStocktakeRunsAtATimeAndOnlyTheRightRolesMayRunIt() {
        setUp();
        Session first = stocktake.open(null, shelfA.id(), null);
        assertThrows(IllegalStateException.class, () -> stocktake.open(null, shelfB.id(), null));
        assertThrows(IllegalArgumentException.class, () -> stocktake.open("A", shelfA.id(), null),
                "A session counts a shelf or a zone, not both");
        stocktake.cancel(first.id());
        assertEquals(Status.CANCELLED, stocktake.findSession(first.id()).orElseThrow().status());
        assertTrue(stocktake.openSession().isEmpty());

        Session second = stocktake.open(null, shelfA.id(), null);
        InventoryPackage item = onShelf(shelfA);
        for (String username : List.of("sorter01", "helper01", "forklift2")) {
            ctx.getSession().login(ctx.getUserDao().findByUsername(username).orElseThrow());
            assertFalse(stocktake.mayCount(), username + " must not run a stocktake");
            assertThrows(IllegalStateException.class,
                    () -> stocktake.count(second.id(), item.qrCode(), null, false, null), username);
            assertThrows(IllegalStateException.class, () -> stocktake.close(second.id()), username);
        }

        // The station manager counts the shelves in practice, so they may.
        ctx.getSession().login(ctx.getUserDao().findByUsername("operator").orElseThrow());
        assertTrue(stocktake.mayCount());
        stocktake.count(second.id(), item.qrCode(), null, false, null);

        ctx.getSession().login(manager);
        stocktake.close(second.id());
        assertThrows(IllegalStateException.class, () -> stocktake.close(second.id()), "Closing twice is refused");
        assertThrows(IllegalStateException.class,
                () -> stocktake.count(second.id(), item.qrCode(), null, false, null), "A closed session takes no counts");
    }

    @Test
    void aZoneSessionAcceptsItsOwnZoneAndFlagsAnythingElseAsUnexpected() {
        setUp();
        InventoryPackage inZone = onShelf(shelfA);
        String zone = shelfA.zone();
        InventoryPackage outsideZone = ctx.getPackageDao().findByFilter(PackageFilter.empty()).stream()
                .filter(p -> p.status() == PackageStatus.IN_STOCK)
                .filter(p -> !ctx.getLocationDao().findById(p.locationId()).orElseThrow().zone().equalsIgnoreCase(zone))
                .findFirst().orElseThrow();

        Session session = stocktake.open(zone, null, null);
        assertNull(session.scopeLocationCode());

        assertEquals(Outcome.FOUND, stocktake.count(session.id(), inZone.qrCode(), null, false, null).outcome());
        assertEquals(Outcome.UNEXPECTED,
                stocktake.count(session.id(), outsideZone.qrCode(), null, false, null).outcome());

        var applied = stocktake.close(session.id());
        assertEquals(0, applied.relocated(), "A zone count does not know where the counter was standing");
        assertEquals(outsideZone.locationId(), reload(outsideZone.id()).locationId(), "Nothing moved");
    }
}
