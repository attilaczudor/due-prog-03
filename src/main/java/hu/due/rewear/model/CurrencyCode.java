package hu.due.rewear.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Supported operating currencies for pricing, customer orders, invoices, expenses, and P&L reporting.
 * EUR uses 2 decimal places; HUF uses whole Forints (0 decimal places) per Hungarian accounting practice.
 */
public enum CurrencyCode {
    EUR(2),
    HUF(0);

    public static final BigDecimal DEFAULT_EUR_HUF_RATE = new BigDecimal("400.00");

    private final int scale;

    CurrencyCode(int scale) {
        this.scale = scale;
    }

    public int scale() {
        return scale;
    }

    public static CurrencyCode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return EUR;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("HUF".equals(normalized) || "FT".equals(normalized)) {
            return HUF;
        }
        return EUR;
    }

    public BigDecimal round(BigDecimal amount) {
        BigDecimal safe = amount != null ? amount : BigDecimal.ZERO;
        return safe.setScale(scale, RoundingMode.HALF_UP);
    }

    /** Formats amount with thousands spaces and currency suffix, e.g. "1 250.50 EUR" or "185 000 HUF". */
    public String format(BigDecimal amount) {
        return formatNumber(amount) + " " + name();
    }

    /** Formats signed amount with explicit + or - prefix and currency suffix. */
    public String formatSigned(BigDecimal amount) {
        BigDecimal rounded = round(amount);
        String prefix = rounded.signum() > 0 ? "+" : "";
        return prefix + formatNumber(rounded) + " " + name();
    }

    /** Formats numeric part with thousands spaces (no currency code), e.g. "1 250.50" or "185 000". */
    public String formatNumber(BigDecimal amount) {
        BigDecimal rounded = round(amount);
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
        sym.setGroupingSeparator(' ');
        sym.setDecimalSeparator('.');
        String pattern = scale == 0 ? "#,##0" : "#,##0.00";
        DecimalFormat df = new DecimalFormat(pattern, sym);
        return df.format(rounded);
    }

    /** Plain machine-friendly string without grouping spaces (for CSV & text inputs). */
    public String formatPlain(BigDecimal amount) {
        return round(amount).toPlainString();
    }

    /** Converts an amount from this currency to the target currency using the supplied HUF-per-1-EUR rate. */
    public BigDecimal convertTo(BigDecimal amount, CurrencyCode target, BigDecimal eurToHufRate) {
        BigDecimal safe = amount != null ? amount : BigDecimal.ZERO;
        if (target == null || target == this) {
            return round(safe);
        }
        BigDecimal rate = (eurToHufRate != null && eurToHufRate.compareTo(BigDecimal.ZERO) > 0)
                ? eurToHufRate : DEFAULT_EUR_HUF_RATE;
        if (this == EUR && target == HUF) {
            return target.round(safe.multiply(rate));
        }
        // HUF -> EUR
        return target.round(safe.divide(rate, 4, RoundingMode.HALF_UP));
    }

    /** Parses user-typed decimal amounts forgiving spaces and Hungarian commas (e.g. "1 850,50"). */
    public static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Amount is required.");
        }
        String cleaned = raw.trim().replace(" ", "").replace("\u00A0", "").replace(',', '.');
        return new BigDecimal(cleaned);
    }
}
