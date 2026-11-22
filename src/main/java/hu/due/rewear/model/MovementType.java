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
    DISCARD
}
