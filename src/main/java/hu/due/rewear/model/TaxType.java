package hu.due.rewear.model;

import java.math.BigDecimal;

/**
 * Configurable VAT / Tax type (e.g., HU-27 for 27% Hungarian VAT, EU-0 for 0% Intra-EU B2B Reverse Charge).
 */
public record TaxType(
        long id,
        String code,
        String nameEn,
        String nameHu,
        BigDecimal ratePercent,
        String countryScope,
        boolean active
) {
    public String localizedName(String lang) {
        return "hu".equalsIgnoreCase(lang) && nameHu != null && !nameHu.isBlank() ? nameHu : nameEn;
    }

    public String rateDisplay() {
        BigDecimal s = (ratePercent != null ? ratePercent : BigDecimal.ZERO).stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString() + "%";
    }

    public String displayLabel(String lang) {
        return code + " — " + localizedName(lang);
    }

    @Override
    public String toString() {
        return code + " (" + rateDisplay() + ")";
    }
}
