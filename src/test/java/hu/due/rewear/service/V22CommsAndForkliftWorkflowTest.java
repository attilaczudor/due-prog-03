package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.ChatRoom;
import hu.due.rewear.model.EmailMessage;
import hu.due.rewear.model.EmailSettings;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import hu.due.rewear.model.WarehouseLocation;
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
 * End-to-end integration tests for v2.2 features:
 * strict Operator Station intake + Pre-Save Preview commit, Forklift Putaway &amp; Order Staging workflow,
 * Super-Admin email domain &amp; SMTP/IMAP settings, internal/external mailbox delivery, and Element-style chat.
 */
class V22CommsAndForkliftWorkflowTest {

    @TempDir
    Path tempDir;

    private AppContext newContext() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("v22-test.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        return new AppContext(db, prefs, i18n);
    }

    @Test
    void operatorStrictlyLockedToOperatorStationAndPreSavePreviewDoesNotWriteUntilCommitted() {
        AppContext ctx = newContext();
        User operator = ctx.getUserDao().findByUsername("operator").orElseThrow();
        WarehouseLocation station = ctx.getLocationDao().findByType(LocationType.OPERATOR_STATION, true).get(0);
        WarehouseLocation shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0);

        var tg = ctx.getDictionaryDao().findTargetGroups(true).get(0);
        var gt = ctx.getDictionaryDao().findGarmentTypes(true).get(0);
        var qg = ctx.getDictionaryDao().findQualityGrades(true).get(0);
        var pt = ctx.getDictionaryDao().findPackageTypes(true).get(0);

        // Operator MUST be blocked from placing new packages directly on a STORAGE shelf
        assertThrows(IllegalArgumentException.class, () ->
                ctx.getInventoryService().buildPreviewPackages(
                        tg, gt, qg, pt, shelf, new BigDecimal("25"), 1, null, "AB", operator.id(), RoleCode.OPERATOR));

        // Pre-save preview generates QR codes WITHOUT persisting anything yet
        int beforeCount = ctx.getPackageDao().findByFilter(hu.due.rewear.model.PackageFilter.empty()).size();
        List<InventoryPackage> previews = ctx.getInventoryService().buildPreviewPackages(
                tg, gt, qg, pt, station, new BigDecimal("25"), 2, null, "AB", operator.id(), RoleCode.OPERATOR);
        assertEquals(2, previews.size());
        assertEquals(beforeCount, ctx.getPackageDao().findByFilter(hu.due.rewear.model.PackageFilter.empty()).size(),
                "Pre-save preview must NOT insert rows into SQLite before confirmation");

