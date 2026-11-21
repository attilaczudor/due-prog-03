package hu.due.rewear.model;

import java.time.LocalDateTime;

/**
 * B2B Business Partner: can be a Buyer (CUSTOMER), a Provider/Vendor we pay (SUPPLIER), or BOTH.
 */
public record Customer(
        long id,
        String name,
        PartnerType partnerType,
        String contactName,
        String email,
        String phone,
        String address,
        String taxNumber,
        String countryCode,
        String preferredCurrency,
        boolean active,
        LocalDateTime createdAt
) {
    public PartnerType effectivePartnerType() {
        return partnerType != null ? partnerType : PartnerType.CUSTOMER;
    }

    public String effectiveCountryCode() {
        return CountryDirectory.normalizeCode(countryCode);
    }

    public String effectiveCurrencyCode() {
        return (preferredCurrency != null && !preferredCurrency.isBlank())
                ? preferredCurrency.trim().toUpperCase() : "EUR";
    }

    @Override
    public String toString() {
        return name + " (" + effectiveCountryCode() + " / " + effectiveCurrencyCode() + ")";
    }
}
