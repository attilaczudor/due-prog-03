package hu.due.rewear.util;

import java.math.BigDecimal;

/**
 * Shared null-safe text and numeric formatting helpers used across UI views and PDF/CSV exporters.
 * Centralizes common string/weight formatting so views do not duplicate boilerplate helpers.
 */
public final class FormatUtil {

    private FormatUtil() {
    }

    /** Formats a decimal weight or quantity without unnecessary trailing zeros (e.g., 25.00 -> "25", 12.50 -> "12.5"). */
    public static String stripZeros(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    /** Returns an empty string for null input, avoiding "null" text in tables and PDFs. */
    public static String safe(String text) {
        return text == null ? "" : text;
    }

    /** Truncates text to {@code maxChars} with an ellipsis if it exceeds the limit. */
    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 3 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars - 3) + "...";
    }
}
