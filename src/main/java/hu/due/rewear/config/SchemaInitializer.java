package hu.due.rewear.config;

import hu.due.rewear.dao.DaoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.stream.Collectors;

/**
 * Executes the bundled SQLite 3NF schema DDL idempotently on application startup.
 */
public final class SchemaInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final String SCHEMA_RESOURCE = "/db/schema.sql";

    private final DatabaseManager dbManager;

    public SchemaInitializer(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void initialize() {
        String sql = loadSchemaSql();
        dbManager.runInTransaction(conn -> {
            try (Statement stmt = conn.createStatement()) {
                for (String raw : sql.split(";")) {
                    String trimmed = raw.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
            }
        });
        LOG.info("SQLite schema initialized at {}", dbManager.getDatabasePath());
    }

    private String loadSchemaSql() {
        try (InputStream is = SchemaInitializer.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (is == null) {
                throw new DaoException("Missing schema resource: " + SCHEMA_RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .filter(line -> !line.trim().startsWith("--"))
                        .collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new DaoException("Failed to read schema.sql", e);
        }
    }
}
