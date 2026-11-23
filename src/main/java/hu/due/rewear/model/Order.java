package hu.due.rewear.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * B2B customer sales order with reversible dispatch lifecycle ({@link OrderStatus}), native currency code,
 * assigned Forklift Driver, target Dispatch/Collection Zone, and customer arrival timestamp.
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
        LocalDateTime shippedAt,
        Long assignedForkliftId,
        String assignedForkliftName,
        Long dispatchLocationId,
        String dispatchLocationCode,
        LocalDateTime customerArrivedAt
) {
    /** Backward-compatible constructor defaulting assignment and arrival fields to {@code null}. */
    public Order(long id, String orderNumber, long customerId, String customerName, OrderStatus status,
                 String currency, BigDecimal totalWeightKg, BigDecimal totalPrice, String notes,
                 long createdBy, String createdByFullName, LocalDateTime createdAt, LocalDateTime shippedAt) {
        this(id, orderNumber, customerId, customerName, status, currency, totalWeightKg, totalPrice, notes,
                createdBy, createdByFullName, createdAt, shippedAt, null, null, null, null, null);
    }

    public boolean isCustomerArrived() {
        return customerArrivedAt != null;
    }

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
