package hu.due.rewear.nav;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Renders a {@link ReportableInvoice} as the {@code InvoiceData} document of the Online Számla 3.0 schema.
 *
 * <p>The XML is written directly rather than through a binding library: the document is small, the element
 * order is fixed by the schema, and a generated binding would be another dependency to keep in step with a
 * specification that has been revised twice in a year. Every value goes through {@link #escape} on the way in.
 *
 * <p>One rule here refuses rather than guesses. A line at a zero rate must carry an exemption case from NAV's
 * own code list, and that list is not something to invent: an invented code is a rejected report, and a
 * rejected report is an unreported invoice. If the tax type in use has no case configured, this throws, and
 * the operator is told which tax type to fix.
 */
public final class NavInvoiceXmlWriter {

    private static final String DATA_NS = "http://schemas.nav.gov.hu/OSA/3.0/data";
    private static final String BASE_NS = "http://schemas.nav.gov.hu/OSA/3.0/base";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private NavInvoiceXmlWriter() { }

    public static String toXml(ReportableInvoice invoice) {
        StringBuilder xml = new StringBuilder(2048);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
           .append("<InvoiceData xmlns=\"").append(DATA_NS).append("\" xmlns:base=\"").append(BASE_NS).append("\">")
           .append(el("invoiceNumber", invoice.invoiceNumber()))
           .append(el("invoiceIssueDate", date(invoice.issueDate())))
           .append(el("completenessIndicator", "false"))
           .append("<invoiceMain><invoice>");

        if (invoice.reference() != null) {
            xml.append("<invoiceReference>")
               .append(el("originalInvoiceNumber", invoice.reference().originalInvoiceNumber()))
               // The original is always present in this application: a credit note is created from an invoice
               // that exists here, never for one issued by some other system.
               .append(el("modifyWithoutMaster", "false"))
               .append(el("modificationIndex", Integer.toString(invoice.reference().modificationIndex())))
               .append("</invoiceReference>");
        }

        xml.append("<invoiceHead>")
           .append(supplier(invoice))
           .append(customer(invoice))
           .append(detail(invoice))
           .append("</invoiceHead>")
           .append(lines(invoice))
           .append(summary(invoice))
           .append("</invoice></invoiceMain></InvoiceData>");
        return xml.toString();
    }

    private static String supplier(ReportableInvoice invoice) {
        ReportableInvoice.Party s = invoice.supplier();
        require(s.taxpayerId(), "the company tax number");
        return "<supplierInfo>"
                + "<supplierTaxNumber>"
                + baseEl("taxpayerId", s.taxpayerId())
                + optionalBase("vatCode", s.vatCode())
                + optionalBase("countyCode", s.countyCode())
                + "</supplierTaxNumber>"
                + el("supplierName", require(s.name(), "the company name"))
                + "<supplierAddress>" + simpleAddress(s) + "</supplierAddress>"
                + "</supplierInfo>";
    }

    private static String customer(ReportableInvoice invoice) {
        ReportableInvoice.Party c = invoice.customer();
        StringBuilder out = new StringBuilder("<customerInfo>")
                .append(el("customerVatStatus", invoice.customerVatStatus().name()));
        switch (invoice.customerVatStatus()) {
            case DOMESTIC -> out.append("<customerVatData><customerTaxNumber>")
                    .append(baseEl("taxpayerId", require(c.taxpayerId(), "the customer's tax number")))
                    .append(optionalBase("vatCode", c.vatCode()))
                    .append(optionalBase("countyCode", c.countyCode()))
                    .append("</customerTaxNumber></customerVatData>");
            case OTHER -> {
                if (c.taxpayerId() != null && !c.taxpayerId().isBlank()) {
                    out.append("<customerVatData>")
                       .append(el("communityVatNumber", c.taxpayerId()))
                       .append("</customerVatData>");
                }
            }
            // A private individual's tax number must not be reported at all, so nothing is written here.
            case PRIVATE_PERSON -> { }
        }
        if (c.name() != null && !c.name().isBlank()) {
            out.append(el("customerName", c.name()));
        }
        if (c.city() != null && !c.city().isBlank()) {
            out.append("<customerAddress>").append(simpleAddress(c)).append("</customerAddress>");
        }
        return out.append("</customerInfo>").toString();
    }

    private static String detail(ReportableInvoice invoice) {
        StringBuilder out = new StringBuilder("<invoiceDetail>")
                .append(el("invoiceCategory", "NORMAL"))
                .append(el("invoiceDeliveryDate",
                        date(invoice.deliveryDate() != null ? invoice.deliveryDate() : invoice.issueDate())))
                .append(el("currencyCode", invoice.currencyCode()))
                .append(el("exchangeRate", amount(invoice.exchangeRate())));
        if (invoice.paymentDate() != null) {
            out.append(el("paymentDate", date(invoice.paymentDate())));
        }
        // The application issues PDFs, never paper, so the appearance is always electronic.
        return out.append(el("invoiceAppearance", "ELECTRONIC")).append("</invoiceDetail>").toString();
    }

    private static String lines(ReportableInvoice invoice) {
        StringBuilder out = new StringBuilder("<invoiceLines>").append(el("mergedItemIndicator", "false"));
        for (ReportableInvoice.Line line : invoice.lines()) {
            out.append("<line>")
               .append(el("lineNumber", Integer.toString(line.lineNumber())))
               .append(el("lineExpressionIndicator", "true"))
               .append(el("lineDescription", line.description()))
               .append(el("quantity", amount(line.quantity())))
               .append(el("unitOfMeasure", line.unitOfMeasure()))
               .append(el("unitPrice", amount(line.unitPrice())))
               .append("<lineAmountsNormal>")
               .append("<lineNetAmountData>")
               .append(el("lineNetAmount", amount(line.amounts().net())))
               .append(el("lineNetAmountHUF", amount(line.amounts().netHuf())))
               .append("</lineNetAmountData>")
               .append(vatRate(line))
               .append("<lineVatData>")
               .append(el("lineVatAmount", amount(line.amounts().vat())))
               .append(el("lineVatAmountHUF", amount(line.amounts().vatHuf())))
               .append("</lineVatData>")
               .append("<lineGrossAmountData>")
               .append(el("lineGrossAmountNormal", amount(line.amounts().gross())))
               .append(el("lineGrossAmountNormalHUF", amount(line.amounts().grossHuf())))
               .append("</lineGrossAmountData>")
               .append("</lineAmountsNormal>")
               .append("</line>");
        }
        return out.append("</invoiceLines>").toString();
    }

    private static String vatRate(ReportableInvoice.Line line) {
        if (!line.isExempt()) {
            return "<lineVatRate>" + el("vatPercentage", rate(line.vatPercentage())) + "</lineVatRate>";
        }
        if (line.vatExemptionCase() == null || line.vatExemptionCase().isBlank()) {
            throw new NavException("Line " + line.lineNumber() + " is at a zero rate but the tax type carries"
                    + " no NAV exemption case. Set the exemption case on the tax type before reporting.", false);
        }
        return "<lineVatRate><vatExemption>"
                + baseEl("case", line.vatExemptionCase())
                + baseEl("reason", line.vatExemptionReason() == null ? line.vatExemptionCase()
                        : line.vatExemptionReason())
                + "</vatExemption></lineVatRate>";
    }

    private static String summary(ReportableInvoice invoice) {
        ReportableInvoice.Amounts total = invoice.summary();
        ReportableInvoice.Line first = invoice.lines().isEmpty() ? null : invoice.lines().get(0);
        StringBuilder out = new StringBuilder("<invoiceSummary><summaryNormal><summaryByVatRate>");
        if (first != null && !first.isExempt()) {
            out.append("<vatRate>").append(el("vatPercentage", rate(first.vatPercentage()))).append("</vatRate>");
        } else if (first != null) {
            out.append("<vatRate><vatExemption>")
               .append(baseEl("case", first.vatExemptionCase() == null ? "" : first.vatExemptionCase()))
               .append(baseEl("reason", first.vatExemptionReason() == null ? "" : first.vatExemptionReason()))
               .append("</vatExemption></vatRate>");
        }
        return out.append("<vatRateNetData>")
                .append(el("vatRateNetAmount", amount(total.net())))
                .append(el("vatRateNetAmountHUF", amount(total.netHuf())))
                .append("</vatRateNetData>")
                .append("<vatRateVatData>")
                .append(el("vatRateVatAmount", amount(total.vat())))
                .append(el("vatRateVatAmountHUF", amount(total.vatHuf())))
                .append("</vatRateVatData>")
                .append("</summaryByVatRate>")
                .append(el("invoiceNetAmount", amount(total.net())))
                .append(el("invoiceNetAmountHUF", amount(total.netHuf())))
                .append(el("invoiceVatAmount", amount(total.vat())))
                .append(el("invoiceVatAmountHUF", amount(total.vatHuf())))
                .append("</summaryNormal>")
                .append("<summaryGrossData>")
                .append(el("invoiceGrossAmount", amount(total.gross())))
                .append(el("invoiceGrossAmountHUF", amount(total.grossHuf())))
                .append("</summaryGrossData>")
                .append("</invoiceSummary>").toString();
    }

    private static String simpleAddress(ReportableInvoice.Party party) {
        return "<base:simpleAddress>"
                + baseEl("countryCode", blankTo(party.countryCode(), "HU"))
                + optionalBase("postalCode", party.postalCode())
                + baseEl("city", blankTo(party.city(), "-"))
                + baseEl("additionalAddressDetail", blankTo(party.addressDetail(), "-"))
                + "</base:simpleAddress>";
    }

    private static String el(String name, String value) {
        return "<" + name + ">" + escape(value) + "</" + name + ">";
    }

    private static String baseEl(String name, String value) {
        return "<base:" + name + ">" + escape(value) + "</base:" + name + ">";
    }

    private static String optionalBase(String name, String value) {
        return value == null || value.isBlank() ? "" : baseEl(name, value);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new NavException("Cannot report the invoice: " + what + " is not set.", false);
        }
        return value;
    }

    private static String date(LocalDate value) {
        return DATE.format(value);
    }

    /** Amounts go out with their own digits; the schema accepts a plain decimal and no exponent. */
    private static String amount(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).toPlainString();
    }

    /** A rate is sent as a fraction, so 27% is 0.27. */
    private static String rate(BigDecimal percentage) {
        return percentage.movePointLeft(2).stripTrailingZeros().toPlainString();
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }
}
