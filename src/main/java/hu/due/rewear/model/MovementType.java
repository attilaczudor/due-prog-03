package hu.due.rewear.model;

/**
 * Event types logged in the immutable {@code stock_movements} warehouse audit trail.
 */
public enum MovementType {
    INTAKE,
    RELOCATE,
    SCAN_VERIFY,
    RESERVE,
    RELEASE,
    SHIP,
    /** Held aside after damage was found. */
    QUARANTINE,
    /** Returned to stock from quarantine. */
    QUARANTINE_RELEASE,
    DISCARD
}
