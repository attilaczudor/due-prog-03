package hu.due.rewear.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * B2B customer sales order with reversible dispatch lifecycle ({@link OrderStatus}) and native currency code.
 */
public record Order(
        long id,
        String orderNumber,
        long customerId,
        String customerName,
        OrderStatus status,
        String currency,
        BigDecimal totalWeightKg,
        BigDecimal totalPrice,
        String notes,
        long createdBy,
        String createdByFullName,
        LocalDateTime createdAt,
        LocalDateTime shippedAt
) {
    public String effectiveCurrencyCode() {
        return (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase(Locale.ROOT) : "EUR";
    }

    public int currencyDecimals() {
        return CurrencyDef.defaultDecimalsFor(effectiveCurrencyCode());
    }

    @Override
    public String toString() {
        return orderNumber + " — " + customerName + " ("
                + CurrencyDef.formatAmount(totalPrice, effectiveCurrencyCode()) + ")";
    }
}
