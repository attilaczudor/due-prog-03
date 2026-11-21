package hu.due.rewear.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Order(
        long id,
        String orderNumber,
        long customerId,
        String customerName,
        OrderStatus status,
        CurrencyCode currency,
        BigDecimal totalWeightKg,
        BigDecimal totalPrice,
        String notes,
        long createdBy,
        String createdByFullName,
        LocalDateTime createdAt,
        LocalDateTime shippedAt
) {
    public CurrencyCode effectiveCurrency() {
        return currency != null ? currency : CurrencyCode.EUR;
    }

    @Override
    public String toString() {
        return orderNumber + " — " + customerName + " (" + effectiveCurrency().format(totalPrice) + ")";
    }
}
