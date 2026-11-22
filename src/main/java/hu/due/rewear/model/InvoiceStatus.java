package hu.due.rewear.model;

/**
 * Reversible payment lifecycle status for an invoice ({@code DRAFT}, {@code ISSUED}, {@code PAID}, {@code OVERDUE}, {@code CANCELLED}).
 */
public enum InvoiceStatus {
    DRAFT,
    ISSUED,
    PAID,
    OVERDUE,
    CANCELLED
}
