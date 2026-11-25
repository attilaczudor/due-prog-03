package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.OrderDemandDao.Product;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SortingToSaleWorkflowTest {
    @TempDir Path tmp;

    @Test
    void tracesRawStockThroughLabellingTargetEligibilityStockReservationAndVerifiedShipment() throws Exception {
        var ctx = new AppContext(new DatabaseManager(tmp.resolve("sort-to-sale.db")),
                new AppPreferences(tmp.resolve("app.properties")), new I18nManager());
        var sorting = ctx.getSortingService();
        var orders = ctx.getOrderService();
        User manager = user(ctx, "manager");
        User sorter = user(ctx, "sorter01");
        User helper = user(ctx, "helper01");
        User station = user(ctx, "operator");
        User supply = user(ctx, "forklift");
        User fulfilment = user(ctx, "forklift2");
        assertEquals("Station Manager", station.roleName());
        assertEquals(RoleCode.SORTER, sorter.roleCode());
        assertEquals(RoleCode.SORTING_HELPER, helper.roleCode());
        assertEquals(20, ctx.getUserDao().findAll().stream().filter(u -> u.roleCode() == RoleCode.SORTER).count());
        assertEquals(5, ctx.getUserDao().findAll().stream().filter(u -> u.roleCode() == RoleCode.SORTING_HELPER).count());
        assertTrue(ctx.getAuthService().login("sorter01", "sorter123".toCharArray()).isSuccess());
        assertTrue(ctx.getSession().canAccessModule(AppModule.SORTING));
        assertFalse(ctx.getSession().canUseIntake());

        ctx.getSession().login(manager);
        var team = sorting.workspace(sorting.today()).teams().stream().filter(t -> t.helperIds().contains(helper.id())).findFirst().orElseThrow();
        assertEquals(4, team.sorterIds().size());
        sorting.saveTeam(team.id(), team.name(), List.of(helper.id()), supply.id(), team.stationId(),
                team.sorterIds(), bd("1000"), bd("7.5"));
        long lot = sorting.receive("TEST-R-001", bd("1300"));
        long shortLot = sorting.receive("TEST-SHORT", bd("20"));

        ctx.getSession().login(sorter);
        assertEquals(0L, sorting.workspace(sorting.today()).daily().get(0).processedGrams());
        long request = sorting.requestSupply(bd("400"));
        assertTrue(ctx.getNotificationService().listForUser(supply.id(), 100).stream()
                .anyMatch(n -> n.title().endsWith("#" + request) && AppModule.SORTING.viewKey().equals(n.targetView())));
        assertFalse(ctx.getNotificationService().listForUser(fulfilment.id(), 100).stream()
                .anyMatch(n -> AppModule.SORTING.viewKey().equals(n.targetView())));

        ctx.getSession().login(fulfilment);
        assertThrows(IllegalStateException.class, () -> sorting.deliver(request, lot, bd("400")));
        ctx.getSession().login(supply);
        assertThrows(IllegalStateException.class, () -> sorting.deliver(request, shortLot, bd("400")));
        assertTrue(sorting.workspace(sorting.today()).requests().get(0).awaitingDelivery());
        sorting.deliver(request, lot, bd("400"));
        assertThrows(IllegalStateException.class, () -> sorting.deliver(request, lot, bd("400")));

        var dict = ctx.getDictionaryDao();
        var target = dict.findTargetGroups(true).stream().filter(t -> t.code().equals("B")).findFirst().orElseThrow();
        var garment = dict.findGarmentTypes(true).stream().filter(t -> t.code().equals("MIX")).findFirst().orElseThrow();
        var grade = dict.findQualityGrades(true).stream().filter(t -> t.code().equals("CR")).findFirst().orElseThrow();
        var packaging = dict.findPackageTypes(true).stream().filter(t -> t.code().equals("B")).findFirst().orElseThrow();
        ctx.getSession().login(sorter);
        long bag = sorting.reportBag(request, target.id(), garment.id(), grade.id(), bd("15"));
        assertThrows(IllegalStateException.class, () -> sorting.reportBag(request, target.id(), garment.id(), grade.id(), bd("390")));
        assertThrows(IllegalStateException.class, () -> sorting.finish(request, bd("14")));
        sorting.finish(request, bd("400"));

        for (String amount : List.of("400", "200")) {
            long next = sorting.requestSupply(bd(amount));
            ctx.getSession().login(supply);
            sorting.deliver(next, lot, bd(amount));
            ctx.getSession().login(sorter);
            sorting.finish(next, bd(amount));
        }
        var total = sorting.workspace(sorting.today()).daily().get(0);
        assertEquals(1_000_000L, total.processedGrams());
        assertEquals(750, total.bonusBasisPoints());
        assertTrue(total.reachedTarget());
        assertEquals(helper.displayName(), total.helper());

        long extra = sorting.requestSupply(bd("100"));
        ctx.getSession().login(supply);
        sorting.deliver(extra, lot, bd("100"));
        ctx.getSession().login(sorter);
        sorting.finish(extra, BigDecimal.ZERO);
        ctx.getSession().login(manager);
        assertEquals(300_000L, sorting.workspace(sorting.today()).lots().stream()
                .filter(row -> row.id() == lot).findFirst().orElseThrow().availableGrams());

        ctx.getSession().login(station);
        assertThrows(IllegalStateException.class, () -> sorting.registerBag(bag));
        ctx.getSession().login(user(ctx, "helper02"));
        assertThrows(IllegalStateException.class, () -> sorting.pack(bag, packaging.id()));
        ctx.getSession().login(helper);
        sorting.pack(bag, packaging.id());
        assertTrue(sorting.workspace(sorting.today()).daily().stream().anyMatch(SortingService.Daily::reachedTarget));

        ctx.getSession().login(station);
        var pkg = sorting.registerBag(bag);
        assertEquals(0, bd("15").compareTo(pkg.weightKg()));
        assertEquals(PackageStatus.IN_STOCK, pkg.status());
        assertThrows(IllegalStateException.class, () -> sorting.registerBag(bag));
        assertTrue(ctx.getTransportService().findPutawayQueue().stream().anyMatch(p -> p.id() == pkg.id()));
        var history = sorting.workspace(sorting.today()).bags().get(0);
        assertNotNull(history.reportedAt());
        assertNotNull(history.packedAt());
        assertNotNull(history.registeredAt());
        assertEquals(pkg.qrCode(), history.qr());

        var storage = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0);
        ctx.getTransportService().putawayToStorage(List.of(pkg.id()), storage.id(), supply.id());

        var buyer = ctx.getCustomerDao().findBuyers(true).get(0);
        var dock = ctx.getLocationDao().findByType(LocationType.DISPATCH_ZONE, true).get(0);
        var product = new Product(target.id(), garment.id(), grade.id(), packaging.id());
        LocalDate date = sorting.today().plusDays(1);
        var importedOrder = tmp.resolve("order.properties");
        Files.writeString(importedOrder, """
                source=demo-webshop
                external_id=SHOP-100
                customer_id=%d
                currency=EUR
                delivery_date=%s
                product.1=B,MIX,CR,B,100
                """.formatted(buyer.id(), date));
        var imported = WebshopOrderFile.importOrder(importedOrder, dict, orders, manager.id());
        assertEquals(imported.id(), WebshopOrderFile.importOrder(importedOrder, dict, orders, manager.id()).id());
        assertEquals(date, imported.deliveryDate());
        orders.assignForkliftAndDock(imported.id(), fulfilment.id(), dock.id());
        assertTrue(orders.checkAvailability(imported.id()).get(0).shortage() > 0);
        assertThrows(IllegalStateException.class, () -> orders.allocateRequestedProducts(imported.id(), manager.id()));
        assertTrue(ctx.getOrderItemDao().findByOrderId(imported.id()).isEmpty());

        var order = orders.createScheduledOrder(buyer.id(), "EUR", date, manager.id());
        orders.setRequestedProduct(order.id(), product, 1);
        orders.assignForkliftAndDock(order.id(), fulfilment.id(), dock.id());
        assertEquals(0, orders.checkAvailability(order.id()).get(0).shortage());
        assertEquals(OrderStatus.PACKING, orders.allocateRequestedProducts(order.id(), manager.id()).status());
        String qr = ctx.getOrderItemDao().findByOrderId(order.id()).get(0).packageQrCode();
        assertThrows(IllegalStateException.class, () -> ctx.getInvoiceService().ensureInvoiceForOrder(order.id(), manager.id()));
        assertThrows(IllegalStateException.class, () -> orders.scanOrderItemForHandover(order.id(), qr, fulfilment.id()));
        assertThrows(IllegalStateException.class, () -> ctx.getTransportService().scanAndCollectForOrder(qr, dock.id(), supply.id(), false));
        ctx.getTransportService().scanAndCollectForOrder(qr, dock.id(), fulfilment.id(), true);
        orders.markCustomerArrived(order.id());
        assertThrows(IllegalStateException.class, () -> orders.completeVerifiedHandover(order.id(), fulfilment.id()));
        assertThrows(IllegalStateException.class, () -> orders.scanOrderItemForHandover(order.id(), qr, supply.id()));
        assertTrue(orders.scanOrderItemForHandover(order.id(), qr, fulfilment.id()).handoverScanned());
        assertTrue(orders.scanOrderItemForHandover(order.id(), qr, fulfilment.id()).handoverScanned());
        assertEquals(OrderStatus.SHIPPED, orders.completeVerifiedHandover(order.id(), fulfilment.id()).status());
        assertEquals(InvoiceStatus.ISSUED, ctx.getInvoiceService().ensureInvoiceForOrder(order.id(), manager.id()).status());
        assertThrows(IllegalStateException.class, () -> orders.changeOrderStatus(order.id(), OrderStatus.PACKING, manager.id()));
    }

    @Test
    void upgradesPlaceholderNamesWithoutChangingEditedProfilesOrTeamMembership() {
        var database = new DatabaseManager(tmp.resolve("demo-names.db"));
        var prefs = new AppPreferences(tmp.resolve("app.properties"));
        var before = new AppContext(database, prefs, new I18nManager());
        User sorter = user(before, "sorter01");
        User helper = user(before, "helper01");
        User driver = user(before, "forklift2");
        assertEquals("Balogh Anna", sorter.displayName());
        assertEquals("Szűcs Bálint", helper.displayName());
        assertEquals("Kelemen Ádám", driver.displayName());

        before.getSession().login(user(before, "manager"));
        var service = before.getSortingService();
        var teams = service.workspace(service.today()).teams();
        before.getUserDao().updateProfile(sorter.id(), "Minta Válogató 1", sorter.roleId(), sorter.active());
        before.getUserDao().updateProfile(helper.id(), "Szabó Judit", helper.roleId(), helper.active());
        before.getUserDao().updateProfile(driver.id(), "Minta Kiszállítási Targoncás", driver.roleId(), driver.active());
        before.getSettingsDao().set("demo.sorting.seeded", "1");

        var after = new AppContext(database, prefs, new I18nManager());
        assertEquals(sorter.id(), user(after, "sorter01").id());
        assertEquals("Balogh Anna", user(after, "sorter01").displayName());
        assertEquals("Szabó Judit", user(after, "helper01").displayName());
        assertEquals("Kelemen Ádám", user(after, "forklift2").displayName());
        assertTrue(after.getAuthService().login("sorter01", "sorter123".toCharArray()).isSuccess());
        after.getSession().login(user(after, "manager"));
        var migrated = after.getSortingService();
        assertEquals(teams, migrated.workspace(migrated.today()).teams());
    }

    private static User user(AppContext ctx, String name) {
        return ctx.getUserDao().findByUsername(name).orElseThrow();
    }

    private static BigDecimal bd(String amount) { return new BigDecimal(amount); }
}
