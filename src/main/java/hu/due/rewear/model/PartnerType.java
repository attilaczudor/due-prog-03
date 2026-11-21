package hu.due.rewear.model;

/**
 * Classifies B2B business partners as Buyers (Customers), Providers (Suppliers/Vendors we pay), or Both.
 */
public enum PartnerType {
    CUSTOMER,
    SUPPLIER,
    BOTH;

    public boolean isBuyer() {
        return this == CUSTOMER || this == BOTH;
    }

    public boolean isSupplier() {
        return this == SUPPLIER || this == BOTH;
    }

    public static PartnerType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CUSTOMER;
        }
        try {
            return PartnerType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CUSTOMER;
        }
    }
}
