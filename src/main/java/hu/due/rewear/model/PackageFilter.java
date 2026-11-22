package hu.due.rewear.model;

/**
 * Multi-criteria filter parameter object used by {@link hu.due.rewear.dao.InventoryPackageDao#findByFilter}.
 */
public record PackageFilter(
        String qrSearch,
        PackageStatus status,
        Long targetGroupId,
        Long garmentTypeId,
        Long qualityGradeId,
        Long packageTypeId,
        Long locationId
) {
    public static PackageFilter empty() {
        return new PackageFilter(null, null, null, null, null, null, null);
    }
}
