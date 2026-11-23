package hu.due.rewear.model;

import java.math.BigDecimal;

/**
 * Physical inventory package allocated to a customer order with unit price per kg, line total,
 * current physical location code, and manager customer-handover scan verification flag.
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
        BigDecimal lineTotal,
        String currentLocationCode,
        boolean handoverScanned
) {
    /** Backward-compatible constructor defaulting location and scan flag. */
    public OrderItem(long id, long orderId, long packageId, String packageQrCode, String summaryEn,
                     String summaryHu, BigDecimal weightKg, BigDecimal pricePerKg, BigDecimal lineTotal) {
        this(id, orderId, packageId, packageQrCode, summaryEn, summaryHu, weightKg, pricePerKg, lineTotal, "", false);
    }

    public String localizedSummary(String lang) {
        return "hu".equalsIgnoreCase(lang) && summaryHu != null && !summaryHu.isBlank() ? summaryHu : summaryEn;
    }
}
