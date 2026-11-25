package hu.due.rewear.nav;

import hu.due.rewear.model.NavSettings;
import hu.due.rewear.model.NavSettings.Field;

import java.time.Instant;
import java.util.List;

/**
 * Builds the three request documents of the Online Számla interface. Every request carries the same header,
 * user and software blocks; only the body differs.
 */
final class NavEnvelope {

    private static final String API_NS = "http://schemas.nav.gov.hu/OSA/3.0/api";
    private static final String COMMON_NS = "http://schemas.nav.gov.hu/NTCA/1.0/common";

    private NavEnvelope() { }

    static String tokenExchange(NavSettings settings, NavCredentials credentials, String requestId, Instant at) {
        return request("TokenExchangeRequest", settings, credentials, requestId, at, List.of(), "");
    }

    /**
     * @param operations the invoices to send, already Base64 encoded, in the order they will be numbered
     */
    static String manageInvoice(NavSettings settings, NavCredentials credentials, String requestId, Instant at,
                                String exchangeToken, List<InvoiceOperation> operations) {
        StringBuilder body = new StringBuilder()
                .append(tag("exchangeToken", exchangeToken))
                .append("<invoiceOperations>")
                // The payload is sent uncompressed: these invoices are a few kilobytes, and compression would
                // add a failure mode for no gain.
                .append(tag("compressedContent", "false"));
        int index = 1;
        for (InvoiceOperation operation : operations) {
            body.append("<invoiceOperation>")
                .append(tag("index", Integer.toString(index++)))
                .append(tag("invoiceOperation", operation.operation()))
                .append(tag("invoiceData", operation.base64Data()))
                .append("</invoiceOperation>");
        }
        body.append("</invoiceOperations>");
        List<String> digestInputs = operations.stream()
                .map(op -> op.operation() + op.base64Data())
                .toList();
        return request("ManageInvoiceRequest", settings, credentials, requestId, at, digestInputs, body.toString());
    }

    static String queryTransactionStatus(NavSettings settings, NavCredentials credentials, String requestId,
                                         Instant at, String transactionId) {
        String body = tag("transactionId", transactionId) + tag("returnOriginalRequest", "false");
        return request("QueryTransactionStatusRequest", settings, credentials, requestId, at, List.of(), body);
    }

    private static String request(String root, NavSettings settings, NavCredentials credentials,
                                  String requestId, Instant at, List<String> digestInputs, String body) {
        String signature = NavRequestSigner.requestSignature(
                requestId, at, credentials.signKey(), digestInputs);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<" + root + " xmlns=\"" + API_NS + "\" xmlns:common=\"" + COMMON_NS + "\">"
                + "<common:header>"
                + common("requestId", requestId)
                + common("timestamp", NavRequestSigner.headerTimestamp(at))
                + common("requestVersion", "3.0")
                + common("headerVersion", "1.0")
                + "</common:header>"
                + "<common:user>"
                + common("login", settings.value(Field.LOGIN))
                + "<common:passwordHash cryptoType=\"SHA-512\">"
                + NavInvoiceXmlWriter.escape(NavRequestSigner.passwordHash(credentials.password()))
                + "</common:passwordHash>"
                + common("taxNumber", settings.value(Field.TAX_NUMBER))
                + "<common:requestSignature cryptoType=\"SHA3-512\">"
                + NavInvoiceXmlWriter.escape(signature)
                + "</common:requestSignature>"
                + "</common:user>"
                + software(settings)
                + body
                + "</" + root + ">";
    }

    private static String software(NavSettings settings) {
        return "<software>"
                + tag("softwareId", settings.value(Field.SOFTWARE_ID))
                + tag("softwareName", settings.value(Field.SOFTWARE_NAME))
                + tag("softwareOperation", settings.value(Field.SOFTWARE_OPERATION))
                + tag("softwareMainVersion", settings.value(Field.SOFTWARE_VERSION))
                + tag("softwareDevName", settings.value(Field.DEVELOPER))
                + tag("softwareDevContact", settings.value(Field.DEVELOPER_CONTACT))
                + optional("softwareDevCountryCode", settings.value(Field.DEVELOPER_COUNTRY))
                + optional("softwareDevTaxNumber", settings.value(Field.DEVELOPER_TAX))
                + "</software>";
    }

    private static String tag(String name, String value) {
        return "<" + name + ">" + NavInvoiceXmlWriter.escape(value) + "</" + name + ">";
    }

    private static String optional(String name, String value) {
        return value == null || value.isBlank() ? "" : tag(name, value);
    }

    private static String common(String name, String value) {
        return "<common:" + name + ">" + NavInvoiceXmlWriter.escape(value) + "</common:" + name + ">";
    }

    /** One invoice as it goes into a {@code manageInvoice} request. */
    record InvoiceOperation(String operation, String base64Data) { }
}
