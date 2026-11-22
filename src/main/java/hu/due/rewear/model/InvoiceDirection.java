package hu.due.rewear.model;

/**
 * Direction of cash flow for an invoice: {@link #OUTGOING} (revenue billed to buyers) or {@link #INCOMING} (expenses paid to providers).
 */
public enum InvoiceDirection {
    OUTGOING,
    INCOMING
}
