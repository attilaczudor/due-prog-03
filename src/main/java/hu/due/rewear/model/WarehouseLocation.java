package hu.due.rewear.model;

import java.math.BigDecimal;

/**
 * Physical warehouse location ({@code ZONE-AISLE-SHELF}) with functional {@link LocationType}
 * ({@code OPERATOR_STATION}, {@code STORAGE}, or {@code DISPATCH_ZONE}), maximum capacity,
 * and live used weight in kg.
 */
public record WarehouseLocation(
        long id,
        String code,
        String zone,
        String aisle,
        String shelf,
        LocationType locationType,
        BigDecimal maxCapacityKg,
        boolean active,
        BigDecimal usedWeightKg
) {
    public WarehouseLocation {
        locationType = locationType != null ? locationType : LocationType.STORAGE;
    }

    /** Convenience constructor defaulting {@link LocationType} to {@link LocationType#STORAGE}. */
    public WarehouseLocation(long id, String code, String zone, String aisle, String shelf,
                             BigDecimal maxCapacityKg, boolean active, BigDecimal usedWeightKg) {
        this(id, code, zone, aisle, shelf, LocationType.STORAGE, maxCapacityKg, active, usedWeightKg);
    }

    public BigDecimal remainingCapacityKg() {
        BigDecimal used = usedWeightKg != null ? usedWeightKg : BigDecimal.ZERO;
        return maxCapacityKg.subtract(used);
    }
}
