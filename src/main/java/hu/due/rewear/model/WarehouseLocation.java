package hu.due.rewear.model;

import java.math.BigDecimal;

/**
 * Physical storage bay/shelf location ({@code ZONE-AISLE-SHELF}) with maximum capacity and live used weight in kg.
 */
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
