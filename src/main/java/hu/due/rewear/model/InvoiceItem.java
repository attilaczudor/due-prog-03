package hu.due.rewear.model;

import java.math.BigDecimal;

/**
 * Single line item on an invoice (quantity, unit, unit price, and net amount in the invoice's native currency).
 */
public record InvoiceItem(
        long id,
        long invoiceId,
        String description,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal lineNet
) {
}
