package hu.due.rewear.model;

import java.math.BigDecimal;

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
