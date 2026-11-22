package hu.due.rewear.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/**
 * Separate per-currency P&L financial summary.
 * Each currency (HUF, EUR, USD, ...) is tracked independently with zero cross-currency conversion.
 */
public record FinancialSummary(
        Map<String, CurrencyTotals> byCurrency
) {
    public record CurrencyTotals(
            String currency,
            int decimalPlaces,
            BigDecimal totalIncome,
            BigDecimal paidIncome,
            BigDecimal totalExpenses,
            BigDecimal paidExpenses,
            BigDecimal netBalance,
            int unpaidCount,
            BigDecimal unpaidAmount
    ) {
        public static CurrencyTotals empty(String currency, int decimalPlaces) {
            BigDecimal zero = CurrencyDef.roundForScale(BigDecimal.ZERO, decimalPlaces);
            return new CurrencyTotals(currency, decimalPlaces, zero, zero, zero, zero, zero, 0, zero);
        }
    }

    public FinancialSummary {
        byCurrency = Collections.unmodifiableMap(new LinkedHashMap<>(byCurrency));
    }

    public CurrencyTotals forCurrency(String code, int decimalPlaces) {
        String key = (code == null || code.isBlank()) ? "EUR" : code.trim().toUpperCase(Locale.ROOT);
        return byCurrency.getOrDefault(key, CurrencyTotals.empty(key, decimalPlaces));
    }
}
