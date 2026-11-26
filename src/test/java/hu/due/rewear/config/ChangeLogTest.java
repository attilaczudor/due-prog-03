package hu.due.rewear.config;

import hu.due.rewear.dao.ChangeLogDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbox, and the machine each change was made on.
 *
 * <p>Neither was needed while one application opened one file. A handheld that works in a dead spot and
 * merges afterwards needs something to replay and a way to say where each change came from — and the log
 * has to be written by the database rather than by the application, because a write that forgets to log
 * itself is the one defect it exists to prevent.
 */
class ChangeLogTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private ChangeLogDao log;
    private User operator;

    @BeforeEach
    void setUp() {
        BusinessClock.useForTesting(Clock.fixed(
                LocalDate.of(2026, 11, 26).atTime(20, 30).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = open("log");
        log = ctx.getChangeLogDao();
        operator = ctx.getUserDao().findByUsername("operator").orElseThrow();
        ctx.getSession().login(operator);
    }

    @AfterEach
    void tearDown() {
        BusinessClock.resetForTesting();
    }

    private AppContext open(String name) {
        return new AppContext(new DatabaseManager(tempDir.resolve(name + ".db")),
                new AppPreferences(tempDir.resolve(name + ".properties")), I18nManager.getInstance());
    }

    /** Creates one package, the shortest piece of real work there is. */
    private long makeAPackage() {
        var dict = ctx.getDictionaryDao();
        return ctx.getInventoryService().createPackages(
                dict.findTargetGroups(true).get(0), dict.findGarmentTypes(true).get(0),
                dict.findQualityGrades(true).get(0), dict.findPackageTypes(true).get(0),
                ctx.getLocationDao().findByType(LocationType.OPERATOR_STATION, true).get(0),
                new BigDecimal("14.5"), 1, null, null, operator.id()).get(0).id();
    }

    private List<String> triggerNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ what is logged

    @Test
    void theDemoWorkspaceOpensWithNothingInTheOutboxButWhatTheApplicationItselfDid() {
        // Ten thousand seeded rows and not one of them in the log: sample data is not work anybody did.
        // What is there is the handover escalation the record performs by itself at startup, which is a
        // real change to real data and exactly the kind of thing another device has to be told about.
        List<ChangeLogDao.Change> changes = log.since(0, 100);

        assertTrue(changes.size() <= 3, "Only what the application did on the way up: " + changes);
        assertTrue(changes.stream().allMatch(c -> c.tableName().equals("shift_handover_issues")),
                "And that is the escalation of issues nobody had closed: " + changes);
        assertTrue(changes.stream().allMatch(c -> c.operation() == ChangeLogDao.Operation.UPDATE));
    }

    @Test
    void makingSomethingIsLoggedOnceWithTheDeviceItWasMadeOn() {
        long id = makeAPackage();

        List<ChangeLogDao.Change> changes = log.forRow("inventory_packages", id);
        assertEquals(1, changes.size(), "One package, one change: " + changes);
        ChangeLogDao.Change change = changes.get(0);
        assertEquals(ChangeLogDao.Operation.INSERT, change.operation());
        assertEquals(id, change.rowId());
        assertNotNull(change.deviceId(), "Every change says which machine made it");
        assertTrue(change.deviceName().startsWith("DESKTOP-"), change.deviceName());
    }

    @Test
    void changingSomethingIsLoggedAsAChangeToTheSameRow() throws SQLException {
        long id = makeAPackage();
        var shelf = ctx.getLocationDao().findByType(LocationType.STORAGE, true).get(0);
        ctx.getInventoryService().relocatePackage(id, shelf.id(), "Betárolva", operator.id());

        List<ChangeLogDao.Change> changes = log.forRow("inventory_packages", id);
        assertTrue(changes.size() >= 2, "Making it and moving it are two changes: " + changes);
        assertEquals(ChangeLogDao.Operation.INSERT, changes.get(0).operation());
        assertEquals(ChangeLogDao.Operation.UPDATE, changes.get(1).operation());
        // The movement itself is work too, and it is logged in its own right.
        assertFalse(log.since(0, 100).stream()
                        .filter(c -> c.tableName().equals("stock_movements")).toList().isEmpty(),
                "The movement that recorded it is a change as well");
    }

    @Test
    void removingSomethingIsLoggedByTheIdItHad() throws SQLException {
        long id = makeAPackage();
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM stock_movements WHERE package_id = " + id);
            stmt.executeUpdate("DELETE FROM inventory_packages WHERE id = " + id);
        }

        List<ChangeLogDao.Change> changes = log.forRow("inventory_packages", id);
        assertEquals(ChangeLogDao.Operation.DELETE, changes.get(changes.size() - 1).operation(),
                "All that is left of a deleted row is the fact that it is gone, and which one it was");
    }

    @Test
    void theOfficesOwnWorkIsNotInTheOutbox() throws SQLException {
        long before = log.count();

        ctx.getSettingsDao().set("something.unimportant", "1");
        new hu.due.rewear.dao.RoleModulePermissionDao(ctx.getDatabaseManager()).setEnabled(
                hu.due.rewear.model.RoleCode.MANAGER, hu.due.rewear.model.AppModule.INVENTORY, true);

        assertEquals(before, log.count(),
                "Settings, sign-ins and preferences are not changed on a handheld in a dead spot,"
                        + " and an outbox is only owed for what a device can change while out of contact");
    }

    // ------------------------------------------------------------------ the triggers

    @Test
    void theLoggedTablesAreThisListAndAddingToItIsADecision() throws SQLException {
        // Spelled out rather than derived, because every other test here compares the triggers against
        // this same list and would happily agree with it whatever it said. Adding a table means deciding
        // that a device out of contact can change it, and writing down how two copies of it merge.
        assertEquals(List.of(
                        "inventory_packages",
                        "stock_movements",
                        "orders",
                        "order_items",
                        "purchase_lots",
                        "sorting_raw_lots",
                        "sorting_requests",
                        "sorting_request_helpers",
                        "sorting_bags",
                        "downtime_events",
                        "shift_handovers",
                        "shift_handover_issues",
                        "stocktake_sessions",
                        "stocktake_counts"),
                ChangeLog.LOGGED_TABLES);

        List<String> tables = new ArrayList<>();
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        assertTrue(tables.containsAll(ChangeLog.LOGGED_TABLES),
                "A logged table has to exist: " + ChangeLog.LOGGED_TABLES.stream()
                        .filter(t -> !tables.contains(t)).toList());
    }

    @Test
    void everyLoggedTableHasItsThreeTriggersAndNothingElseHasAny() throws SQLException {
        List<String> actual = triggerNames();
        List<String> expected = ChangeLog.triggerNames().stream().sorted().toList();

        assertEquals(expected, actual,
                "The list of logged tables and the triggers in the database are the same list, or one of"
                        + " them is a lie");
        assertEquals(ChangeLog.LOGGED_TABLES.size() * 3, actual.size());
    }

    @Test
    void theLogCanBeTurnedOffForWorkThatIsNotWorkAndComesBackOnAfterwards() throws SQLException {
        long before = log.count();

        ChangeLog.withoutLogging(ctx.getDatabaseManager(), this::makeAPackage);

        assertEquals(before, log.count(), "Nothing was recorded while the log was off");
        assertEquals(ChangeLog.triggerNames().size(), triggerNames().size(), "And it is back on");
        makeAPackage();
        assertTrue(log.count() > before, "Recording again");
    }

    @Test
    void theLogComesBackOnEvenWhenTheWorkFails() throws SQLException {
        assertThrows(IllegalStateException.class, () -> ChangeLog.withoutLogging(
                ctx.getDatabaseManager(), () -> {
                    throw new IllegalStateException("the seeding fell over");
                }));

        assertEquals(ChangeLog.triggerNames().size(), triggerNames().size(),
                "An installation that quietly stops recording is worse than one that fails loudly");
    }

    @Test
    void theSchemasOwnTidyingUpIsNotSomebodyElsesChange() throws SQLException {
        // Put a moment back in the format used before the convention, the way every database written
        // before that pass holds them. Reopening runs the migration that rewrites it.
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE sorting_bags SET reported_at = '2026-11-20 08:00:00'");
        }
        long afterMyOwnChange = log.count();
        assertTrue(afterMyOwnChange > 0, "My own update is a change and is logged");

        open("log");

        assertEquals(afterMyOwnChange, log.count(),
                "Rewriting a column into a new format is the application tidying up after itself. A device"
                        + " syncing afterwards must not be handed every row in the database as though the"
                        + " night shift had touched them all");
    }

    // ------------------------------------------------------------------ the sequence

    @Test
    void theSequenceOnlyEverGoesForwards() throws SQLException {
        makeAPackage();
        long highWater = log.latestSequence();
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM change_log");
        }

        makeAPackage();

        assertTrue(log.latestSequence() > highWater,
                "A number must not be handed out twice: 'everything after 412' has to mean the same thing"
                        + " tomorrow as it does today, even after the log has been trimmed");
    }

    @Test
    void aDeviceAsksForWhatHappenedAfterTheNumberItLastSaw() {
        makeAPackage();
        long seen = log.latestSequence();
        makeAPackage();
        makeAPackage();

        List<ChangeLogDao.Change> since = log.since(seen, 100);
        assertFalse(since.isEmpty());
        assertTrue(since.stream().allMatch(c -> c.sequence() > seen), "Nothing it has already seen");
        assertEquals(log.countSince(seen), since.size());
        assertEquals(1, log.since(seen, 1).size(), "The answer is bounded: it is talking to a handheld");
    }

    // ------------------------------------------------------------------ the device

    @Test
    void thisInstallationRegistersItselfOnceAndIsTheSameMachineTomorrow() throws SQLException {
        long id;
        String name;
        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            id = DeviceRegistry.currentDeviceId(conn).orElseThrow();
            var device = DeviceRegistry.find(conn, id).orElseThrow();
            name = device.name();
            assertEquals(DeviceRegistry.Kind.DESKTOP, device.kind());
            assertNotNull(device.registeredAt());
        }

        open("log");

        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            assertEquals(id, DeviceRegistry.currentDeviceId(conn).orElseThrow(),
                    "Opening the same database again is the same machine, not a new one");
            assertEquals(1, DeviceRegistry.all(conn).size());
            assertEquals(name, DeviceRegistry.find(conn, id).orElseThrow().name());
        }
    }

    @Test
    void theDeviceIsNamedWithoutAskingTheComputerWhoItIs() throws SQLException {
        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            String name = DeviceRegistry.find(conn, DeviceRegistry.currentDeviceId(conn).orElseThrow())
                    .orElseThrow().name();

            assertTrue(name.matches("DESKTOP-[0-9A-F]{8}"), name);
            // A machine name is the owner's, and it travels in a backup bundle that may go somewhere else.
            assertFalse(name.toLowerCase(java.util.Locale.ROOT)
                            .contains(System.getProperty("user.name", "nobody").toLowerCase(java.util.Locale.ROOT)),
                    "Nothing here needs the computer's own name");
        }
    }

    @Test
    void aSecondWorkspaceIsASecondDevice() throws SQLException {
        AppContext other = open("another");

        try (Connection mine = ctx.getDatabaseManager().getConnection();
             Connection theirs = other.getDatabaseManager().getConnection()) {
            assertNotNull(DeviceRegistry.currentDeviceId(theirs).orElse(null));
            assertEquals(1, DeviceRegistry.all(theirs).size(),
                    "Each database knows the machines that have written to it, and this one has had one");
            assertFalse(DeviceRegistry.find(mine, DeviceRegistry.currentDeviceId(mine).orElseThrow())
                            .orElseThrow().name()
                            .equals(DeviceRegistry.find(theirs, DeviceRegistry.currentDeviceId(theirs).orElseThrow())
                                    .orElseThrow().name()),
                    "Two installations are two machines, and a change says which one it came from");
        }
    }

    @Test
    void aWorkspaceCarriedToAnotherMachineKeepsItsHistory() throws SQLException {
        makeAPackage();
        long changes = log.count();

        // The same file, opened again: a restored backup, or a copy taken to another computer.
        AppContext reopened = open("log");

        assertEquals(changes, reopened.getChangeLogDao().count(),
                "The log is part of the data, not of the installation");
        assertEquals(log.latestSequence(), reopened.getChangeLogDao().latestSequence());
    }
}
