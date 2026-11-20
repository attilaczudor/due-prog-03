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
        long qualityGradeId,
        String qualityGradeCode,
        String qualityGradeNameEn,
        String qualityGradeNameHu,
        BigDecimal defaultPricePerKg,
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
