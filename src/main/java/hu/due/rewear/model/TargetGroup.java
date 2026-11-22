package hu.due.rewear.model;

/**
 * Target demographic lookup ({@code WHATFOR} QR segment, e.g. {@code L} Ladies, {@code M} Men, {@code K} Kids, {@code B} Baby).
 */
public record TargetGroup(
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
