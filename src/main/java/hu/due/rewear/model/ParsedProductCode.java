package hu.due.rewear.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Decoded representation of a structured ReWear WMS QR product code:
 * {@code YYYY-MM-DD-HH-MM-<WHATFOR>-<TYPE>-<QUALITY>-<PACKAGETYPE>-<WEIGHT>-<HEX2>}
 */
public record ParsedProductCode(
        String rawCode,
        LocalDateTime timestamp,
        String targetGroupCode,
        String garmentTypeCode,
        String qualityGradeCode,
        String packageTypeCode,
        BigDecimal weightKg,
        String hexSuffix
) {
}
