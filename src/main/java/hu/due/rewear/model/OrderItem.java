package hu.due.rewear.model;

import java.math.BigDecimal;

/**
 * Physical inventory package allocated to a customer order with unit price per kg and line total in the order's currency.
 */
public record OrderItem(
        long id,
        long orderId,
        long packageId,
        String packageQrCode,
        String summaryEn,
        String summaryHu,
        BigDecimal weightKg,
        BigDecimal pricePerKg,
        BigDecimal lineTotal
) {
    public String localizedSummary(String lang) {
        return "hu".equalsIgnoreCase(lang) && summaryHu != null && !summaryHu.isBlank() ? summaryHu : summaryEn;
    }
}
