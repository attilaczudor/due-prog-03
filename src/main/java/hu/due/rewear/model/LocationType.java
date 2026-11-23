package hu.due.rewear.model;

import java.util.Locale;

/**
 * Functional role of a physical warehouse location:
 * <ul>
 *     <li>{@link #OPERATOR_STATION} — sorting/intake workstation where operators create new packages.</li>
 *     <li>{@link #STORAGE} — standard rack/shelf bay managed by forklift drivers.</li>
 *     <li>{@link #DISPATCH_ZONE} — order staging / connection dock where forklift drivers assemble outgoing orders.</li>
 * </ul>
 */
public enum LocationType {
    OPERATOR_STATION,
    STORAGE,
    DISPATCH_ZONE;

    public static LocationType fromDb(String raw) {
        if (raw == null || raw.isBlank()) {
            return STORAGE;
        }
        try {
            return LocationType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return STORAGE;
        }
    }
}
