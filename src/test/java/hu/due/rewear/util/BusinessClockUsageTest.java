package hu.due.rewear.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architectural guard: application code must take the current time from {@link BusinessClock}.
 * <p>
 * A direct {@code LocalDate.now()} reads the machine's own time zone, which silently shifts a record
 * across midnight whenever the application runs outside Hungary. {@code Instant.now()} and
 * {@code Clock.system…} are the same hole with the zone left out, and no test can pin either of them. The
 * same applies to SQLite's {@code datetime('now')}, which is UTC and ignores the business clock entirely —
 * in the bundled DDL as much as in a query. This fails the build the moment any of them is reintroduced.
 */
class BusinessClockUsageTest {

    private static final Pattern SYSTEM_CLOCK_CALL = Pattern.compile(
            "\\b(LocalDate|LocalDateTime|LocalTime|YearMonth|Year|ZonedDateTime|OffsetDateTime|Instant)"
                    + "\\.now\\(\\s*\\)|\\bClock\\.(system|systemUTC|systemDefaultZone)\\(");
    private static final Pattern SQLITE_UTC_CLOCK = Pattern.compile("datetime\\('now'\\)|CURRENT_TIMESTAMP");

    /** Column defaults in the DDL are fallbacks that every DAO overrides, so only DML is checked. */
    private static final String DDL_FILE = "SchemaInitializer.java";

    @Test
    void applicationCodeNeverReadsTheMachineClockDirectly() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("BusinessClock.java")) {
                    continue;
                }
                String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    String trimmed = line.trim();
                    if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    boolean systemClock = SYSTEM_CLOCK_CALL.matcher(line).find();
                    boolean utcSql = !name.equals(DDL_FILE) && SQLITE_UTC_CLOCK.matcher(line).find();
                    if (systemClock || utcSql) {
                        offenders.add(root.relativize(file) + ":" + (i + 1) + "  " + trimmed);
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "Use BusinessClock.today()/now() instead of the machine clock or SQLite's UTC clock:\n"
                        + String.join("\n", offenders));
    }

    /**
     * The bundled DDL is not application code and was never scanned, which is how a column default of
     * {@code datetime('now')} sat in the schema for twenty-five passes. It is UTC, it ignores the business
     * clock, and it writes a moment in a format nothing else uses — three different wrongs in one default
     * that only fires when a writer forgets the column, which is exactly when nobody is looking.
     */
    @Test
    void theBundledSchemaNeverReachesForSqlitesOwnClock() throws IOException {
        Path schema = Path.of("src/main/resources/db/schema.sql");
        List<String> offenders = new ArrayList<>();
        String[] lines = Files.readString(schema, StandardCharsets.UTF_8).split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (SQLITE_UTC_CLOCK.matcher(lines[i]).find()) {
                offenders.add("schema.sql:" + (i + 1) + "  " + lines[i].trim());
            }
        }

        assertTrue(offenders.isEmpty(),
                "Every moment is written by the application from the business clock:\n"
                        + String.join("\n", offenders));
    }
}
