package hu.due.rewear.model;

public record GarmentType(
        long id,
        String code,
        String nameEn,
        String nameHu,
        boolean active
) {
    public String localizedName(String lang) {
        return "hu".equalsIgnoreCase(lang) && nameHu != null && !nameHu.isBlank() ? nameHu : nameEn;
    }

    public String displayLabel(String lang) {
        return code + " — " + localizedName(lang);
    }
}
