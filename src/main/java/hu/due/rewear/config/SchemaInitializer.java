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
            ensureMultiCurrencySchema(conn);
        });
        LOG.info("SQLite schema initialized at {}", dbManager.getDatabasePath());
    }

    private void ensureMultiCurrencySchema(Connection conn) throws SQLException {
        // 1. Seed default FX rate & reporting currency in app_settings if missing
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('fx.eur_huf', '400.00')");
            stmt.execute("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('finance.reporting_currency', 'HUF')");
        }

        // 2. Add dual-currency columns to existing tables if upgrading from an older DB
        addColumnIfMissing(conn, "garment_types", "price_eur_per_kg", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing(conn, "garment_types", "price_huf_per_kg", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing(conn, "quality_grades", "default_price_huf_per_kg", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing(conn, "customers", "preferred_currency", "TEXT NOT NULL DEFAULT 'EUR'");
        addColumnIfMissing(conn, "orders", "currency", "TEXT NOT NULL DEFAULT 'EUR'");
        addColumnIfMissing(conn, "invoices", "exchange_rate", "REAL NOT NULL DEFAULT 400.0");

        // 3. Backfill realistic EUR/kg & HUF/kg product prices on existing garment_types that still have 0/0
        String[][] defaultGarmentPrices = {
                {"TSH", "4.50", "1800"},
                {"JNS", "5.50", "2200"},
                {"JKT", "7.50", "3000"},
                {"DRS", "6.00", "2400"},
                {"SWT", "5.00", "2000"},
                {"SHO", "8.00", "3200"},
                {"MIX", "3.50", "1400"}
        };
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE garment_types SET price_eur_per_kg = ?, price_huf_per_kg = ? "
                        + "WHERE upper(code) = ? AND price_eur_per_kg = 0 AND price_huf_per_kg = 0")) {
            for (String[] row : defaultGarmentPrices) {
                ps.setDouble(1, Double.parseDouble(row[1]));
                ps.setDouble(2, Double.parseDouble(row[2]));
                ps.setString(3, row[0]);
                ps.executeUpdate();
            }
        }

        // 4. Backfill HUF/kg fallback prices on existing quality_grades when 0
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE quality_grades SET default_price_huf_per_kg = ROUND(default_price_per_kg * 400.0, 0) "
                    + "WHERE default_price_huf_per_kg = 0 AND default_price_per_kg > 0");
            // Default Hungarian customers (.hu email) to HUF if still set to default EUR and untouched
            stmt.execute("UPDATE customers SET preferred_currency = 'HUF' "
                    + "WHERE preferred_currency = 'EUR' AND lower(COALESCE(email, '')) LIKE '%.hu'");
        }
    }

    private void addColumnIfMissing(Connection conn, String table, String column, String definition) throws SQLException {
        Set<String> cols = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name").toLowerCase());
            }
        }
        if (!cols.contains(column.toLowerCase())) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
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
                    "INSERT INTO users (username, password_hash, full_name, last_name, first_name, role_id, active, created_at) VALUES (?, ?, ?, ?, ?, ?, 1, ?)")) {
                ps.setString(1, "superadmin");
                ps.setString(2, hash);
                ps.setString(3, "Czudor Attila");
                ps.setString(4, "Czudor");
                ps.setString(5, "Attila");
                ps.setLong(6, superAdminRoleId);
                ps.setString(7, created);
                ps.executeUpdate();
            }
        }

        // 3. Reassign legacy 'admin' display name away from Attila Czudor so Czudor Attila is exclusively Super Admin
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET full_name = 'Szabó Gábor', last_name = 'Szabó', first_name = 'Gábor' WHERE lower(username) = 'admin' AND (full_name LIKE '%Attila Czudor%' OR full_name LIKE '%Czudor Attila%')")) {
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
            if (!existing.contains("last_name")) {
                stmt.execute("ALTER TABLE users ADD COLUMN last_name TEXT");
            }
            if (!existing.contains("first_name")) {
                stmt.execute("ALTER TABLE users ADD COLUMN first_name TEXT");
            }
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
        backfillSplitUserNames(conn);
    }

    private void backfillSplitUserNames(Connection conn) throws SQLException {
        try (PreparedStatement sel = conn.prepareStatement("SELECT id, full_name, last_name, first_name FROM users");
             ResultSet rs = sel.executeQuery();
             PreparedStatement upd = conn.prepareStatement("UPDATE users SET full_name = ?, last_name = ?, first_name = ? WHERE id = ?")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String rawFull = rs.getString("full_name");
                String last = rs.getString("last_name");
                String first = rs.getString("first_name");

                String cleanFull = rawFull == null ? "" : rawFull.replaceAll("\\s*\\([^)]*\\)\\s*", " ").trim();
                boolean needsRoleStrip = rawFull != null && !rawFull.equals(cleanFull);
                if ((last == null || last.isBlank()) && (first == null || first.isBlank())) {
                    String[] parts = cleanFull.split("\\s+", 2);
                    last = parts.length > 0 ? parts[0] : "";
                    first = parts.length > 1 ? parts[1] : "";
                    upd.setString(1, cleanFull);
                    upd.setString(2, last);
                    upd.setString(3, first);
                    upd.setLong(4, id);
                    upd.executeUpdate();
                } else if (needsRoleStrip) {
                    upd.setString(1, cleanFull);
                    upd.setString(2, last);
                    upd.setString(3, first);
                    upd.setLong(4, id);
                    upd.executeUpdate();
                }
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
