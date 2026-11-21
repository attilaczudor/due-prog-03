package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.CurrencyCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Persists portable workspace settings inside SQLite ({@code app_settings}) so exchange rates
 * and reporting currency preferences travel alongside the database file across machines.
 */
public final class SettingsDao {

    public static final String KEY_EUR_HUF_RATE = "fx.eur_huf";
    public static final String KEY_REPORTING_CURRENCY = "finance.reporting_currency";

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

    public CurrencyCode getReportingCurrency() {
        return get(KEY_REPORTING_CURRENCY)
                .map(CurrencyCode::parse)
                .orElse(CurrencyCode.HUF);
    }

    public void setReportingCurrency(CurrencyCode currency) {
        set(KEY_REPORTING_CURRENCY, (currency != null ? currency : CurrencyCode.HUF).name());
    }
}
