package hu.due.rewear.nav;

import hu.due.rewear.model.NavOperation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * An invoice in the shape a tax authority needs, with no tax authority in it.
 *
 * <p>This sits deliberately between the application's own {@code Invoice} and the XML that goes on the wire.
 * The reason is a date: cross-border reporting under ViDA begins on 1 July 2030 and will want a structured
 * e-invoice rather than NAV's schema, and Hungary's own specification has already been revised twice while
 * this application was being written. Whatever replaces or joins the current format will need the same facts
 * assembled the same way, so the assembling is done once here and the format is a separate, replaceable step.
 *
 * <p>Amounts carry their HUF counterpart because the reporting obligation is in forints whatever the invoice
 * was written in.
 */
public record ReportableInvoice(
        NavOperation operation,
        String invoiceNumber,
        LocalDate issueDate,
        LocalDate deliveryDate,
        LocalDate paymentDate,
        Party supplier,
        Party customer,
        CustomerVatStatus customerVatStatus,
        String currencyCode,
        BigDecimal exchangeRate,
        List<Line> lines,
        Amounts summary,
        Reference reference) {

    public ReportableInvoice {
        lines = List.copyOf(lines);
    }

    /** How NAV classifies the buyer, which decides whether a tax number may be sent at all. */
    public enum CustomerVatStatus {
        /** A Hungarian taxable person; the tax number is required. */
        DOMESTIC,
        /** A foreign buyer, inside or outside the EU. */
        OTHER,
        /** A private individual; no tax number may be reported. */
        PRIVATE_PERSON
    }

    /**
     * A name and an address. The tax number is split the way the schema wants it: the eight-digit core, the
     * single VAT code digit, and the two-digit county code.
     */
    public record Party(
            String name,
            String taxpayerId,
            String vatCode,
            String countyCode,
            String countryCode,
            String postalCode,
            String city,
            String addressDetail) {
    }

    /** One invoice line, already priced. */
    public record Line(
            int lineNumber,
            String description,
            BigDecimal quantity,
            String unitOfMeasure,
            BigDecimal unitPrice,
            BigDecimal vatPercentage,
            String vatExemptionCase,
            String vatExemptionReason,
            Amounts amounts) {

        /** True when the line is exempt rather than rated, and must carry an exemption case instead of a rate. */
        public boolean isExempt() {
            return vatPercentage == null || vatPercentage.signum() == 0;
        }
    }

    /** A net, VAT and gross triple with the forint equivalents alongside. */
    public record Amounts(
            BigDecimal net, BigDecimal netHuf,
            BigDecimal vat, BigDecimal vatHuf,
            BigDecimal gross, BigDecimal grossHuf) {
    }

    /**
     * What an amending document points back at. {@code modificationIndex} counts upwards from one for each
     * document that amends the same original, which is how NAV orders a chain of corrections.
     */
    public record Reference(String originalInvoiceNumber, int modificationIndex) {
    }
}
