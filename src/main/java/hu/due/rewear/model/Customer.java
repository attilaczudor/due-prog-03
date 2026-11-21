package hu.due.rewear.model;

import java.time.LocalDateTime;

public record Customer(
        long id,
        String name,
        String contactName,
        String email,
        String phone,
        String address,
        String taxNumber,
        CurrencyCode preferredCurrency,
        boolean active,
        LocalDateTime createdAt
) {
    public CurrencyCode effectiveCurrency() {
        return preferredCurrency != null ? preferredCurrency : CurrencyCode.EUR;
    }

    @Override
    public String toString() {
        return name + " (" + effectiveCurrency().name() + ")";
    }
}
