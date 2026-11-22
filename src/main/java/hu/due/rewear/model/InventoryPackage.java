package hu.due.rewear.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable domain projection of a physical sorted-clothing bale/bag/box ({@code inventory_packages})
 * joined with its bilingual dictionary metadata, warehouse location, dual EUR/HUF pricing, and audit info.
 */
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
     * Resolves the default unit price per kg in the requested currency code:
     * uses HUF/kg when currency is HUF, or EUR/kg as the base international price for EUR/USD/other currencies,
     * falling back to the quality grade price when the product price is 0.
     */
    public BigDecimal effectivePricePerKg(String currencyCode) {
        boolean isHuf = currencyCode != null && "HUF".equalsIgnoreCase(currencyCode.trim());
        int decimals = CurrencyDef.defaultDecimalsFor(currencyCode);
        BigDecimal productPrice = isHuf ? garmentPriceHufPerKg : garmentPriceEurPerKg;
        if (productPrice != null && productPrice.compareTo(BigDecimal.ZERO) > 0) {
            return CurrencyDef.roundForScale(productPrice, decimals);
        }
        BigDecimal gradePrice = isHuf ? defaultPriceHufPerKg : defaultPricePerKg;
        return CurrencyDef.roundForScale(gradePrice != null ? gradePrice : BigDecimal.ZERO, decimals);
    }

    public BigDecimal effectivePricePerKg(CurrencyCode currency) {
        return effectivePricePerKg(currency != null ? currency.name() : "EUR");
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
