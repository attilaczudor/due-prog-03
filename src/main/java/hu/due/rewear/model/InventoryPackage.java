package hu.due.rewear.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record InventoryPackage(
        long id,
        String qrCode,
        long targetGroupId,
        String targetGroupCode,
        String targetGroupNameEn,
        String targetGroupNameHu,
        long garmentTypeId,
        String garmentTypeCode,
        String garmentTypeNameEn,
        String garmentTypeNameHu,
        BigDecimal garmentPriceEurPerKg,
        BigDecimal garmentPriceHufPerKg,
        long qualityGradeId,
        String qualityGradeCode,
        String qualityGradeNameEn,
        String qualityGradeNameHu,
        BigDecimal defaultPricePerKg,
        BigDecimal defaultPriceHufPerKg,
        long packageTypeId,
        String packageTypeCode,
        String packageTypeNameEn,
        String packageTypeNameHu,
        long locationId,
        String locationCode,
        BigDecimal weightKg,
        String hexSuffix,
        PackageStatus status,
        String notes,
        long createdBy,
        String createdByUsername,
        String createdByFullName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * Resolves the default unit price per kg in the requested currency:
     * uses the product/garment type's configured EUR/kg or HUF/kg price when > 0,
     * falling back to the quality grade's EUR/kg or HUF/kg price.
     */
    public BigDecimal effectivePricePerKg(CurrencyCode currency) {
        CurrencyCode cur = currency != null ? currency : CurrencyCode.EUR;
        BigDecimal productPrice = cur == CurrencyCode.HUF ? garmentPriceHufPerKg : garmentPriceEurPerKg;
        if (productPrice != null && productPrice.compareTo(BigDecimal.ZERO) > 0) {
            return cur.round(productPrice);
        }
        BigDecimal gradePrice = cur == CurrencyCode.HUF ? defaultPriceHufPerKg : defaultPricePerKg;
        return cur.round(gradePrice != null ? gradePrice : BigDecimal.ZERO);
    }

    public String localizedTargetGroup(String lang) {
        return "hu".equalsIgnoreCase(lang) && targetGroupNameHu != null && !targetGroupNameHu.isBlank()
                ? targetGroupNameHu : targetGroupNameEn;
    }

    public String localizedGarmentType(String lang) {
        return "hu".equalsIgnoreCase(lang) && garmentTypeNameHu != null && !garmentTypeNameHu.isBlank()
                ? garmentTypeNameHu : garmentTypeNameEn;
    }

    public String localizedQualityGrade(String lang) {
        return "hu".equalsIgnoreCase(lang) && qualityGradeNameHu != null && !qualityGradeNameHu.isBlank()
                ? qualityGradeNameHu : qualityGradeNameEn;
    }

    public String localizedPackageType(String lang) {
        return "hu".equalsIgnoreCase(lang) && packageTypeNameHu != null && !packageTypeNameHu.isBlank()
                ? packageTypeNameHu : packageTypeNameEn;
    }

    public String shortSummary(String lang) {
        return localizedTargetGroup(lang) + " | "
                + localizedGarmentType(lang) + " | "
                + localizedQualityGrade(lang) + " | "
                + localizedPackageType(lang);
    }
}
