package hu.due.rewear.model;

import java.math.BigDecimal;

/**
 * Product / garment category lookup ({@code TYPE} QR segment, e.g. {@code TSH}, {@code JNS}, {@code JKT})
 * carrying independent default wholesale prices in both EUR/kg and HUF/kg.
 */
public record GarmentType(
        long id,
        String code,
        String nameEn,
        String nameHu,
        BigDecimal priceEurPerKg,
        BigDecimal priceHufPerKg,
        boolean active
) {
    public String localizedName(String lang) {
        return "hu".equalsIgnoreCase(lang) && nameHu != null && !nameHu.isBlank() ? nameHu : nameEn;
    }

    public String displayLabel(String lang) {
        return code + " — " + localizedName(lang);
    }

    public BigDecimal priceFor(CurrencyCode currency) {
        BigDecimal raw = currency == CurrencyCode.HUF ? priceHufPerKg : priceEurPerKg;
        return currency.round(raw != null ? raw : BigDecimal.ZERO);
    }
}
