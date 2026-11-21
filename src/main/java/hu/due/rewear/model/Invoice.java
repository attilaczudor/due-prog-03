package hu.due.rewear.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record Invoice(
        long id,
        String invoiceNumber,
        InvoiceDirection direction,
        InvoiceCategory category,
        Long orderId,
        String orderNumber,
        Long customerId,
        String partnerName,
        String partnerTaxNumber,
        String partnerAddress,
        String partnerEmail,
        InvoiceStatus status,
        LocalDate issueDate,
        LocalDate dueDate,
        LocalDate paidDate,
        BigDecimal netAmount,
        BigDecimal vatRate,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        String currency,
        long createdBy,
        String createdByName,
        LocalDateTime createdAt
) {
}
