package hu.due.rewear.model;

import java.math.BigDecimal;

/**
 * Aggregated inventory weight & package count slice used by Dashboard analytics charts.
 */
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
