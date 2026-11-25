package hu.due.rewear.model;

import java.time.LocalDateTime;

/** One step in an invoice's life: issued, marked paid, payment withdrawn, or reversed by a credit note. */
public record InvoiceEvent(
        long id,
        long invoiceId,
        String eventType,
        String fromStatus,
        String toStatus,
        String note,
        String userName,
        LocalDateTime createdAt
) { }
