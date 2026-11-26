package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.DeviceRegistry;
import hu.due.rewear.dao.ChangeLogDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.SyncConflict;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when a second machine brings back work done out of contact.
 *
 * <p>There is no second machine and no transport, so these tests play one: a set of changes another device
 * says it made, offered to this database. That is enough to pin the part that has a right answer — which
 * changes can be taken without losing anything, and which are disagreements nobody may settle by rule.
 *
 * <p>The thing being tested is a refusal as much as a decision. Last-write-wins would pass a test suite
 * like this one happily and throw away a correct answer on the floor, so what is checked here is that the
 * cases where no rule can be right are kept for a person, with both accounts and a sentence about what was
 * decided.
 */
class SyncServiceTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private SyncService sync;
    private ChangeLogDao log;
    private User manager;
    private User operator;
    private long handheld;

    @BeforeEach
    void setUp() throws SQLException {
        BusinessClock.useForTesting(Clock.fixed(
                LocalDate.of(2026, 11, 26).atTime(21, 0).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(new DatabaseManager(tempDir.resolve("sync.db")),
                new AppPreferences(tempDir.resolve("sync.properties")), i18n);
        sync = ctx.getSyncService();
        log = ctx.getChangeLogDao();
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        operator = ctx.getUserDao().findByUsername("operator").orElseThrow();
        ctx.getSession().login(manager);
        handheld = registerHandheld();
    }

    @AfterEach
    void tearDown() {
        BusinessClock.resetForTesting();
    }

    /** The machine that has been out in the warehouse. It does not exist; its row does. */
    private long registerHandheld() throws SQLException {
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             var ps = conn.prepareStatement("INSERT INTO devices(name, kind, registered_at)"
                     + " VALUES ('HANDHELD-01','HANDHELD',?) RETURNING id")) {
            ps.setString(1, hu.due.rewear.dao.DaoDateUtil.now());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long makeAPackage() {
        var dict = ctx.getDictionaryDao();
        return ctx.getInventoryService().createPackages(
                dict.findTargetGroups(true).get(0), dict.findGarmentTypes(true).get(0),
                dict.findQualityGrades(true).get(0), dict.findPackageTypes(true).get(0),
                ctx.getLocationDao().findByType(LocationType.OPERATOR_STATION, true).get(0),
                new BigDecimal("18.5"), 1, null, null, manager.id()).get(0).id();
    }

    private SyncService.IncomingChange theirChange(String table, long rowId) {
        return new SyncService.IncomingChange(table, rowId, ChangeLogDao.Operation.UPDATE,
                7, "HANDHELD-01", "Balogh Anna");
    }

    // ------------------------------------------------------------ what can simply be taken

    @Test
    void aRowWeHaveNotTouchedSinceTheyLastHeardFromUsIsTheirsToChange() {
        long id = makeAPackage();
        // They have seen everything we have: the sync is up to date as of now.
        sync.recordSync(handheld, log.latestSequence(), 0);

        List<SyncService.Decision> decisions = sync.classify(handheld,
                List.of(theirChange("inventory_packages", id)));

        assertEquals(SyncService.Outcome.TAKE, decisions.get(0).outcome(),
                "Nothing of ours stands in the way");
        assertEquals(0, sync.countOpenConflicts());
    }

    @Test
    void anInsertOnlyTableNeedsNoAgreementAtAll() {
        long id = makeAPackage();
        // We have moved it since they last heard from us, so there is a movement of ours they have not seen.
        var shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0);
        ctx.getInventoryService().relocatePackage(id, shelf.id(), "Betárolva", manager.id());
        long movement = log.since(0, 500).stream()
                .filter(c -> c.tableName().equals("stock_movements")).findFirst().orElseThrow().rowId();

        List<SyncService.Decision> decisions = sync.classify(handheld,
                List.of(theirChange("stock_movements", movement)));

        assertEquals(SyncService.Outcome.TAKE, decisions.get(0).outcome(),
                "Neither machine edits a movement: they each add their own, and both are true");
        assertEquals(0, sync.countOpenConflicts());
    }

    // ------------------------------------------------------------ what is held for a person

    @Test
    void aRowBothMachinesChangedIsHeldWithBothAccountsOnIt() {
        long id = makeAPackage();
        sync.recordSync(handheld, log.latestSequence(), 0);
        // Our shift moves it after they last heard from us; theirs moved it too, out in the warehouse.
        var shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0);
        ctx.getSession().login(operator);
        ctx.getInventoryService().relocatePackage(id, shelf.id(), "Betárolva", operator.id());
        ctx.getSession().login(manager);

        List<SyncService.Decision> decisions = sync.classify(handheld,
                List.of(theirChange("inventory_packages", id)));

        assertEquals(SyncService.Outcome.HELD, decisions.get(0).outcome(),
                "A package is in one place, and one of these two is describing a shelf it is not on");
        assertNotNull(decisions.get(0).conflictId());
        assertTrue(decisions.get(0).reason().contains("one place"), decisions.get(0).reason());

        SyncConflict conflict = sync.openConflicts().get(0);
        assertEquals("inventory_packages", conflict.tableName());
        assertEquals(id, conflict.rowId());
        assertEquals("HANDHELD-01", conflict.theirDevice());
        assertEquals("Balogh Anna", conflict.theirUser());
        assertTrue(conflict.ourDeviceName().startsWith("DESKTOP-"), conflict.between());
        assertEquals(operator.displayName(), conflict.ourUserName(),
                "Both sides name a person, because settling this means asking the two of them");
    }

    @Test
    void theOrderOfEventsIsWhatDecidesWhetherThereIsADisagreementAtAll() {
        long id = makeAPackage();
        var shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0);
        ctx.getInventoryService().relocatePackage(id, shelf.id(), "Betárolva", manager.id());

        // They have not seen any of it: every change of ours is news, so theirs was made blind.
        sync.recordSync(handheld, 0, 0);
        assertEquals(SyncService.Outcome.HELD,
                sync.classify(handheld, List.of(theirChange("inventory_packages", id))).get(0).outcome());

        // Told that they are up to date, the same change of theirs is simply the next thing that happened.
        sync.recordSync(handheld, log.latestSequence(), 0);
        assertEquals(SyncService.Outcome.TAKE,
                sync.classify(handheld, List.of(theirChange("inventory_packages", id))).get(0).outcome());
    }

    @Test
    void howFarTwoMachinesHaveGotWithEachOtherOnlyEverMovesForward() {
        sync.recordSync(handheld, 40, 12);
        sync.recordSync(handheld, 20, 30);

        var state = sync.stateFor(handheld);
        assertEquals(40, state.sentThrough(), "A later sync cannot un-send what was already sent");
        assertEquals(30, state.receivedThrough());
        assertNotNull(state.lastSyncedAt());
    }

    @Test
    void aDeviceNobodyHasSyncedWithStartsAtTheBeginning() {
        assertEquals(0, sync.stateFor(handheld).sentThrough(),
                "Everything in the log is news to it, which is why a first sync is a bootstrap");
        assertNull(sync.stateFor(handheld).lastSyncedAt());
    }

    // ------------------------------------------------------------ settling one

    @Test
    void settlingADisagreementTakesASentenceAndHappensOnce() {
        long id = makeAPackage();
        sync.recordSync(handheld, 0, 0);
        long conflict = sync.classify(handheld, List.of(theirChange("inventory_packages", id)))
                .get(0).conflictId();

        assertThrows(IllegalArgumentException.class, () -> sync.resolve(conflict, "  "),
                "A disagreement settled without a record is one that comes back as 'nobody knows why'");

        sync.resolve(conflict, "Megkérdeztem Annát: a polcon van, a kézi eszköz verziója a jó.");

        assertEquals(0, sync.countOpenConflicts());
        SyncConflict settled = ctx.getSyncConflictDao().find(conflict).orElseThrow();
        assertFalse(settled.open());
        assertEquals(manager.displayName(), settled.resolvedByName());
        assertTrue(settled.resolution().startsWith("Megkérdeztem"));
        assertThrows(IllegalStateException.class,
                () -> sync.resolve(conflict, "Mégis a miénk a jó."),
                "Settled is settled");
    }

    @Test
    void settlingOneIsTheProductionLeadershipsJob() {
        long id = makeAPackage();
        sync.recordSync(handheld, 0, 0);
        long conflict = sync.classify(handheld, List.of(theirChange("inventory_packages", id)))
                .get(0).conflictId();

        ctx.getSession().login(ctx.getUserDao().findByUsername("sorter01").orElseThrow());

        assertThrows(IllegalStateException.class, () -> sync.resolve(conflict, "Az enyém a jó."),
                "A sorter whose own run is in dispute is the person to ask, not the person to decide");
        assertThrows(IllegalStateException.class,
                () -> sync.classify(handheld, List.of(theirChange("inventory_packages", id))));
    }

    @Test
    void aBatchIsDecidedChangeByChange() {
        long kept = makeAPackage();
        long contested = makeAPackage();
        sync.recordSync(handheld, log.latestSequence(), 0);
        var shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0);
        ctx.getInventoryService().relocatePackage(contested, shelf.id(), "Betárolva", manager.id());

        List<SyncService.Decision> decisions = sync.classify(handheld, List.of(
                theirChange("inventory_packages", kept),
                theirChange("inventory_packages", contested)));

        assertEquals(SyncService.Outcome.TAKE, decisions.get(0).outcome());
        assertEquals(SyncService.Outcome.HELD, decisions.get(1).outcome());
        assertEquals(1, sync.countOpenConflicts(),
                "One disagreement out of two changes, rather than the whole batch refused");
    }

    // ------------------------------------------------------------ who made the change

    @Test
    void aChangeNamesThePersonAtTheKeyboardAsWellAsTheMachine() {
        ctx.getSession().login(operator);
        long id = makeAPackage();
        ctx.getSession().login(manager);

        ChangeLogDao.Change change = log.forRow("inventory_packages", id).get(0);

        assertEquals(operator.id(), change.userId(), "The person who was signed in when it happened");
        assertEquals(operator.displayName(), change.madeBy());
        assertNotNull(change.deviceId(), "And the machine they were sitting at");
    }

    @Test
    void aChangeNobodyMadeNamesNobody() throws SQLException {
        ctx.getSession().logout();
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE shift_handover_issues SET escalated_at = NULL WHERE id = 1");
        }

        ChangeLogDao.Change change = log.since(0, 1000).get(log.since(0, 1000).size() - 1);

        assertNull(change.userId(),
                "The escalation that runs at startup and the application's own housekeeping are changes"
                        + " with a machine behind them and no person");
        assertNotNull(change.deviceName());
    }

    @Test
    void thisDatabaseKnowsWhichMachinesHaveWrittenToIt() throws SQLException {
        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            var devices = DeviceRegistry.all(conn);

            assertEquals(2, devices.size(), "This installation and the handheld that has been registered");
            assertTrue(devices.stream().anyMatch(d -> d.kind() == DeviceRegistry.Kind.HANDHELD));
            assertTrue(devices.stream().anyMatch(d -> d.kind() == DeviceRegistry.Kind.DESKTOP));
        }
    }
}
