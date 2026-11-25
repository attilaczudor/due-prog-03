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
 * across midnight whenever the application runs outside Hungary. The same applies to SQLite's
 * {@code datetime('now')}, which is UTC. This fails the build the moment either is reintroduced.
 */
class BusinessClockUsageTest {

    private static final Pattern SYSTEM_CLOCK_CALL = Pattern.compile(
            "\\b(LocalDate|LocalDateTime|LocalTime|YearMonth|Year|ZonedDateTime|OffsetDateTime)\\.now\\(\\s*\\)");
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
}
