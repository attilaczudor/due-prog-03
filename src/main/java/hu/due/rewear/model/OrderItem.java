package hu.due.rewear.model;

import java.math.BigDecimal;

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
