package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.MovementType;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.model.PackageFilter;
import hu.due.rewear.model.PackageStatus;
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
 * Verifies the v2.4 end-to-end order fulfillment &amp; verified customer handover workflow:
 * Manager assigns Forklift Driver + Collection Site -> Forklift scans barcodes to collect items
 * to Collection Site -> Customer Arrived triggers alert -> Manager scans every package on the order
 * (rejecting foreign QR &amp; blocking premature completion) -> Verified Handover ships the order.
 */
class V24OrderHandoverWorkflowTest {

    @TempDir
    Path tempDir;

    @Test
    void fullForkliftScanCollectCustomerArrivedAndManagerVerifiedHandoverFlow() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("v24-test.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        AppContext ctx = new AppContext(db, prefs, i18n);

        User manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        User forklift = ctx.getUserDao().findByUsername("forklift2").orElseThrow();
        WarehouseLocation dock = ctx.getLocationDao().findByType(LocationType.DISPATCH_ZONE, true).get(0);

        // 1. Manager creates an order, allocates 2 in-stock packages, and assigns Forklift Driver + DOCK-01
        long custId = ctx.getCustomerDao().findAll(true).get(0).id();
        Order order = ctx.getOrderService().createOrder(custId, "EUR", null, manager.id());
        List<InventoryPackage> stock = ctx.getPackageDao().findByFilter(PackageFilter.empty()).stream()
                .filter(p -> p.status() == PackageStatus.IN_STOCK)
                .toList();
        InventoryPackage p1 = stock.get(0);
        InventoryPackage p2 = stock.get(1);
        ctx.getOrderService().addPackageToOrder(order.id(), p1.id(), new BigDecimal("5.00"), manager.id());
        ctx.getOrderService().addPackageToOrder(order.id(), p2.id(), new BigDecimal("5.00"), manager.id());

        Order assigned = ctx.getOrderService().assignForkliftAndDock(order.id(), forklift.id(), dock.id());
        assertEquals(forklift.id(), assigned.assignedForkliftId());
        assertEquals(dock.code(), assigned.dispatchLocationCode());

        // 2. Forklift sees assigned order items and scans both QR codes to collect them to DOCK-01
        List<TransportService.StagingTask> fkTasks = ctx.getTransportService().findOrderStagingTasksForDriver(forklift.id());
        assertTrue(fkTasks.stream().anyMatch(t -> t.packageId() == p1.id()));
        assertTrue(fkTasks.stream().anyMatch(t -> t.packageId() == p2.id()));

        long smallerDockId = ctx.getLocationDao().insert("DOCK-SMALL", "D", "1", "1",
                LocationType.DISPATCH_ZONE, BigDecimal.ONE);
        ctx.getOrderService().assignForkliftAndDock(order.id(), forklift.id(), smallerDockId);
        assertThrows(IllegalStateException.class, () ->
                ctx.getTransportService().scanAndCollectForOrder(p1.qrCode(), dock.id(), forklift.id(), true));
        assertEquals(OrderStatus.DRAFT, ctx.getOrderDao().findById(order.id()).orElseThrow().status());
        assertEquals(p1.locationId(), ctx.getPackageDao().findById(p1.id()).orElseThrow().locationId());
        ctx.getOrderService().assignForkliftAndDock(order.id(), forklift.id(), dock.id());

        assertThrows(IllegalStateException.class, () ->
                ctx.getOrderService().changeOrderStatus(order.id(), OrderStatus.SHIPPED, manager.id()));

        assertThrows(IllegalArgumentException.class, () ->
                ctx.getTransportService().stageToDispatchZone(List.of(p1.id()), smallerDockId, forklift.id(), true));
        assertThrows(IllegalStateException.class, () ->
                ctx.getInventoryService().discardPackage(p1.id(), "Still allocated", manager.id()));
        int pendingBefore = ctx.getTransportService().countPendingStaging(forklift.id());
        int mgrAlertsBeforeStaging = ctx.getNotificationService().countUnread(manager.id());

        TransportService.StagingTask t1 = ctx.getTransportService().scanAndCollectForOrder(p1.qrCode(), dock.id(), forklift.id(), true);
        assertTrue(t1.isAtDispatchZone());
        assertEquals(OrderStatus.PACKING, ctx.getOrderDao().findById(order.id()).orElseThrow().status(),
                "Scanning first package should automatically advance DRAFT -> PACKING");
        assertEquals(pendingBefore - 1, ctx.getTransportService().countPendingStaging(forklift.id()));
        assertEquals(mgrAlertsBeforeStaging, ctx.getNotificationService().countUnread(manager.id()));

        TransportService.StagingTask t2 = ctx.getTransportService().scanAndCollectForOrder(p2.qrCode(), dock.id(), forklift.id(), true);
        assertTrue(t2.isAtDispatchZone());
        assertEquals(pendingBefore - 2, ctx.getTransportService().countPendingStaging(forklift.id()));
        assertEquals(mgrAlertsBeforeStaging + 1, ctx.getNotificationService().countUnread(manager.id()));
        ctx.getTransportService().scanAndCollectForOrder(p2.qrCode(), dock.id(), forklift.id(), true);
        assertEquals(mgrAlertsBeforeStaging + 1, ctx.getNotificationService().countUnread(manager.id()));

        // 3. Customer arrives -> triggers high-priority notification to Manager & Forklift
        int mgrNotifsBefore = ctx.getNotificationService().countUnread(manager.id());
        Order arrived = ctx.getOrderService().markCustomerArrived(order.id());
        assertTrue(arrived.isCustomerArrived());
        assertEquals(mgrNotifsBefore + 1, ctx.getNotificationService().countUnread(manager.id()));
        assertEquals(arrived.customerArrivedAt(),
                ctx.getOrderService().markCustomerArrived(order.id()).customerArrivedAt());
        assertEquals(mgrNotifsBefore + 1, ctx.getNotificationService().countUnread(manager.id()));

        // 4. Manager verified customer handover:
        //    - Premature handover blocked before all items scanned
        assertThrows(IllegalStateException.class, () ->
                ctx.getOrderService().completeVerifiedHandover(order.id(), manager.id()));
        //    - Foreign QR code rejected
        assertThrows(IllegalArgumentException.class, () ->
                ctx.getOrderService().scanOrderItemForHandover(order.id(), "2026-01-01-00-00-X-XXX-A-A-10-FF", manager.id()));

        //    - Scan first package (still blocked because 1 remaining)
        var s1 = ctx.getOrderService().scanOrderItemForHandover(order.id(), p1.qrCode(), manager.id());
        assertTrue(s1.handoverScanned());
        ctx.getOrderService().scanOrderItemForHandover(order.id(), p1.qrCode(), manager.id());
        assertEquals(1L, ctx.getMovementDao().findByPackageId(p1.id()).stream()
                .filter(move -> move.movementType() == MovementType.SCAN_VERIFY).count());
        assertThrows(IllegalStateException.class, () ->
                ctx.getOrderService().completeVerifiedHandover(order.id(), manager.id()));

        //    - Scan second package -> complete handover succeeds and ships the order!
        var s2 = ctx.getOrderService().scanOrderItemForHandover(order.id(), p2.qrCode(), manager.id());
        assertTrue(s2.handoverScanned());

        Order shipped = ctx.getOrderService().completeVerifiedHandover(order.id(), manager.id());
        assertEquals(OrderStatus.SHIPPED, shipped.status());
        assertFalse(ctx.getPackageDao().findById(p1.id()).orElseThrow().status() == PackageStatus.RESERVED);
        assertEquals(PackageStatus.SHIPPED, ctx.getPackageDao().findById(p1.id()).orElseThrow().status());
        assertEquals(PackageStatus.SHIPPED, ctx.getPackageDao().findById(p2.id()).orElseThrow().status());

        Order reopened = ctx.getOrderService().changeOrderStatus(order.id(), OrderStatus.PACKING, manager.id());
        assertFalse(reopened.isCustomerArrived());
        assertTrue(ctx.getOrderItemDao().findByOrderId(order.id()).stream().noneMatch(item -> item.handoverScanned()));
        assertThrows(IllegalStateException.class, () ->
                ctx.getOrderService().changeOrderStatus(order.id(), OrderStatus.SHIPPED, manager.id()));

        User operator = ctx.getUserDao().findByUsername("operator").orElseThrow();
        ctx.getUserDao().updateAssignedStation(operator.id(), null);
        ctx.getOrderService().assignForkliftAndDock(order.id(), null, null);
        new SchemaInitializer(db).initialize();
        assertNull(ctx.getUserDao().findById(operator.id()).orElseThrow().assignedStationId());
        Order afterRestart = ctx.getOrderDao().findById(order.id()).orElseThrow();
        assertNull(afterRestart.assignedForkliftId());
        assertNull(afterRestart.dispatchLocationId());
    }
}
