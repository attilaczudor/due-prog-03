package hu.due.rewear.model;

import java.math.BigDecimal;

public record WarehouseLocation(
        long id,
        String code,
        String zone,
        String aisle,
        String shelf,
        BigDecimal maxCapacityKg,
        boolean active,
        BigDecimal usedWeightKg
) {
    public BigDecimal remainingCapacityKg() {
        BigDecimal used = usedWeightKg != null ? usedWeightKg : BigDecimal.ZERO;
        return maxCapacityKg.subtract(used);
    }
}
