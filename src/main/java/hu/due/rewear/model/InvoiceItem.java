package hu.due.rewear.model;

import java.math.BigDecimal;

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
