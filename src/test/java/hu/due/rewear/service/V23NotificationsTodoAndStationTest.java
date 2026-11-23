package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppNotification;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.PersonalTodo;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.SystemTodoItem;
import hu.due.rewear.model.User;
import hu.due.rewear.model.WarehouseLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies v2.3 features: automated operational notifications (Operator -> Forklift -> Manager),
 * role-specific logically ordered System To-Dos, separate Personal To-Dos, and per-Operator station assignment.
 */
class V23NotificationsTodoAndStationTest {

    @TempDir
    Path tempDir;

    private AppContext newContext() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("v23-test.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        return new AppContext(db, prefs, i18n);
    }

    @Test
    void operatorCreationAndOrderAllocationTriggerForkliftNotificationsAndSystemTodos() {
        AppContext ctx = newContext();
        User operator = ctx.getUserDao().findByUsername("operator").orElseThrow();
        User forklift = ctx.getUserDao().findByUsername("forklift").orElseThrow();
        User manager = ctx.getUserDao().findByUsername("manager").orElseThrow();

        // Demo operator is pre-assigned to ST-OP-01
        assertNotNull(operator.assignedStationId(), "Demo operator should be assigned to ST-OP-01");
        WarehouseLocation station = ctx.getLocationDao().findById(operator.assignedStationId()).orElseThrow();
        assertEquals(LocationType.OPERATOR_STATION, station.locationType());

        int fkNotifsBefore = ctx.getNotificationService().countUnread(forklift.id());

        // 1. Operator commits a new package at their assigned station -> Forklift gets PUTAWAY notification
        var tg = ctx.getDictionaryDao().findTargetGroups(true).get(0);
        var gt = ctx.getDictionaryDao().findGarmentTypes(true).get(0);
        var qg = ctx.getDictionaryDao().findQualityGrades(true).get(0);
        var pt = ctx.getDictionaryDao().findPackageTypes(true).get(0);
        List<InventoryPackage> previews = ctx.getInventoryService().buildPreviewPackages(
                tg, gt, qg, pt, station, new BigDecimal("22"), 1, null, "CD", operator.id(), RoleCode.OPERATOR);
        ctx.getInventoryService().commitPreviewedPackages(previews, station, operator.id(), RoleCode.OPERATOR);

        assertEquals(fkNotifsBefore + 1, ctx.getNotificationService().countUnread(forklift.id()));
        List<AppNotification> fkNotifs = ctx.getNotificationService().listForUser(forklift.id(), 10);
        assertEquals(AppNotification.CAT_PUTAWAY, fkNotifs.get(0).category());

        // 2. Manager creates an order, assigns it to Forklift + DOCK-01, & allocates a shelf package
        long custId = ctx.getCustomerDao().findAll(true).get(0).id();
        WarehouseLocation dock = ctx.getLocationDao().findByType(LocationType.DISPATCH_ZONE, true).get(0);
        Order ord = ctx.getOrderService().createOrder(custId, "EUR", null, manager.id());
        ctx.getOrderService().assignForkliftAndDock(ord.id(), forklift.id(), dock.id());
        InventoryPackage shelfPkg = ctx.getPackageDao().findByFilter(hu.due.rewear.model.PackageFilter.empty())
                .stream()
                .filter(p -> p.status() == hu.due.rewear.model.PackageStatus.IN_STOCK && p.locationId() != station.id())
                .findFirst().orElseThrow();
        ctx.getOrderService().addPackageToOrder(ord.id(), shelfPkg.id(), new BigDecimal("5.00"), manager.id());

        assertTrue(ctx.getNotificationService().countUnread(forklift.id()) >= fkNotifsBefore + 3);

        // 3. Forklift System To-Do list is logically ordered: P1 Order Staging before P2 Putaway
        List<SystemTodoItem> fkTodos = ctx.getTodoService().buildSystemTodosFor(forklift, ctx.getI18n());
        assertFalse(fkTodos.isEmpty());
        assertEquals("P1", fkTodos.get(0).priorityBadge());
        assertTrue(fkTodos.stream().anyMatch(t -> "P2".equals(t.priorityBadge())));

        // 4. Operator System To-Do list includes their assigned station status
        List<SystemTodoItem> opTodos = ctx.getTodoService().buildSystemTodosFor(operator, ctx.getI18n());
        assertTrue(opTodos.stream().anyMatch(t -> t.summary().contains(station.code())));

        // 5. Forklift stages package to DOCK-01 -> Manager receives ORDER_READY notification
        int mgrNotifsBefore = ctx.getNotificationService().countUnread(manager.id());
        ctx.getTransportService().stageToDispatchZone(List.of(shelfPkg.id()), dock.id(), forklift.id());
        assertEquals(mgrNotifsBefore + 1, ctx.getNotificationService().countUnread(manager.id()));
    }

    @Test
    void personalTodosArePrivatePerUserAndSeparateFromSystemTodos() {
        AppContext ctx = newContext();
        User forklift = ctx.getUserDao().findByUsername("forklift").orElseThrow();
        User operator = ctx.getUserDao().findByUsername("operator").orElseThrow();

        long id1 = ctx.getTodoService().addPersonalTodo(forklift.id(), "Check forklift battery", PersonalTodo.PRIO_HIGH, LocalDate.now().plusDays(1));
        long id2 = ctx.getTodoService().addPersonalTodo(forklift.id(), "Sweep DOCK-01", PersonalTodo.PRIO_LOW, null);

        List<PersonalTodo> fkList = ctx.getTodoService().listPersonalTodos(forklift.id());
        assertEquals(2, fkList.size());
        assertEquals(PersonalTodo.PRIO_HIGH, fkList.get(0).priority());
        assertTrue(ctx.getTodoService().listPersonalTodos(operator.id()).isEmpty(), "Personal to-dos must be private per user");

        ctx.getTodoService().togglePersonalTodo(id1, true);
        assertTrue(ctx.getTodoService().listPersonalTodos(forklift.id()).stream()
                .filter(t -> t.id() == id1).findFirst().orElseThrow().completed());

        ctx.getTodoService().deletePersonalTodo(id2);
        assertEquals(1, ctx.getTodoService().listPersonalTodos(forklift.id()).size());
    }
}
