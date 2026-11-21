package hu.due.rewear.config;

import hu.due.rewear.dao.DaoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
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
            ensureUserProfileColumns(conn);
        });
        LOG.info("SQLite schema initialized at {}", dbManager.getDatabasePath());
    }

    private void ensureUserProfileColumns(Connection conn) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(users)")) {
            while (rs.next()) {
                existing.add(rs.getString("name").toLowerCase());
            }
        }
        try (Statement stmt = conn.createStatement()) {
            if (!existing.contains("email")) {
                stmt.execute("ALTER TABLE users ADD COLUMN email TEXT");
            }
            if (!existing.contains("phone")) {
                stmt.execute("ALTER TABLE users ADD COLUMN phone TEXT");
            }
            if (!existing.contains("avatar_png")) {
                stmt.execute("ALTER TABLE users ADD COLUMN avatar_png BLOB");
            }
        }
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
