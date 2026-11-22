package hu.due.rewear.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Outgoing (revenue) or Incoming (expense) invoice header preserving its native currency,
 * VAT/tax type code, partner details, and reversible payment status.
 */
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
        String taxTypeCode,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        String currency,
        BigDecimal exchangeRate,
        String attachmentPath,
        long createdBy,
        String createdByName,
        LocalDateTime createdAt
) {
    public boolean hasAttachment() {
        return attachmentPath != null && !attachmentPath.isBlank();
    }

    public String effectiveCurrencyCode() {
        return (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase(Locale.ROOT) : "EUR";
    }

    public int currencyDecimals() {
        return CurrencyDef.defaultDecimalsFor(effectiveCurrencyCode());
    }

    public String effectiveTaxTypeCode() {
        if (taxTypeCode != null && !taxTypeCode.isBlank()) {
            return taxTypeCode.trim().toUpperCase(Locale.ROOT);
        }
        if (vatRate != null && vatRate.compareTo(BigDecimal.ZERO) == 0) {
            return "EU-0";
        }
        return "HU-27";
    }
}
