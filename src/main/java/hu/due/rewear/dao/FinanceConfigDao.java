package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.CountryDirectory;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.TaxType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Manages configurable Invoice Settings: VAT / Tax Types ({@code tax_types})
 * and operating Currencies ({@code currencies}).
 */
public final class FinanceConfigDao {

    private final DatabaseManager db;

    public FinanceConfigDao(DatabaseManager db) {
        this.db = db;
    }

    // --- Tax Types ---

    public List<TaxType> findTaxTypes(boolean activeOnly) {
        String sql = "SELECT id, code, name_en, name_hu, rate_percent, country_scope, active FROM tax_types "
                + (activeOnly ? "WHERE active = 1 " : "")
                + "ORDER BY CASE country_scope WHEN 'HU' THEN 1 WHEN 'EU' THEN 2 WHEN 'NON_EU' THEN 3 ELSE 4 END, rate_percent DESC, code";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<TaxType> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapTaxType(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list tax types", e);
        }
    }

    public Optional<TaxType> findTaxTypeByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT id, code, name_en, name_hu, rate_percent, country_scope, active FROM tax_types WHERE upper(code) = upper(?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapTaxType(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find tax type: " + code, e);
        }
    }

    /**
     * Resolves the default VAT/tax type for a customer's country:
     * Hungary (HU) -> HU-27 (27%), other European Union countries (AT, SK, DE, ...) -> EU-0 (0%),
     * outside EU -> EXPORT-0 (0%).
     */
    public TaxType resolveDefaultTaxTypeForCountry(String countryCode) {
        String scope = CountryDirectory.taxScopeForCountry(countryCode);
        List<TaxType> active = findTaxTypes(true);
        // 1. Prefer exact country_scope match (HU -> highest standard rate HU-27; EU/NON_EU -> 0% exempt)
        for (TaxType t : active) {
            if (scope.equalsIgnoreCase(t.countryScope())) {
                return t;
            }
        }
        // 2. Fallback: EU-0 for non-HU, HU-27 for HU, or first active
        if (!"HU".equals(scope)) {
            for (TaxType t : active) {
                if (t.ratePercent().compareTo(BigDecimal.ZERO) == 0) {
                    return t;
                }
            }
        }
        return active.isEmpty()
                ? new TaxType(0, "HU".equals(scope) ? "HU-27" : "EU-0",
                "HU".equals(scope) ? "Hungarian Standard VAT (27%)" : "Intra-EU B2B Reverse Charge (0%)",
                "HU".equals(scope) ? "Belföldi ÁFA (27%)" : "EU közösségen belüli adómentes (0%)",
                "HU".equals(scope) ? new BigDecimal("27.00") : BigDecimal.ZERO, scope, true)
                : active.get(0);
    }

