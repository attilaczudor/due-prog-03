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
import java.util.Locale;

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
            ensureTaxAndPartnerSchema(conn);
            ensureV22OperationsAndCommsSchema(conn);
        });
        LOG.info("SQLite schema initialized at {}", dbManager.getDatabasePath());
    }

    private void ensureTaxAndPartnerSchema(Connection conn) throws SQLException {
        // 1. Seed default currencies (HUF, EUR, USD) idempotently
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT OR IGNORE INTO currencies (code, name_en, name_hu, symbol, decimal_places, active)
                    VALUES ('HUF', 'Hungarian Forint', 'Magyar forint', 'Ft', 0, 1)
                    """);
            stmt.execute("""
                    INSERT OR IGNORE INTO currencies (code, name_en, name_hu, symbol, decimal_places, active)
                    VALUES ('EUR', 'Euro', 'Euró', '€', 2, 1)
                    """);
            stmt.execute("""
                    INSERT OR IGNORE INTO currencies (code, name_en, name_hu, symbol, decimal_places, active)
                    VALUES ('USD', 'US Dollar', 'Amerikai dollár', '$', 2, 1)
                    """);
        }

        // 2. Seed default tax types idempotently (Hungary 27%, EU B2B 0%, Non-EU Export 0%, reduced rates)
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT OR IGNORE INTO tax_types (code, name_en, name_hu, rate_percent, country_scope, active)
                    VALUES ('HU-27', 'Hungarian Standard VAT (27%)', 'Belföldi ÁFA (27%)', 27.0, 'HU', 1)
                    """);
            stmt.execute("""
                    INSERT OR IGNORE INTO tax_types (code, name_en, name_hu, rate_percent, country_scope, active)
                    VALUES ('EU-0', 'Intra-EU B2B Reverse Charge (0%)', 'EU közösségen belüli adómentes (0%)', 0.0, 'EU', 1)
                    """);
            stmt.execute("""
                    INSERT OR IGNORE INTO tax_types (code, name_en, name_hu, rate_percent, country_scope, active)
                    VALUES ('EXPORT-0', 'Outside-EU Export Exempt (0%)', 'EU-n kívüli export adómentes (0%)', 0.0, 'NON_EU', 1)
                    """);
            stmt.execute("""
                    INSERT OR IGNORE INTO tax_types (code, name_en, name_hu, rate_percent, country_scope, active)
                    VALUES ('HU-18', 'Hungarian Reduced VAT (18%)', 'Kedvezményes ÁFA (18%)', 18.0, 'HU', 1)
                    """);
            stmt.execute("""
                    INSERT OR IGNORE INTO tax_types (code, name_en, name_hu, rate_percent, country_scope, active)
                    VALUES ('HU-5', 'Hungarian Super-Reduced VAT (5%)', 'Kedvezményes ÁFA (5%)', 5.0, 'HU', 1)
                    """);
            stmt.execute("""
                    INSERT OR IGNORE INTO tax_types (code, name_en, name_hu, rate_percent, country_scope, active)
                    VALUES ('AAM-0', 'Tax Exempt — AAM (0%)', 'Alanyi adómentes — AAM (0%)', 0.0, 'ANY', 1)
                    """);
        }

        // 3. Add partner_type & country_code to customers, tax_type_code & attachment_path to invoices if missing
        addColumnIfMissing(conn, "customers", "partner_type", "TEXT NOT NULL DEFAULT 'CUSTOMER'");
        addColumnIfMissing(conn, "customers", "country_code", "TEXT NOT NULL DEFAULT 'HU'");
        addColumnIfMissing(conn, "invoices", "tax_type_code", "TEXT");
        addColumnIfMissing(conn, "invoices", "attachment_path", "TEXT");

        // 4. Infer country_code for existing non-Hungarian customers & backfill tax_type_code on existing invoices
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    UPDATE customers SET country_code = 'SK'
                    WHERE country_code = 'HU' AND (upper(COALESCE(tax_number, '')) LIKE 'SK%' OR lower(COALESCE(email, '')) LIKE '%.sk')
                    """);
            stmt.execute("""
                    UPDATE customers SET country_code = 'AT'
                    WHERE country_code = 'HU' AND (upper(COALESCE(tax_number, '')) LIKE 'AT%' OR lower(COALESCE(email, '')) LIKE '%.at')
                    """);
            stmt.execute("""
                    UPDATE customers SET country_code = 'DE'
                    WHERE country_code = 'HU' AND (upper(COALESCE(tax_number, '')) LIKE 'DE%' OR lower(COALESCE(email, '')) LIKE '%.de')
                    """);
            stmt.execute("""
                    UPDATE invoices SET tax_type_code = CASE
                        WHEN vat_rate = 0 THEN 'EU-0'
                        WHEN vat_rate = 18 THEN 'HU-18'
                        WHEN vat_rate = 5 THEN 'HU-5'
                        ELSE 'HU-27'
                    END
                    WHERE tax_type_code IS NULL OR tax_type_code = ''
                    """);
        }

        // 5. If upgrading an already-populated DB with zero suppliers yet, add starter suppliers & an Austrian buyer
        long custCount = 0;
        long supCount = 0;
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM customers")) {
                if (rs.next()) custCount = rs.getLong(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM customers WHERE partner_type IN ('SUPPLIER', 'BOTH')")) {
                if (rs.next()) supCount = rs.getLong(1);
            }
        }
        if (custCount > 0 && supCount == 0) {
            String created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now());
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO customers (name, partner_type, contact_name, email, phone, address, tax_number, country_code, preferred_currency, active, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                    """)) {
                Object[][] starters = {
                        {"Alpine Second-Hand GmbH", "CUSTOMER", "Lukas Gruber", "office@alpinesecondhand.at",
                                "+43 1 512 8890", "1010 Wien, Mariahilfer Str. 48", "ATU68219034", "AT", "EUR"},
                        {"EuroTex Import GmbH", "SUPPLIER", "Klaus Weber", "billing@eurotex-import.de",
                                "+49 89 4120 991", "80331 München, Industriestr. 14", "DE294810221", "DE", "EUR"},
                        {"Duna Ipari Park Kft.", "SUPPLIER", "Kovács Zoltán", "penzugy@dunaiparipark.hu",
                                "+36 25 510 200", "2400 Dunaújváros, Papírgyári út 12.", "14820911-2-07", "HU", "HUF"},
                        {"MVM Next Energiakereskedelmi Zrt.", "SUPPLIER", "Ügyfélszolgálat", "szamlazas@mvm.hu",
                                "+36 1 474 9999", "1081 Budapest, II. János Pál pápa tér 20.", "26713111-2-44", "HU", "HUF"}
                };
                for (Object[] row : starters) {
                    ps.setString(1, (String) row[0]);
                    ps.setString(2, (String) row[1]);
                    ps.setString(3, (String) row[2]);
                    ps.setString(4, (String) row[3]);
                    ps.setString(5, (String) row[4]);
                    ps.setString(6, (String) row[5]);
                    ps.setString(7, (String) row[6]);
                    ps.setString(8, (String) row[7]);
                    ps.setString(9, (String) row[8]);
                    ps.setString(10, created);
                    ps.executeUpdate();
                }
            }
        }
    }

    private void ensureV22OperationsAndCommsSchema(Connection conn) throws SQLException {
        // 1. Add location_type to warehouse_locations if upgrading an older DB
        addColumnIfMissing(conn, "warehouse_locations", "location_type", "TEXT NOT NULL DEFAULT 'STORAGE'");

        // 2. Seed default email domain & SMTP/IMAP settings if missing
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('mail.domain', 'rewear-wms.hu')");
            stmt.execute("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('mail.smtp.host', 'smtp.rewear-wms.hu')");
            stmt.execute("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('mail.smtp.port', '587')");
            stmt.execute("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('mail.smtp.security', 'STARTTLS')");
            stmt.execute("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('mail.imap.host', 'imap.rewear-wms.hu')");
            stmt.execute("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('mail.imap.port', '993')");
            stmt.execute("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('mail.imap.security', 'SSL/TLS')");
            stmt.execute("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('mail.simulation_mode', '1')");
        }

        // 3. Ensure FORKLIFT role & demo account exist on already-seeded DBs
        long userCount = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next()) userCount = rs.getLong(1);
        }
        if (userCount > 0) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT OR IGNORE INTO roles (code, name) VALUES ('FORKLIFT', 'Forklift Driver')");
            }
            long forkliftRoleId = -1;
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM roles WHERE code = 'FORKLIFT'");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) forkliftRoleId = rs.getLong(1);
            }
            if (forkliftRoleId > 0) {
                boolean hasForklift = false;
                try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE lower(username) = 'forklift'");
                     ResultSet rs = ps.executeQuery()) {
                    hasForklift = rs.next();
                }
                if (!hasForklift) {
                    String hash = BCrypt.hashpw("forklift123", BCrypt.gensalt(10));
                    String created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now());
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO users (username, password_hash, full_name, last_name, first_name, email, role_id, active, created_at) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)")) {
                        ps.setString(1, "forklift");
                        ps.setString(2, hash);
                        ps.setString(3, "Tóth Márk");
                        ps.setString(4, "Tóth");
                        ps.setString(5, "Márk");
                        ps.setString(6, "mark.toth@rewear-wms.hu");
                        ps.setLong(7, forkliftRoleId);
                        ps.setString(8, created);
                        ps.executeUpdate();
                    }
                }
            }

            // 4. Seed multiple OPERATOR_STATIONs & DISPATCH_ZONE if upgrading an existing DB
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        INSERT OR IGNORE INTO warehouse_locations (code, zone, aisle, shelf, location_type, max_capacity_kg, active)
                        VALUES ('ST-OP-01', 'OP', '01', '01', 'OPERATOR_STATION', 400.0, 1)
                        """);
                stmt.execute("""
                        INSERT OR IGNORE INTO warehouse_locations (code, zone, aisle, shelf, location_type, max_capacity_kg, active)
                        VALUES ('ST-OP-02', 'OP', '02', '01', 'OPERATOR_STATION', 400.0, 1)
                        """);
                stmt.execute("""
                        INSERT OR IGNORE INTO warehouse_locations (code, zone, aisle, shelf, location_type, max_capacity_kg, active)
                        VALUES ('ST-OP-03', 'OP', '03', '01', 'OPERATOR_STATION', 400.0, 1)
                        """);
                stmt.execute("""
                        INSERT OR IGNORE INTO warehouse_locations (code, zone, aisle, shelf, location_type, max_capacity_kg, active)
                        VALUES ('DOCK-01', 'DOCK', '01', '01', 'DISPATCH_ZONE', 2000.0, 1)
                        """);
            }

            // 5. Backfill corporate email addresses (firstname.lastname@<domain>) for any existing user missing an email
            String domain = hu.due.rewear.model.EmailSettings.DEFAULT_DOMAIN;
            try (PreparedStatement ps = conn.prepareStatement("SELECT setting_value FROM app_settings WHERE setting_key = 'mail.domain'");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getString(1) != null && !rs.getString(1).isBlank()) {
                    domain = hu.due.rewear.model.EmailSettings.sanitizeDomain(rs.getString(1));
                }
            }
            hu.due.rewear.model.EmailSettings helper = new hu.due.rewear.model.EmailSettings(
                    domain, null, 587, "STARTTLS", "", "", null, 993, "SSL/TLS", "", "", true);
            try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT id, username, first_name, last_name, email FROM users");
                 ResultSet rs = sel.executeQuery();
                 PreparedStatement upd = conn.prepareStatement("UPDATE users SET email = ? WHERE id = ?")) {
                while (rs.next()) {
                    String em = rs.getString("email");
                    if (em == null || em.isBlank()) {
                        String gen = helper.generateUserEmail(
                                rs.getString("first_name"),
                                rs.getString("last_name"),
                                rs.getString("username"));
                        upd.setString(1, gen);
                        upd.setLong(2, rs.getLong("id"));
                        upd.executeUpdate();
                    }
                }
            }
        }
    }

    private void ensureMultiCurrencySchema(Connection conn) throws SQLException {
        // 1. Seed default reference EUR/HUF rate in app_settings if missing (used by dictionary price converter)
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('fx.eur_huf', '400.00')");
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
                cols.add(rs.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        if (!cols.contains(column.toLowerCase(Locale.ROOT))) {
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
                existing.add(rs.getString("name").toLowerCase(Locale.ROOT));
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
