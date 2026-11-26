package hu.due.rewear.config;

import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The number the database carries about itself.
 *
 * <p>There was none for twenty-five passes. The schema was kept current by a run of idempotent steps and the
 * only way to ask what a given file had been through was to look for the columns a particular step adds.
 * That answer cannot be given over a wire, which is the first thing a handheld needs.
 */
class SchemaVersionTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        BusinessClock.useForTesting(Clock.fixed(
                LocalDate.of(2026, 11, 26).atTime(15, 30).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));
        I18nManager.getInstance().loadBundledDefault();
    }

    @AfterEach
    void tearDown() {
        BusinessClock.resetForTesting();
    }

    private AppContext open(String name) {
        return new AppContext(new DatabaseManager(tempDir.resolve(name + ".db")),
                new AppPreferences(tempDir.resolve(name + ".properties")), I18nManager.getInstance());
    }

    @Test
    void aNewDatabaseSaysWhichVersionItWasCreatedAtAndWhen() throws SQLException {
        AppContext ctx = open("fresh");

        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            assertEquals(SchemaVersion.CURRENT, SchemaVersion.current(conn).orElseThrow(),
                    "A database that has just been created is at the current version");
            List<SchemaVersion.Reached> history = SchemaVersion.history(conn);
            assertEquals(1, history.size());
            assertFalse(history.get(0).predatesTheRecord(),
                    "This run created it, so this run knows when");
            assertNotNull(history.get(0).appliedAt());
            assertEquals(2026, history.get(0).appliedAt().getYear());
            assertTrue(history.get(0).note().contains("instants"),
                    "The note says what the version is, for somebody reading the table: "
                            + history.get(0).note());
        }
    }

    @Test
    void aDatabaseFromBeforeVersioningIsRecordedWithoutInventingADate() throws SQLException {
        AppContext ctx = open("older");
        // Take the record away, leaving the schema itself exactly as it is: this is what every database
        // written before this pass looks like.
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP TABLE schema_versions");
            assertTrue(SchemaVersion.current(conn).isEmpty(), "No record at all, as of a moment ago");
        }

        open("older");

        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            SchemaVersion.Reached reached = SchemaVersion.history(conn).get(0);
            assertEquals(SchemaVersion.CURRENT, reached.version());
            assertTrue(reached.predatesTheRecord(),
                    "Nobody wrote down when this database was brought up to date, and a plausible date"
                            + " would be worse than an admitted gap");
            assertNotNull(reached.recordedAt(), "What is known is when the record started");
            assertTrue(reached.note().contains("already current"), reached.note());
        }
    }

    @Test
    void aVersionIsRecordedOnceAndTheDateItWasReachedNeverMoves() throws SQLException {
        AppContext ctx = open("twice");
        java.time.LocalDateTime first;
        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            first = SchemaVersion.history(conn).get(0).recordedAt();
            assertNotNull(first);
        }

        // A week later, the same database opened again.
        BusinessClock.useForTesting(Clock.fixed(
                LocalDate.of(2026, 12, 3).atTime(9, 0).atZone(BusinessClock.ZONE).toInstant(),
                BusinessClock.ZONE));
        open("twice");
        open("twice");

        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            List<SchemaVersion.Reached> history = SchemaVersion.history(conn);
            assertEquals(1, history.size(),
                    "A version is reached once, however many times the application starts");
            assertEquals(first, history.get(0).recordedAt(),
                    "And the date it was reached is a record, not a last-seen stamp");
        }
    }

    @Test
    void theVersionCanBeReadWithoutTheApplication() throws SQLException {
        AppContext ctx = open("plain");

        // This is the query the other end of a sync would run: no classes, no migrations, one table.
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT MAX(version) FROM schema_versions")) {
            assertTrue(rs.next());
            assertEquals(SchemaVersion.CURRENT, rs.getInt(1));
        }
    }
}
