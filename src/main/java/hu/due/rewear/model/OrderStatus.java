package hu.due.rewear.model;

/**
 * Reversible linear lifecycle states for a customer order ({@code DRAFT} -> {@code PACKING} -> {@code SHIPPED} / {@code CANCELLED}).
 */
public enum OrderStatus {
    DRAFT,
    PACKING,
    SHIPPED,
    CANCELLED
}
