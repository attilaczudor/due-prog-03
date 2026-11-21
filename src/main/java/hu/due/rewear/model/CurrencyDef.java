package hu.due.rewear.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Configurable invoice/order currency managed in Invoice Settings (e.g., HUF, EUR, USD, GBP, CHF).
 */
public record CurrencyDef(
        long id,
        String code,
        String nameEn,
        String nameHu,
        String symbol,
        int decimalPlaces,
        boolean active
) {
    public String localizedName(String lang) {
        return "hu".equalsIgnoreCase(lang) && nameHu != null && !nameHu.isBlank() ? nameHu : nameEn;
    }

    public BigDecimal round(BigDecimal amount) {
        return roundForScale(amount, decimalPlaces);
    }

    public String format(BigDecimal amount) {
        return formatAmount(amount, code, decimalPlaces);
    }

    public String formatSigned(BigDecimal amount) {
        return formatSignedAmount(amount, code, decimalPlaces);
    }

    public String formatNumber(BigDecimal amount) {
        return formatNumeric(amount, decimalPlaces);
    }

    public String formatPlain(BigDecimal amount) {
        return round(amount).toPlainString();
    }

    public static int defaultDecimalsFor(String currencyCode) {
        if (currencyCode != null && "HUF".equalsIgnoreCase(currencyCode.trim())) {
            return 0;
        }
        return 2;
    }

    public static BigDecimal roundForScale(BigDecimal amount, int decimals) {
        BigDecimal safe = amount != null ? amount : BigDecimal.ZERO;
        return safe.setScale(Math.max(0, decimals), RoundingMode.HALF_UP);
    }

    public static String formatNumeric(BigDecimal amount, int decimals) {
        BigDecimal rounded = roundForScale(amount, decimals);
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
        sym.setGroupingSeparator(' ');
        sym.setDecimalSeparator('.');
        String pattern = decimals <= 0 ? "#,##0" : "#,##0.00";
        return new DecimalFormat(pattern, sym).format(rounded);
    }

    public static String formatAmount(BigDecimal amount, String currencyCode, int decimals) {
        String code = (currencyCode == null || currencyCode.isBlank()) ? "EUR" : currencyCode.trim().toUpperCase(Locale.ROOT);
        return formatNumeric(amount, decimals) + " " + code;
    }

    public static String formatAmount(BigDecimal amount, String currencyCode) {
        return formatAmount(amount, currencyCode, defaultDecimalsFor(currencyCode));
    }

    public static String formatSignedAmount(BigDecimal amount, String currencyCode, int decimals) {
        BigDecimal rounded = roundForScale(amount, decimals);
        String prefix = rounded.signum() > 0 ? "+" : "";
        String code = (currencyCode == null || currencyCode.isBlank()) ? "EUR" : currencyCode.trim().toUpperCase(Locale.ROOT);
        return prefix + formatNumeric(rounded, decimals) + " " + code;
    }

    @Override
    public String toString() {
        return code + (symbol != null && !symbol.isBlank() ? " (" + symbol + ")" : "");
    }
}