        // Committing the preview atomically saves both packages at the operator station
        List<InventoryPackage> committed = ctx.getInventoryService().commitPreviewedPackages(
                previews, station, operator.id(), RoleCode.OPERATOR);
        assertEquals(2, committed.size());
        assertEquals(beforeCount + 2, ctx.getPackageDao().findByFilter(hu.due.rewear.model.PackageFilter.empty()).size());
        assertTrue(ctx.getTransportService().findPutawayQueue().stream()
                .anyMatch(p -> p.qrCode().equals(committed.get(0).qrCode())));
    }

    @Test
    void forkliftPutawayAndOrderStagingFlowMovesStationToShelfToDispatchZone() {
        AppContext ctx = newContext();
        User forklift = ctx.getUserDao().findByUsername("forklift").orElseThrow();
        assertEquals(RoleCode.SUPPLY_DRIVER, forklift.roleCode());
        assertEquals("mark.toth@rewear-wms.hu", forklift.email());

        WarehouseLocation shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0);
        WarehouseLocation dock = ctx.getLocationDao().findByType(LocationType.DISPATCH_ZONE, true).get(0);
        List<InventoryPackage> waiting = ctx.getTransportService().findPutawayQueue();
        assertFalse(waiting.isEmpty(), "Seeded demo should have packages awaiting forklift putaway");

        InventoryPackage pkg = waiting.get(0);
        // Reject putting away to a non-STORAGE target
        assertThrows(IllegalArgumentException.class, () ->
                ctx.getTransportService().putawayToStorage(List.of(pkg.id()), dock.id(), forklift.id()));

        // 1. Forklift moves package from OPERATOR_STATION -> STORAGE shelf
        ctx.getTransportService().putawayToStorage(List.of(pkg.id()), shelf.id(), forklift.id());
        InventoryPackage onShelf = ctx.getPackageDao().findById(pkg.id()).orElseThrow();
        assertEquals(shelf.id(), onShelf.locationId());

        // 2. Manager allocates that package to a new customer order
        User manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        long custId = ctx.getCustomerDao().findAll(true).get(0).id();
        Order order = ctx.getOrderService().createOrder(custId, "EUR", null, manager.id());
        ctx.getOrderService().addPackageToOrder(order.id(), onShelf.id(), new BigDecimal("5.00"), manager.id());

        // Package now appears in Forklift Order Staging queue as needing pickup
        var pendingTask = ctx.getTransportService().findOrderStagingTasks().stream()
                .filter(t -> t.packageId() == onShelf.id())
                .findFirst().orElseThrow();
        assertFalse(pendingTask.isAtDispatchZone());

        // 3. Forklift moves it to DISPATCH_ZONE (DOCK-01)
        User deliveryDriver = ctx.getUserDao().findByUsername("forklift2").orElseThrow();
        assertEquals(RoleCode.DELIVERY_DRIVER, deliveryDriver.roleCode());
        ctx.getTransportService().stageToDispatchZone(List.of(onShelf.id()), dock.id(), deliveryDriver.id());
        var stagedTask = ctx.getTransportService().findOrderStagingTasks().stream()
                .filter(t -> t.packageId() == onShelf.id())
                .findFirst().orElseThrow();
        assertTrue(stagedTask.isAtDispatchZone());
        assertEquals(dock.code(), stagedTask.currentLocationCode());
    }

    @Test
    void emailDomainAutoGenerationSyncAndInternalExternalDelivery() {
        AppContext ctx = newContext();
        User superAdmin = ctx.getUserDao().findByUsername("superadmin").orElseThrow();
        User manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        assertEquals("attila.czudor@rewear-wms.hu", superAdmin.email());
        assertEquals("eszter.kovacs@rewear-wms.hu", manager.email());

        // Super Admin changes domain and syncs all user emails
        EmailSettings updated = new EmailSettings("rewear-logistics.hu",
                "smtp.rewear-logistics.hu", 587, "STARTTLS", "postmaster@rewear-logistics.hu", "secret",
                "imap.rewear-logistics.hu", 993, "SSL/TLS", "postmaster@rewear-logistics.hu", "secret", true);
        ctx.getEmailService().saveSettings(updated);
        assertThrows(IllegalStateException.class, ctx.getEmailService()::syncAllUserEmailsToDomain);
        ctx.getSession().login(superAdmin);
        int synced = ctx.getEmailService().syncAllUserEmailsToDomain();
        assertTrue(synced >= 5);

        User refreshedSuper = ctx.getUserDao().findByUsername("superadmin").orElseThrow();
        User refreshedMgr = ctx.getUserDao().findByUsername("manager").orElseThrow();
        assertEquals("attila.czudor@rewear-logistics.hu", refreshedSuper.email());
        assertEquals("eszter.kovacs@rewear-logistics.hu", refreshedMgr.email());

        // Send internal + external email simultaneously
        int mgrUnreadBefore = ctx.getEmailService().countUnreadInbox(refreshedMgr.id());
        assertTrue(ctx.getEmailService().sendEmail(refreshedSuper,
                refreshedMgr.email() + ", office@alpinesecondhand.at", "",
                "Weekly Dispatch Sync", "Please prepare DOCK-01 for Austria shipment.", null));

        assertEquals(mgrUnreadBefore + 1, ctx.getEmailService().countUnreadInbox(refreshedMgr.id()));
        List<EmailMessage> sent = ctx.getEmailService().listFolder(refreshedSuper.id(), EmailMessage.FOLDER_SENT);
        assertTrue(sent.stream().anyMatch(m -> m.subject().equals("Weekly Dispatch Sync")
                && m.external() && m.deliveryStatus().contains("SIMULATED")));

        // Operator & Forklift Driver are strictly internal-only (cannot send or receive external emails)
        User operator = ctx.getUserDao().findByUsername("operator").orElseThrow();
        User forklift = ctx.getUserDao().findByUsername("forklift").orElseThrow();
        assertThrows(IllegalArgumentException.class, () ->
                ctx.getEmailService().sendEmail(operator, "external@partner.at", "", "Hello", "Test", null));
        assertThrows(IllegalArgumentException.class, () ->
                ctx.getEmailService().sendEmail(forklift, "external@partner.at", "", "Hello", "Test", null));
        assertThrows(IllegalArgumentException.class, () ->
                ctx.getEmailService().simulateIncomingExternalEmail(forklift, "external@partner.at", "Hi", "Body"));

        // Internal email between Forklift Driver and Operator works cleanly
        int opUnreadBefore = ctx.getEmailService().countUnreadInbox(operator.id());
        ctx.getEmailService().sendEmail(forklift, operator.email(), "", "Station pickup", "On my way to ST-OP-01", null);
        assertEquals(opUnreadBefore + 1, ctx.getEmailService().countUnreadInbox(operator.id()));
    }

    @Test
    void elementStyleChatRoomsDirectMessagesAndUnreadCounters() {
        AppContext ctx = newContext();
        User superAdmin = ctx.getUserDao().findByUsername("superadmin").orElseThrow();
        User admin = ctx.getUserDao().findByUsername("admin").orElseThrow();
        User manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        User operator = ctx.getUserDao().findByUsername("operator").orElseThrow();
        User forklift = ctx.getUserDao().findByUsername("forklift").orElseThrow();

        // Operator and Forklift Driver CANNOT create group rooms
        assertThrows(IllegalArgumentException.class, () ->
                ctx.getChatService().createRoom("unauthorized-op", "Nope", operator.id(), List.of(forklift.id())));
        assertThrows(IllegalArgumentException.class, () ->
                ctx.getChatService().createRoom("unauthorized-fk", "Nope", forklift.id(), List.of(operator.id())));

        // Manager creates a room with only Operator & Forklift explicitly passed; SuperAdmin & Admin are auto-included
        long customRoomId = ctx.getChatService().createRoom("dock-coordination", "Dock 01 alerts",
                manager.id(), List.of(operator.id(), forklift.id()));
        List<Long> members = ctx.getChatService().listMemberIds(customRoomId);
        assertTrue(members.contains(superAdmin.id()), "Super Admin must automatically be a member of every room");
        assertTrue(members.contains(admin.id()), "Admin must automatically be a member of every room");
        assertTrue(ctx.getChatService().listRoomsForUser(superAdmin.id()).stream().anyMatch(r -> r.id() == customRoomId));
        assertTrue(ctx.getChatService().listRoomsForUser(admin.id()).stream().anyMatch(r -> r.id() == customRoomId));

        ctx.getChatService().sendMessage(customRoomId, operator.id(), "2 bales ready at ST-OP-01!");

        List<ChatRoom> forkliftRooms = ctx.getChatService().listRoomsForUser(forklift.id());
        ChatRoom custom = forkliftRooms.stream().filter(r -> r.id() == customRoomId).findFirst().orElseThrow();
        assertEquals("#dock-coordination", custom.name());
        assertEquals(1, custom.unreadCount());

        ctx.getChatService().markRoomRead(customRoomId, forklift.id());
        ChatRoom afterRead = ctx.getChatService().listRoomsForUser(forklift.id()).stream()
                .filter(r -> r.id() == customRoomId).findFirst().orElseThrow();
        assertEquals(0, afterRead.unreadCount());

        long dm1 = ctx.getChatService().getOrCreateDirectMessage(operator, forklift);
        long dm2 = ctx.getChatService().getOrCreateDirectMessage(forklift, operator);
        assertEquals(dm1, dm2, "Direct message room between two users must be idempotent");
    }
}
