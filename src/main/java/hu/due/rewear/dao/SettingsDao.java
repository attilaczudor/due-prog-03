package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.CompanyProfile;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.EmailSettings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Persists portable workspace settings inside SQLite ({@code app_settings}) so company profile
 * details, per-currency bank IBANs, and reference exchange rates travel alongside the database file.
 */
public final class SettingsDao {

    public static final String KEY_EUR_HUF_RATE = "fx.eur_huf";
    public static final String KEY_COMPANY_NAME = "company.name";
    public static final String KEY_COMPANY_ADDRESS = "company.address";
    private static final String KEY_COMPANY_STREET = "company.address.street";
    private static final String KEY_COMPANY_CITY = "company.address.city";
    private static final String KEY_COMPANY_POSTAL_CODE = "company.address.postal_code";
    public static final String KEY_COMPANY_TAX_NUMBER = "company.tax_number";
    public static final String KEY_COMPANY_EMAIL = "company.email";
    public static final String KEY_COMPANY_PHONE = "company.phone";
    public static final String PREFIX_COMPANY_IBAN = "company.iban.";
    public static final String KEY_MAIL_DOMAIN = "mail.domain";
    public static final String KEY_MAIL_SMTP_HOST = "mail.smtp.host";
    public static final String KEY_MAIL_SMTP_PORT = "mail.smtp.port";
    public static final String KEY_MAIL_SMTP_SECURITY = "mail.smtp.security";
    public static final String KEY_MAIL_SMTP_USER = "mail.smtp.user";
    public static final String KEY_MAIL_SMTP_PASS = "mail.smtp.pass";
    public static final String KEY_MAIL_IMAP_HOST = "mail.imap.host";
    public static final String KEY_MAIL_IMAP_PORT = "mail.imap.port";
    public static final String KEY_MAIL_IMAP_SECURITY = "mail.imap.security";
    public static final String KEY_MAIL_IMAP_USER = "mail.imap.user";
    public static final String KEY_MAIL_IMAP_PASS = "mail.imap.pass";
    public static final String KEY_MAIL_SIMULATION = "mail.simulation_mode";

    private final DatabaseManager db;

    public SettingsDao(DatabaseManager db) {
        this.db = db;
    }

