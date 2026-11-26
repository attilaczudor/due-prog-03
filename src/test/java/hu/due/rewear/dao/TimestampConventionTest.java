package hu.due.rewear.dao;

import hu.due.rewear.TestWorkspaces;
import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One way of writing a moment down, held to by the database itself.
 *
 * <p>Two conventions were in use until this pass: the sorting module stored UTC instants and everything else
 * stored local date-times. Nothing enforced either, which is why a seeder once wrote local times into instant
 * columns and every sorting screen threw. A guard test is the only thing that stops that happening again,
 * because the wrong format is not a compile error and usually not even a visible one — until the night a
 * record lands on the wrong day.
 *
 * <p>These read the database rather than the code: whatever wrote a value, and whatever route it took to get
 * there, it is in the one format or it is not.
 */
class TimestampConventionTest {

    /** A business date: a day, with no clock on it at all. */
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** Anything that looks like a date with a time after it, however it is punctuated. */
    private static final Pattern LOOKS_LIKE_A_MOMENT =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}.*");

    @TempDir
    Path tempDir;

    private AppContext ctx;

    @BeforeEach
    void setUp() {
        BusinessClock.useForTesting(Clock.fixed(
                LocalDate.of(2026, 11, 26).atTime(15, 30).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(new DatabaseManager(tempDir.resolve("moments.db")),
                new AppPreferences(tempDir.resolve("moments.properties")), i18n);
    }

    @AfterEach
    void tearDown() {
        BusinessClock.resetForTesting();
    }

    private record Column(String table, String name) {
        @Override
        public String toString() {
            return table + "." + name;
        }
    }

    private List<Column> columns(Connection conn) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'"
                     + " AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        List<Column> columns = new ArrayList<>();
        for (String table : tables) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
                while (rs.next()) {
                    columns.add(new Column(table, rs.getString("name")));
                }
            }
        }
        return columns;
    }

    private List<String> valuesOf(Connection conn, Column column) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT \"" + column.name() + "\" FROM \"" + column.table()
                     + "\" WHERE \"" + column.name() + "\" IS NOT NULL")) {
            while (rs.next()) {
                String value = rs.getString(1);
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    // ------------------------------------------------------- what the database holds

    @Test
    void everyStoredMomentIsAUtcInstantOfWholeSeconds() throws SQLException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            for (Column column : columns(conn)) {
                if (!column.name().endsWith("_at")) {
                    continue;
                }
                for (String value : valuesOf(conn, column)) {
                    checked++;
                    if (!DaoDateUtil.isCanonicalMoment(value)) {
                        offenders.add(column + " = " + value);
                    }
                }
            }
        }
        assertTrue(checked > 100, "Expected the demo workspace to be full of moments, found " + checked);
        assertTrue(offenders.isEmpty(),
                "A moment must be written as a UTC instant of whole seconds (2026-11-26T07:12:00Z):\n"
                        + String.join("\n", offenders.subList(0, Math.min(20, offenders.size()))));
    }

    @Test
    void nothingOutsideAnAtColumnHoldsAMoment() throws SQLException {
        List<String> offenders = new ArrayList<>();
        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            for (Column column : columns(conn)) {
                if (column.name().endsWith("_at")) {
                    continue;
                }
                for (String value : valuesOf(conn, column)) {
                    if (LOOKS_LIKE_A_MOMENT.matcher(value).matches()) {
                        offenders.add(column + " = " + value);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "A column holding a moment must say so in its name, by ending in _at:\n"
                        + String.join("\n", offenders.subList(0, Math.min(20, offenders.size()))));
    }

    @Test
    void aBusinessDateIsADayAndNothingElse() throws SQLException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            for (Column column : columns(conn)) {
                String name = column.name();
                boolean businessDate = name.endsWith("_day") || name.endsWith("_date")
                        || name.endsWith("_on") || name.endsWith("_from") || name.endsWith("_until");
                if (!businessDate) {
                    continue;
                }
                for (String value : valuesOf(conn, column)) {
                    checked++;
                    if (!DATE.matcher(value).matches()) {
                        offenders.add(column + " = " + value);
                    }
                }
            }
        }
        assertTrue(checked > 20, "Expected the demo workspace to hold business dates, found " + checked);
        assertTrue(offenders.isEmpty(),
                "A business date is the day the work belongs to, with no clock on it:\n"
                        + String.join("\n", offenders));
    }

    // ------------------------------------------------------------ reading and writing

    @Test
    void aMomentSurvivesBeingWrittenAndReadBack() {
        LocalDateTime midnightThirty = LocalDateTime.of(2026, 11, 25, 0, 30);

        String stored = DaoDateUtil.format(midnightThirty);

        assertEquals("2026-11-24T23:30:00Z", stored,
                "Half past midnight in Budapest in November is half past eleven the evening before, in UTC");
        assertEquals(midnightThirty, DaoDateUtil.parse(stored), "And it reads back as the time it was");
    }

    @Test
    void theOffsetIsTheOneThatWasInForceThatDay() {
        // Summer time: Budapest is two hours ahead of UTC in July and one in November. A fixed offset — or
        // SQLite, which has no time zone database at all — would put one of these an hour out.
        assertEquals("2026-07-15T10:00:00Z", DaoDateUtil.format(LocalDateTime.of(2026, 7, 15, 12, 0)));
        assertEquals("2026-11-15T11:00:00Z", DaoDateUtil.format(LocalDateTime.of(2026, 11, 15, 12, 0)));
    }

    @Test
    void aMomentWrittenBeforeThisConventionIsReadAsTheBudapestTimeItMeant() {
        Instant legacy = DaoDateUtil.parseInstant("2026-11-25 00:30:00");

        assertEquals(Instant.parse("2026-11-24T23:30:00Z"), legacy,
                "A local date-time written before the convention meant Budapest, the only clock there was");
        assertEquals(legacy, DaoDateUtil.parseInstant("2026-11-24T23:30:00Z"),
                "Both ways of writing it name the same moment");
    }

    @Test
    void aMomentIsAlwaysTwentyCharactersSoThatTextComparisonsHold() {
        String withFraction = DaoDateUtil.format(Instant.parse("2026-11-26T07:12:00.123456Z"));

        assertEquals("2026-11-26T07:12:00Z", withFraction);
        assertEquals(20, withFraction.length());
        assertEquals(Instant.parse("2026-11-26T07:12:00Z"),
                DaoDateUtil.parseInstant("2026-11-26T07:12:00.123456Z"),
                "And reading one back drops the fraction too, so a value and its re-reading are equal");
        // Without truncation this is the trap: a fraction sorts before the same second without one, so
        // "the first moment of the shift" would quietly include the second before it.
        assertTrue("2026-11-26T07:12:00.123Z".compareTo("2026-11-26T07:12:00Z") < 0);
    }

    @Test
    void aLocalDayIsAskedForAsARangeOfMoments() {
        String[] day = DaoDateUtil.dayRange(LocalDate.of(2026, 11, 26));

        assertEquals("2026-11-25T23:00:00Z", day[0], "The Budapest day starts an hour before UTC's does");
        assertEquals("2026-11-26T23:00:00Z", day[1]);
        assertNotEquals(day[0].substring(0, 10), "2026-11-26",
                "Which is exactly why a day is not the first ten characters of a moment");
    }

    @Test
    void theEveningsIntakeIsCountedOnTheDayItHappened() {
        // Half past eleven at night in Budapest is already tomorrow in UTC. Counting by the first ten
        // characters of the stored moment would put this package on the 27th.
        BusinessClock.useForTesting(Clock.fixed(
                LocalDate.of(2026, 11, 26).atTime(23, 30).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));
        AppContext evening = new AppContext(new DatabaseManager(tempDir.resolve("evening.db")),
                new AppPreferences(tempDir.resolve("evening.properties")), I18nManager.getInstance());
        TestWorkspaces.clearSeededSortingHistory(evening);
        int before = evening.getPackageDao().countCreatedOnDate(LocalDate.of(2026, 11, 26));

        var operator = evening.getUserDao().findByUsername("operator").orElseThrow();
        evening.getSession().login(operator);
        var dict = evening.getDictionaryDao();
        evening.getInventoryService().createPackages(
                dict.findTargetGroups(true).get(0), dict.findGarmentTypes(true).get(0),
                dict.findQualityGrades(true).get(0), dict.findPackageTypes(true).get(0),
                evening.getLocationDao().findByType(hu.due.rewear.model.LocationType.OPERATOR_STATION, true).get(0),
                new java.math.BigDecimal("12.5"), 1, null, null, operator.id());

        assertEquals(before + 1, evening.getPackageDao().countCreatedOnDate(LocalDate.of(2026, 11, 26)),
                "A package made at half past eleven belongs to the day the operator was working");
        assertEquals(0, evening.getPackageDao().countCreatedOnDate(LocalDate.of(2026, 11, 27)),
                "And not to the day UTC had already reached");
    }

    @Test
    void theNightShiftsWaitForMaterialIsCountedOnTheDayItHappened() {
        // Half past midnight in Budapest is still the previous day in UTC. Asking for the day by comparing
        // dates would leave the night shift's waiting out of its own day's figures.
        BusinessClock.useForTesting(Clock.fixed(
                LocalDate.of(2026, 11, 26).atTime(0, 30).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));
        AppContext night = new AppContext(new DatabaseManager(tempDir.resolve("night.db")),
                new AppPreferences(tempDir.resolve("night.properties")), I18nManager.getInstance());
        TestWorkspaces.clearSeededSortingHistory(night);

        var manager = night.getUserDao().findByUsername("manager").orElseThrow();
        var sorter = night.getUserDao().findByUsername("sorter01").orElseThrow();
        var driver = night.getUserDao().findByUsername("forklift").orElseThrow();
        night.getSession().login(manager);
        var sorting = night.getSortingService();
        long lot = sorting.receive("RAW-NIGHT", "Vegyes", "Dunaújváros", new java.math.BigDecimal("900"));
        sorting.storeLot(lot, night.getLocationDao()
                .findByType(hu.due.rewear.model.LocationType.STORAGE, true).get(0).id());
        night.getSession().login(sorter);
        long request = sorting.requestSupply(lot);

        BusinessClock.useForTesting(Clock.fixed(
                LocalDate.of(2026, 11, 26).atTime(0, 55).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));
        night.getSession().login(driver);
        sorting.deliver(request, new java.math.BigDecimal("200"));

        night.getSession().login(manager);
        var report = night.getDowntimeService()
                .report(new DowntimeDao.Scope(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 26), null, null));

        assertEquals(25, report.waitingForStockMinutes(),
                "The sorter waited twenty-five minutes on the 26th, whatever date UTC was on");
    }

    // ------------------------------------------------------------------ the migration

    @Test
    void anOlderDatabaseIsBroughtIntoTheOneFormatWithoutChangingAnyMoment() throws SQLException {
        Instant moment;
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement()) {
            // Put the two older spellings back: a local date-time, and an instant with a fraction on it.
            stmt.executeUpdate("UPDATE notifications SET created_at = '2026-11-25 00:30:00'");
            stmt.executeUpdate("UPDATE chat_messages SET created_at = '2026-11-25T10:20:30.456Z'");
            moment = Instant.parse("2026-11-24T23:30:00Z");
        }

        // Opening the workspace again is what converts them.
        new AppContext(new DatabaseManager(tempDir.resolve("moments.db")),
                new AppPreferences(tempDir.resolve("moments.properties")), I18nManager.getInstance());

        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT DISTINCT created_at FROM notifications")) {
                assertTrue(rs.next(), "The demo workspace has notifications in it");
                assertEquals(DaoDateUtil.format(moment), rs.getString(1),
                        "The same moment, written the one way, not shifted by an hour");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT DISTINCT created_at FROM chat_messages")) {
                assertTrue(rs.next());
                assertEquals("2026-11-25T10:20:30Z", rs.getString(1), "And the fraction is gone");
            }
        }
    }

    @Test
    void convertingADatabaseThatIsAlreadyConvertedChangesNothing() throws SQLException {
        List<String> before = allMoments();

        new AppContext(new DatabaseManager(tempDir.resolve("moments.db")),
                new AppPreferences(tempDir.resolve("moments.properties")), I18nManager.getInstance());

        assertEquals(before, allMoments(), "A second run is a no-op, so it can be interrupted and resumed");
        assertTrue(before.size() > 100);
    }

    @Test
    void aNullMomentStaysNull() throws SQLException {
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE sorting_requests SET finished_at = NULL, processed_grams = NULL,"
                    + " work_day = NULL");
        }

        new AppContext(new DatabaseManager(tempDir.resolve("moments.db")),
                new AppPreferences(tempDir.resolve("moments.properties")), I18nManager.getInstance());

        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM sorting_requests WHERE finished_at IS NOT NULL")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "A run that never finished has no moment to convert");
        }
        assertNull(DaoDateUtil.format((Instant) null));
        assertNull(DaoDateUtil.parse(null));
        assertNull(DaoDateUtil.parse("  "));
    }

    private List<String> allMoments() throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            for (Column column : columns(conn)) {
                if (column.name().endsWith("_at")) {
                    values.addAll(valuesOf(conn, column));
                }
            }
        }
        return values;
    }
}
