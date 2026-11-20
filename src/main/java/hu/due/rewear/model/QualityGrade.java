package hu.due.rewear.model;

import java.math.BigDecimal;

public record QualityGrade(
        long id,
        String code,
        String nameEn,
        String nameHu,
        BigDecimal defaultPricePerKg,
        boolean active
) {
    public String localizedName(String lang) {
        return "hu".equalsIgnoreCase(lang) && nameHu != null && !nameHu.isBlank() ? nameHu : nameEn;
    }

    public String displayLabel(String lang) {
        return code + " — " + localizedName(lang);
    }
}
