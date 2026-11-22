package hu.due.rewear.model;

/**
 * Accounting category classifying outgoing revenue streams and incoming warehouse expenses.
 */
public enum InvoiceCategory {
    WHOLESALE_SALES(InvoiceDirection.OUTGOING),
    OTHER_INCOME(InvoiceDirection.OUTGOING),
    RAW_STOCK_PURCHASE(InvoiceDirection.INCOMING),
    TRANSPORT_LOGISTICS(InvoiceDirection.INCOMING),
    WAREHOUSE_RENT(InvoiceDirection.INCOMING),
    UTILITIES(InvoiceDirection.INCOMING),
    PACKAGING_SUPPLIES(InvoiceDirection.INCOMING),
    OTHER_EXPENSE(InvoiceDirection.INCOMING);

    private final InvoiceDirection defaultDirection;

    InvoiceCategory(InvoiceDirection defaultDirection) {
        this.defaultDirection = defaultDirection;
    }

    public InvoiceDirection defaultDirection() {
        return defaultDirection;
    }
}
