package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import hu.due.rewear.service.SortingService.Lot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Raw material arrives as daily collections from drop-off areas, is stored by a forklift driver, and is then
 * called for by name — never by weight, because the sorter cannot know what the driver will bring.
 */
class RawMaterialSupplyTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private SortingService sorting;
    private User manager;
    private User sorter;
    private User supplyDriver;
    private long shelf;

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("raw.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(db, prefs, i18n);
        sorting = ctx.getSortingService();
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        sorter = ctx.getUserDao().findByUsername("sorter01").orElseThrow();
        supplyDriver = ctx.getUserDao().findByUsername("forklift").orElseThrow();
        shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0).id();
        ctx.getSession().login(manager);
    }

    private Lot lot(long id) {
        return sorting.workspace(sorting.today()).lots().stream()
                .filter(row -> row.id() == id).findFirst().orElseThrow();
    }

    @Test
    void aCollectionIsReceivedWithItsMaterialAndAreaThenStoredBeforeItCanBeUsed() {
        setUp();
        long id = sorting.receive("RAW-TEST-1", "Vegyes használt ruha", "Budapest és Pest megye", bd("12000"));

        Lot received = lot(id);
        assertEquals("Vegyes használt ruha", received.material());
        assertEquals("Budapest és Pest megye", received.sourceArea());
        assertEquals(12_000_000L, received.availableGrams());
        assertFalse(received.stored(), "A collection is not usable until a driver stores it");
        assertTrue(ctx.getNotificationService().listForUser(supplyDriver.id(), 100).stream()
                .anyMatch(n -> AppModule.SORTING.viewKey().equals(n.targetView()) && n.title().contains("RAW-TEST-1")),
                "The supply driver is told there is something to store");
        assertFalse(ctx.getNotificationService().listForUser(
                ctx.getUserDao().findByUsername("forklift2").orElseThrow().id(), 100).stream()
                .anyMatch(n -> n.title().contains("RAW-TEST-1")), "The fulfilment driver is not involved");

        // A sorter cannot call for it while it is still unstored, and does not even see it.
        ctx.getSession().login(sorter);
        assertTrue(sorting.workspace(sorting.today()).lots().stream().noneMatch(row -> row.id() == id));
        assertThrows(IllegalStateException.class, () -> sorting.requestSupply(id));

        ctx.getSession().login(supplyDriver);
        sorting.storeLot(id, shelf);

        ctx.getSession().login(sorter);
        Lot stored = lot(id);
        assertTrue(stored.stored());
        assertEquals(ctx.getLocationDao().findById(shelf).orElseThrow().code(), stored.locationCode());
    }

    @Test
    void callingForMaterialNamesTheLineAndTheDriverDecidesTheWeight() {
        setUp();
        long id = sorting.receive("RAW-TEST-2", "Gyerekruha", "Dunaújváros", bd("500"));
        sorting.storeLot(id, shelf);

        ctx.getSession().login(sorter);
        long request = sorting.requestSupply(id);

        var called = sorting.workspace(sorting.today()).requests().stream()
                .filter(r -> r.id() == request).findFirst().orElseThrow();
        assertEquals(null, called.requestedGrams(), "No weight is promised when calling for material");
        assertEquals(id, called.requestedLotId());
        assertEquals("Gyerekruha", called.material());
        assertEquals("RAW-TEST-2", called.lot(), "The sorter sees which line was called for before delivery");

        // The driver brings whatever was loaded; it need not match any requested figure.
        ctx.getSession().login(manager);
        long other = sorting.receive("RAW-TEST-2B", "Kabát", "Győr", bd("900"));
        sorting.storeLot(other, shelf);
        ctx.getSession().login(supplyDriver);
        assertThrows(IllegalStateException.class, () -> sorting.deliver(request, other, bd("50")),
                "The called line cannot be swapped for another at delivery");
        sorting.deliver(request, bd("137.5"));

        ctx.getSession().login(sorter);
        var delivered = sorting.workspace(sorting.today()).requests().stream()
                .filter(r -> r.id() == request).findFirst().orElseThrow();
        assertEquals(137_500L, delivered.deliveredGrams());
        ctx.getSession().login(manager);
        assertEquals(500_000L - 137_500L, lot(id).availableGrams(), "Only the delivered weight leaves the line");
    }

    @Test
    void materialThatIsGoneOrUnstoredCannotBeCalledForAndBadDetailsAreRefused() {
        setUp();
        long small = sorting.receive("RAW-TEST-3", "Cipő", "Debrecen", bd("10"));
        sorting.storeLot(small, shelf);
        long unstored = sorting.receive("RAW-TEST-4", "Lakástextil", "Szeged", bd("900"));

        // The service reports every refused action as IllegalStateException; the weight parser is the exception.
        for (String[] bad : new String[][]{{"", "Vegyes", "Pest"}, {"RAW-X", "", "Pest"}, {"RAW-X", "Vegyes", ""}}) {
            assertThrows(IllegalStateException.class,
                    () -> sorting.receive(bad[0], bad[1], bad[2], bd("100")), String.join("/", bad));
        }
        assertThrows(IllegalArgumentException.class, () -> sorting.receive("RAW-X", "Vegyes", "Pest", bd("0")));
        assertThrows(IllegalStateException.class, () -> sorting.storeLot(small, 999_999L), "Unknown location");
        var station = ctx.getLocationDao().findByType(LocationType.OPERATOR_STATION, true).get(0);
        assertThrows(IllegalStateException.class, () -> sorting.storeLot(small, station.id()),
                "Raw stock belongs on a storage shelf, not at a labelling station");

        ctx.getSession().login(sorter);
        assertThrows(IllegalStateException.class, () -> sorting.requestSupply(unstored));
        long request = sorting.requestSupply(small);
        ctx.getSession().login(supplyDriver);
        assertThrows(IllegalStateException.class, () -> sorting.deliver(request, bd("11")),
                "A driver cannot deliver more than the line holds");
        sorting.deliver(request, bd("10"));

        ctx.getSession().login(sorter);
        assertThrows(IllegalStateException.class, () -> sorting.requestSupply(small), "A used-up line cannot be called for");
    }

    @Test
    void onlyTheRightRolesMayReceiveStoreAndCall() {
        setUp();
        long id = sorting.receive("RAW-TEST-5", "Vegyes", "Pest", bd("100"));
        User operator = ctx.getUserDao().findByUsername("operator").orElseThrow();

        ctx.getSession().login(sorter);
        assertThrows(IllegalStateException.class, () -> sorting.receive("RAW-X", "Vegyes", "Pest", bd("100")));
        assertThrows(IllegalStateException.class, () -> sorting.storeLot(id, shelf));

        ctx.getSession().login(operator);
        assertThrows(IllegalStateException.class, () -> sorting.receive("RAW-X", "Vegyes", "Pest", bd("100")));
        assertThrows(IllegalStateException.class, () -> sorting.storeLot(id, shelf));

        ctx.getSession().login(supplyDriver);
        sorting.storeLot(id, shelf);
        assertThrows(IllegalStateException.class, () -> sorting.requestSupply(id), "Only a sorter calls for material");

        ctx.getSession().login(manager);
        assertThrows(IllegalStateException.class, () -> sorting.requestSupply(id));
    }

    @Test
    void theDemoWorkspaceShowsSeveralCollectionAreasAndSomeStillAwaitingStorage() {
        setUp();
        List<Lot> lots = sorting.workspace(sorting.today()).lots();

        assertTrue(lots.size() >= 6, "The demo has a set of daily collections: " + lots.size());
        assertTrue(lots.stream().allMatch(row -> !row.material().isBlank() && !row.sourceArea().isBlank()),
                "Every demo line names its material and collection area");
        assertTrue(lots.stream().map(Lot::sourceArea).distinct().count() >= 5, "Different drivers, different areas");
        assertTrue(lots.stream().anyMatch(Lot::stored), "Some are already on a shelf");
        assertTrue(lots.stream().anyMatch(row -> !row.stored()), "…and some still wait for a forklift driver");
        assertEquals(RoleCode.SORTER, sorter.roleCode());
    }
}
