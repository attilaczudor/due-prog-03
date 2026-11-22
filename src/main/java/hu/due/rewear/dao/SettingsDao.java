package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.CompanyProfile;
import hu.due.rewear.model.CurrencyCode;

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
    public static final String KEY_COMPANY_TAX_NUMBER = "company.tax_number";
    public static final String KEY_COMPANY_EMAIL = "company.email";
    public static final String KEY_COMPANY_PHONE = "company.phone";
    public static final String PREFIX_COMPANY_IBAN = "company.iban.";

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
                    case KEY_COMPANY_TAX_NUMBER -> { if (!v.isBlank()) tax = v.trim(); }
                    case KEY_COMPANY_EMAIL -> { if (!v.isBlank()) email = v.trim(); }
                    case KEY_COMPANY_PHONE -> phone = v.trim();
                    default -> {
                        if (k.startsWith(PREFIX_COMPANY_IBAN)) {
                            String cur = k.substring(PREFIX_COMPANY_IBAN.length()).trim().toUpperCase(Locale.ROOT);
                            if (!cur.isEmpty() && !v.isBlank()) {
                                ibans.put(cur, v.trim());
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to load company profile settings", e);
        }
        return new CompanyProfile(name, address, tax, email, phone, ibans);
    }

    /** Atomically persists seller/issuer company profile details and per-currency bank IBANs. */
    public void saveCompanyProfile(CompanyProfile profile) {
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
                upsertSetting(ps, KEY_COMPANY_TAX_NUMBER, profile.taxNumber());
                upsertSetting(ps, KEY_COMPANY_EMAIL, profile.email());
                upsertSetting(ps, KEY_COMPANY_PHONE, profile.phone());
                for (Map.Entry<String, String> entry : profile.ibansByCurrency().entrySet()) {
                    String cur = entry.getKey().trim().toUpperCase(Locale.ROOT);
                    upsertSetting(ps, PREFIX_COMPANY_IBAN + cur, entry.getValue().trim());
                }
            }
        });
    }

    private static void upsertSetting(PreparedStatement ps, String key, String val) throws SQLException {
        ps.setString(1, key);
        ps.setString(2, val != null ? val : "");
        ps.executeUpdate();
    }
}
