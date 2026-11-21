package hu.due.rewear.config;

import hu.due.rewear.dao.DaoException;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
            ensureSuperAdminRoleAndAccount(conn);
        });
        LOG.info("SQLite schema initialized at {}", dbManager.getDatabasePath());
    }

    private void ensureSuperAdminRoleAndAccount(Connection conn) throws SQLException {
        // Only migrate already-seeded databases; brand-new empty databases are seeded by DemoDataSeeder
        long userCount = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next()) {
                userCount = rs.getLong(1);
            }
        }
        if (userCount == 0) {
            return;
        }

        // 1. Ensure SUPER_ADMIN role exists
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT OR IGNORE INTO roles (code, name) VALUES ('SUPER_ADMIN', 'Super Administrator')");
        }
        long superAdminRoleId = -1;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM roles WHERE code = 'SUPER_ADMIN'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                superAdminRoleId = rs.getLong(1);
            }
        }
        if (superAdminRoleId < 0) {
            return;
        }

        // 2. Ensure 'superadmin' account (Czudor Attila) exists
        boolean hasSuperAdmin = false;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE lower(username) = 'superadmin'");
             ResultSet rs = ps.executeQuery()) {
            hasSuperAdmin = rs.next();
        }
        if (!hasSuperAdmin) {
            String hash = BCrypt.hashpw("superadmin123", BCrypt.gensalt(10));
            String created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now());
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password_hash, full_name, role_id, active, created_at) VALUES (?, ?, ?, ?, 1, ?)")) {
                ps.setString(1, "superadmin");
                ps.setString(2, hash);
                ps.setString(3, "Czudor Attila");
                ps.setLong(4, superAdminRoleId);
                ps.setString(5, created);
                ps.executeUpdate();
            }
        }

        // 3. Reassign legacy 'admin' display name away from Attila Czudor so Czudor Attila is exclusively Super Admin
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET full_name = 'Szabó Gábor' WHERE lower(username) = 'admin' AND (full_name LIKE '%Attila Czudor%' OR full_name LIKE '%Czudor Attila%')")) {
            ps.executeUpdate();
        }
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
