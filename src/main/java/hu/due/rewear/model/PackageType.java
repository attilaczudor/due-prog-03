package hu.due.rewear.model;

import java.math.BigDecimal;

/**
 * Packaging container lookup ({@code PACKAGETYPE} QR segment, e.g. Compressed Bale, Clear Bag, Box, Pallet)
 * defining tare weight and maximum recommended weight capacity.
 */
public record PackageType(
        long id,
        String code,
        String nameEn,
        String nameHu,
        BigDecimal tareWeightKg,
        BigDecimal maxWeightKg,
        boolean active
) {
    public String localizedName(String lang) {
        return "hu".equalsIgnoreCase(lang) && nameHu != null && !nameHu.isBlank() ? nameHu : nameEn;
    }

    public String displayLabel(String lang) {
        return code + " — " + localizedName(lang);
    }
}
