package hu.due.rewear.model;

import java.math.BigDecimal;

/**
 * Snapshot of live warehouse inventory valuation ({@code IN_STOCK} and {@code RESERVED} packages)
 * computed natively in both EUR and HUF using each package's effective product/grade price per kg.
 */
public record StockValuation(
        int packageCount,
        BigDecimal totalWeightKg,
        BigDecimal totalEur,
        BigDecimal totalHuf
) {
    public static StockValuation zero() {
        return new StockValuation(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