    public long insertTaxType(String code, String nameEn, String nameHu, BigDecimal ratePercent, String countryScope) {
        String normCode = normalizeCode(code);
        String scope = normalizeScope(countryScope);
        if (ratePercent == null || ratePercent.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Tax rate must be >= 0.");
        }
        String sql = """
                INSERT INTO tax_types (code, name_en, name_hu, rate_percent, country_scope, active)
                VALUES (?, ?, ?, ?, ?, 1)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, normCode);
            ps.setString(2, nameEn.trim());
            ps.setString(3, nameHu.trim());
            ps.setDouble(4, ratePercent.setScale(2, RoundingMode.HALF_UP).doubleValue());
            ps.setString(5, scope);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No key returned for tax_types insert");
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to insert tax type: " + e.getMessage(), e);
        }
    }

    public void updateTaxType(long id, String code, String nameEn, String nameHu,
                              BigDecimal ratePercent, String countryScope, boolean active) {
        String normCode = normalizeCode(code);
        String scope = normalizeScope(countryScope);
        if (ratePercent == null || ratePercent.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Tax rate must be >= 0.");
        }
        String sql = """
                UPDATE tax_types
                SET code = ?, name_en = ?, name_hu = ?, rate_percent = ?, country_scope = ?, active = ?
                WHERE id = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normCode);
            ps.setString(2, nameEn.trim());
            ps.setString(3, nameHu.trim());
            ps.setDouble(4, ratePercent.setScale(2, RoundingMode.HALF_UP).doubleValue());
            ps.setString(5, scope);
            ps.setInt(6, active ? 1 : 0);
            ps.setLong(7, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update tax type: " + e.getMessage(), e);
        }
    }

    public boolean isTaxTypeReferenced(String code) {
        String sql = "SELECT 1 FROM invoices WHERE upper(COALESCE(tax_type_code, '')) = upper(?) LIMIT 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to check tax type usage", e);
        }
    }

    public void deleteTaxType(long id) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM tax_types WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to delete tax type: " + id, e);
        }
    }

    // --- Currencies ---

    public List<CurrencyDef> findCurrencies(boolean activeOnly) {
        String sql = "SELECT id, code, name_en, name_hu, symbol, decimal_places, active FROM currencies "
                + (activeOnly ? "WHERE active = 1 " : "")
                + "ORDER BY CASE code WHEN 'HUF' THEN 1 WHEN 'EUR' THEN 2 WHEN 'USD' THEN 3 ELSE 4 END, code";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<CurrencyDef> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapCurrency(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list currencies", e);
        }
    }

    public Optional<CurrencyDef> findCurrencyByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT id, code, name_en, name_hu, symbol, decimal_places, active FROM currencies WHERE upper(code) = upper(?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapCurrency(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find currency: " + code, e);
        }
    }

    public int decimalPlacesFor(String currencyCode) {
        return findCurrencyByCode(currencyCode)
                .map(CurrencyDef::decimalPlaces)
                .orElseGet(() -> CurrencyDef.defaultDecimalsFor(currencyCode));
    }

    /**
     * Loads all configured currency decimal precisions in a single SQL query so callers rendering
     * multi-row tables or summaries never trigger N+1 lookups.
     */
    public Map<String, Integer> decimalPlacesMap() {
        String sql = "SELECT upper(code) AS code, decimal_places FROM currencies";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            Map<String, Integer> map = new HashMap<>();
            while (rs.next()) {
                map.put(rs.getString("code"), rs.getInt("decimal_places"));
            }
            return map;
        } catch (SQLException e) {
            throw new DaoException("Failed to load currency decimal map", e);
        }
    }

    public long insertCurrency(String code, String nameEn, String nameHu, String symbol, int decimalPlaces) {
        String normCode = normalizeCurrencyCode(code);
        int dec = decimalPlaces <= 0 ? 0 : 2;
        String sql = """
                INSERT INTO currencies (code, name_en, name_hu, symbol, decimal_places, active)
                VALUES (?, ?, ?, ?, ?, 1)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, normCode);
            ps.setString(2, nameEn.trim());
            ps.setString(3, nameHu.trim());
            ps.setString(4, symbol != null && !symbol.isBlank() ? symbol.trim() : normCode);
            ps.setInt(5, dec);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No key returned for currencies insert");
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to insert currency: " + e.getMessage(), e);
        }
    }

    public void updateCurrency(long id, String code, String nameEn, String nameHu,
                               String symbol, int decimalPlaces, boolean active) {
        String normCode = normalizeCurrencyCode(code);
        int dec = decimalPlaces <= 0 ? 0 : 2;
        String sql = """
                UPDATE currencies
                SET code = ?, name_en = ?, name_hu = ?, symbol = ?, decimal_places = ?, active = ?
                WHERE id = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normCode);
            ps.setString(2, nameEn.trim());
            ps.setString(3, nameHu.trim());
            ps.setString(4, symbol != null && !symbol.isBlank() ? symbol.trim() : normCode);
            ps.setInt(5, dec);
            ps.setInt(6, active ? 1 : 0);
            ps.setLong(7, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update currency: " + e.getMessage(), e);
        }
    }

    public boolean isCurrencyReferenced(String code) {
        String sql = """
                SELECT 1 FROM invoices WHERE upper(currency) = upper(?)
                UNION ALL
                SELECT 1 FROM orders WHERE upper(currency) = upper(?)
                UNION ALL
                SELECT 1 FROM bank_accounts WHERE currency = upper(?)
                LIMIT 1
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, code);
            ps.setString(3, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to check currency usage", e);
        }
    }

    public void deleteCurrency(long id) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM currencies WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to delete currency: " + id, e);
        }
    }

    private static TaxType mapTaxType(ResultSet rs) throws SQLException {
        return new TaxType(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name_en"),
                rs.getString("name_hu"),
                BigDecimal.valueOf(rs.getDouble("rate_percent")).setScale(2, RoundingMode.HALF_UP),
                rs.getString("country_scope"),
                rs.getInt("active") == 1
        );
    }

    private static CurrencyDef mapCurrency(ResultSet rs) throws SQLException {
        return new CurrencyDef(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name_en"),
                rs.getString("name_hu"),
                rs.getString("symbol"),
                rs.getInt("decimal_places"),
                rs.getInt("active") == 1
        );
    }

    private static String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Code is required.");
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT);
        if (!norm.matches("[A-Z0-9_-]{2,16}")) {
            throw new IllegalArgumentException("Code must be 2-16 uppercase letters, digits, '-' or '_'.");
        }
        return norm;
    }

    private static String normalizeCurrencyCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Currency code is required.");
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT);
        if (!norm.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency code must be a 3-letter ISO code (e.g. EUR, HUF, USD, GBP).");
        }
        return norm;
    }

    private static String normalizeScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return "ANY";
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "HU", "EU", "NON_EU", "ANY" -> s;
            default -> "ANY";
        };
    }
}
