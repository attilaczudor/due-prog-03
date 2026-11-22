package hu.due.rewear.model;

import java.math.BigDecimal;

/**
 * Textile sorting quality grade lookup ({@code QUALITY} QR segment, e.g. Cream/Extra, Grade A/B/C)
 * with fallback dual-currency EUR/kg and HUF/kg pricing.
 */
public record QualityGrade(
        long id,
        String code,
        String nameEn,
        String nameHu,
        BigDecimal defaultPricePerKg,
        BigDecimal defaultPriceHufPerKg,
        boolean active
) {
    public String localizedName(String lang) {
        return "hu".equalsIgnoreCase(lang) && nameHu != null && !nameHu.isBlank() ? nameHu : nameEn;
    }

    public String displayLabel(String lang) {
        return code + " — " + localizedName(lang);
    }

    public BigDecimal priceFor(CurrencyCode currency) {
        BigDecimal raw = currency == CurrencyCode.HUF ? defaultPriceHufPerKg : defaultPricePerKg;
        return currency.round(raw != null ? raw : BigDecimal.ZERO);
    }
}
