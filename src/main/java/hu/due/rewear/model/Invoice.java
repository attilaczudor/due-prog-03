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
        BigDecimal exchangeRate,
        long createdBy,
        String createdByName,
        LocalDateTime createdAt
) {
    public CurrencyCode currencyCode() {
        return CurrencyCode.parse(currency);
    }

    public BigDecimal effectiveExchangeRate() {
        return exchangeRate != null && exchangeRate.compareTo(BigDecimal.ZERO) > 0
                ? exchangeRate : CurrencyCode.DEFAULT_EUR_HUF_RATE;
    }
}
