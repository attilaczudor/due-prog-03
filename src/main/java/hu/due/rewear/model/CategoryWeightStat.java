package hu.due.rewear.model;

import java.math.BigDecimal;

public record CategoryWeightStat(
        String code,
        String nameEn,
        String nameHu,
        int packageCount,
        BigDecimal totalWeightKg
) {
    public String localizedName(String lang) {
        return "hu".equalsIgnoreCase(lang) && nameHu != null && !nameHu.isBlank() ? nameHu : nameEn;
    }
}