    public Optional<String> get(String key) {
        String sql = "SELECT setting_value FROM app_settings WHERE setting_key = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to read setting: " + key, e);
        }
    }

    public void set(String key, String value) {
        String sql = """
                INSERT INTO app_settings (setting_key, setting_value)
                VALUES (?, ?)
                ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to write setting: " + key, e);
        }
    }

    /** The real Online Számla setup. Secrets are not here; they live in the credential file. */
    public hu.due.rewear.model.NavSettings getNavSettings() {
        var fields = new java.util.EnumMap<hu.due.rewear.model.NavSettings.Field, String>(
                hu.due.rewear.model.NavSettings.Field.class);
        try (var conn = db.getConnection();
             var query = conn.prepareStatement(
                     "SELECT setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'nav.%'");
             var rows = query.executeQuery()) {
            var lookup = new java.util.HashMap<String, String>();
            while (rows.next()) lookup.put(rows.getString(1), rows.getString(2));
            for (var field : hu.due.rewear.model.NavSettings.Field.values()) {
                String value = lookup.get("nav." + field.key());
                if (value != null) fields.put(field, value);
            }
            return new hu.due.rewear.model.NavSettings(fields);
        } catch (SQLException ex) {
            throw new DaoException("Could not load the NAV setup", ex);
        }
    }

    public void saveNavSettings(hu.due.rewear.model.NavSettings navSettings) {
        db.runInTransaction(conn -> {
            try (var query = conn.prepareStatement("""
                    INSERT INTO app_settings(setting_key, setting_value) VALUES(?, ?)
                    ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value
                    """)) {
                for (var field : hu.due.rewear.model.NavSettings.Field.values()) {
                    upsertSetting(query, "nav." + field.key(), navSettings.value(field));
                }
            }
        });
    }

    public BigDecimal getEurToHufRate() {
        return get(KEY_EUR_HUF_RATE)
                .map(raw -> {
                    try {
                        BigDecimal v = CurrencyCode.parseAmount(raw);
                        return v.compareTo(BigDecimal.ZERO) > 0
                                ? v.setScale(2, RoundingMode.HALF_UP)
                                : CurrencyCode.DEFAULT_EUR_HUF_RATE;
                    } catch (Exception ignored) {
                        return CurrencyCode.DEFAULT_EUR_HUF_RATE;
                    }
                })
                .orElse(CurrencyCode.DEFAULT_EUR_HUF_RATE);
    }

    public void setEurToHufRate(BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("EUR/HUF exchange rate must be > 0.");
        }
        set(KEY_EUR_HUF_RATE, rate.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    /** Loads the editable seller/issuer company profile and all per-currency IBANs in a single query. */
    public CompanyProfile getCompanyProfile() {
        String sql = "SELECT setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'company.%'";
        String name = CompanyProfile.DEFAULT_NAME;
        String address = CompanyProfile.DEFAULT_ADDRESS;
        String street = null;
        String city = null;
        String postalCode = null;
        String tax = CompanyProfile.DEFAULT_TAX_NUMBER;
        String email = CompanyProfile.DEFAULT_EMAIL;
        String phone = CompanyProfile.DEFAULT_PHONE;
        Map<String, String> ibans = new LinkedHashMap<>();
        ibans.put("HUF", CompanyProfile.DEFAULT_IBAN_HUF);
        ibans.put("EUR", CompanyProfile.DEFAULT_IBAN_EUR);

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String k = rs.getString("setting_key");
                String v = rs.getString("setting_value");
                if (k == null || v == null) continue;
                switch (k) {
                    case KEY_COMPANY_NAME -> { if (!v.isBlank()) name = v.trim(); }
                    case KEY_COMPANY_ADDRESS -> { if (!v.isBlank()) address = v.trim(); }
                    case KEY_COMPANY_STREET -> street = v;
                    case KEY_COMPANY_CITY -> city = v;
                    case KEY_COMPANY_POSTAL_CODE -> postalCode = v;
                    case KEY_COMPANY_TAX_NUMBER -> { if (!v.isBlank()) tax = v.trim(); }
                    case KEY_COMPANY_EMAIL -> { if (!v.isBlank()) email = v.trim(); }
                    case KEY_COMPANY_PHONE -> phone = v.trim();
                    default -> {
                        if (k.startsWith(PREFIX_COMPANY_IBAN)) {
                            String cur = k.substring(PREFIX_COMPANY_IBAN.length()).trim().toUpperCase(Locale.ROOT);
                            if (!cur.isEmpty()) {
                                ibans.put(cur, v.trim());
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to load company profile settings", e);
        }
        var addressParts = street != null || city != null || postalCode != null
                ? new CompanyProfile.Address(street, city, postalCode) : CompanyProfile.Address.fromLegacy(address);
        return new CompanyProfile(name, addressParts, tax, email, phone, ibans);
    }

    /** Atomically persists seller/issuer company profile details and per-currency bank IBANs. */
    public void saveCompanyProfile(CompanyProfile profile) {
        persistCompanyProfile(profile, true, true);
    }

    public void saveInvoiceCompanyProfile(String name, CompanyProfile.Address address, String taxNumber,
                                          Map<String, String> ibans) {
        saveInvoiceCompanyProfile(name, address, taxNumber, null, ibans);
    }

    public void saveInvoiceCompanyProfile(String name, CompanyProfile.Address address, String taxNumber,
                                          String email, Map<String, String> ibans) {
        persistCompanyProfile(new CompanyProfile(name, address, taxNumber, email, null, ibans), email != null, false);
    }

    private void persistCompanyProfile(CompanyProfile profile, boolean writeEmail, boolean writePhone) {
        if (profile == null) {
            throw new IllegalArgumentException("Company profile is required.");
        }
        String upsertSql = """
                INSERT INTO app_settings (setting_key, setting_value)
                VALUES (?, ?)
                ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value
                """;
        db.runInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                upsertSetting(ps, KEY_COMPANY_NAME, profile.name());
                upsertSetting(ps, KEY_COMPANY_ADDRESS, profile.address());
                upsertSetting(ps, KEY_COMPANY_STREET, profile.addressParts().street());
                upsertSetting(ps, KEY_COMPANY_CITY, profile.addressParts().city());
                upsertSetting(ps, KEY_COMPANY_POSTAL_CODE, profile.addressParts().postalCode());
                upsertSetting(ps, KEY_COMPANY_TAX_NUMBER, profile.taxNumber());
                if (writeEmail) upsertSetting(ps, KEY_COMPANY_EMAIL, profile.email());
                if (writePhone) upsertSetting(ps, KEY_COMPANY_PHONE, profile.phone());
                for (Map.Entry<String, String> entry : profile.ibansByCurrency().entrySet()) {
                    String cur = entry.getKey().trim().toUpperCase(Locale.ROOT);
                    upsertSetting(ps, PREFIX_COMPANY_IBAN + cur, entry.getValue().trim());
                }
            }
        });
    }

    /** Loads the Super-Admin-managed company email domain & SMTP/IMAP gateway settings. */
    public EmailSettings getEmailSettings() {
        String sql = "SELECT setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'mail.%'";
        EmailSettings def = EmailSettings.defaults();
        String domain = def.companyDomain();
        String smtpHost = def.smtpHost();
        int smtpPort = def.smtpPort();
        String smtpSec = def.smtpSecurity();
        String smtpUser = def.smtpUsername();
        String smtpPass = def.smtpPassword();
        String imapHost = def.imapHost();
        int imapPort = def.imapPort();
        String imapSec = def.imapSecurity();
        String imapUser = def.imapUsername();
        String imapPass = def.imapPassword();
        boolean simMode = def.simulationMode();

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String k = rs.getString("setting_key");
                String v = rs.getString("setting_value");
                if (k == null || v == null) continue;
                switch (k) {
                    case KEY_MAIL_DOMAIN -> domain = v;
                    case KEY_MAIL_SMTP_HOST -> smtpHost = v;
                    case KEY_MAIL_SMTP_PORT -> smtpPort = parsePort(v, def.smtpPort());
                    case KEY_MAIL_SMTP_SECURITY -> smtpSec = v;
                    case KEY_MAIL_SMTP_USER -> smtpUser = v;
                    case KEY_MAIL_SMTP_PASS -> smtpPass = v;
                    case KEY_MAIL_IMAP_HOST -> imapHost = v;
                    case KEY_MAIL_IMAP_PORT -> imapPort = parsePort(v, def.imapPort());
                    case KEY_MAIL_IMAP_SECURITY -> imapSec = v;
                    case KEY_MAIL_IMAP_USER -> imapUser = v;
                    case KEY_MAIL_IMAP_PASS -> imapPass = v;
                    case KEY_MAIL_SIMULATION -> simMode = !"0".equals(v.trim()) && !"false".equalsIgnoreCase(v.trim());
                    default -> { }
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to load email settings", e);
        }
        return new EmailSettings(domain, smtpHost, smtpPort, smtpSec, smtpUser, smtpPass,
                imapHost, imapPort, imapSec, imapUser, imapPass, simMode);
    }

    /** Atomically persists the Super-Admin-managed company email domain and SMTP/IMAP settings. */
    public void saveEmailSettings(EmailSettings s) {
        if (s == null) {
            throw new IllegalArgumentException("Email settings are required.");
        }
        String upsertSql = """
                INSERT INTO app_settings (setting_key, setting_value)
                VALUES (?, ?)
                ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value
                """;
        db.runInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                upsertSetting(ps, KEY_MAIL_DOMAIN, s.companyDomain());
                upsertSetting(ps, KEY_MAIL_SMTP_HOST, s.smtpHost());
                upsertSetting(ps, KEY_MAIL_SMTP_PORT, String.valueOf(s.smtpPort()));
                upsertSetting(ps, KEY_MAIL_SMTP_SECURITY, s.smtpSecurity());
                upsertSetting(ps, KEY_MAIL_SMTP_USER, s.smtpUsername());
                upsertSetting(ps, KEY_MAIL_SMTP_PASS, s.smtpPassword());
                upsertSetting(ps, KEY_MAIL_IMAP_HOST, s.imapHost());
                upsertSetting(ps, KEY_MAIL_IMAP_PORT, String.valueOf(s.imapPort()));
                upsertSetting(ps, KEY_MAIL_IMAP_SECURITY, s.imapSecurity());
                upsertSetting(ps, KEY_MAIL_IMAP_USER, s.imapUsername());
                upsertSetting(ps, KEY_MAIL_IMAP_PASS, s.imapPassword());
                upsertSetting(ps, KEY_MAIL_SIMULATION, s.simulationMode() ? "1" : "0");
            }
        });
    }

    private static int parsePort(String raw, int fallback) {
        try {
            int p = Integer.parseInt(raw.trim());
            return p > 0 && p <= 65535 ? p : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static void upsertSetting(PreparedStatement ps, String key, String val) throws SQLException {
        ps.setString(1, key);
        ps.setString(2, val != null ? val : "");
        ps.executeUpdate();
    }
}
