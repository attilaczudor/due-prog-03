package hu.due.rewear.nav;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for the Online Számla service, running on this machine.
 *
 * <p>It exists so the reporting path can be exercised for real — real HTTP, real request bytes, real XML —
 * without ever contacting the tax authority. It also checks the request signature with its own small
 * implementation of the published rule, so a mistake in the signer is caught here rather than by a rejection
 * in production.
 */
final class FakeNavService implements AutoCloseable {

    /** What the service should say about a transaction when asked. */
    enum Outcome { DONE, WARN, ERROR, ABORTED, PROCESSING }

    private final HttpServer server;
    private final String exchangeKey;
    private final String signKey;

    final List<String> requests = new ArrayList<>();
    final List<String> submittedInvoices = new ArrayList<>();
    volatile Outcome outcome = Outcome.DONE;
    /** When set, every call answers with this HTTP status and an error document instead. */
    volatile int failWithStatus;
    volatile String signatureProblem;

    FakeNavService(String signKey, String exchangeKey) throws IOException {
        this.signKey = signKey;
        this.exchangeKey = exchangeKey;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/invoiceService/v3/tokenExchange", exchange -> handle(exchange, this::tokenExchange));
        server.createContext("/invoiceService/v3/manageInvoice", exchange -> handle(exchange, this::manageInvoice));
        server.createContext("/invoiceService/v3/queryTransactionStatus",
                exchange -> handle(exchange, this::queryStatus));
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/invoiceService/v3";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private interface Responder {
        String reply(String requestBody);
    }

    private void handle(HttpExchange exchange, Responder responder) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(exchange.getHttpContext().getPath() + " " + body);
        byte[] out;
        int status;
        if (failWithStatus > 0) {
            status = failWithStatus;
            out = ("<?xml version=\"1.0\"?><GeneralExceptionResponse><funcCode>ERROR</funcCode>"
                    + "<message>Service unavailable</message></GeneralExceptionResponse>")
                    .getBytes(StandardCharsets.UTF_8);
        } else {
            checkSignature(body);
            status = 200;
            out = responder.reply(body).getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    /**
     * Recomputes the signature from the request the way the specification describes it, with its own code
     * rather than the application's, and records a complaint if it does not match.
     */
    private void checkSignature(String body) {
        String requestId = between(body, "requestId");
        String timestamp = between(body, "timestamp");
        String claimed = between(body, "requestSignature");
        if (requestId == null || timestamp == null || claimed == null) {
            signatureProblem = "The request is missing its identity";
            return;
        }
        StringBuilder base = new StringBuilder(requestId)
                .append(timestamp.replaceAll("[-:TZ]", "").substring(0, 14))
                .append(signKey);
        // The operation element is nested inside a wrapper of the same name, so the operation is matched as
        // the bare word it is rather than by the first closing tag, which would swallow the wrapper's index.
        Matcher invoices = Pattern.compile("<invoiceOperation>([A-Z_]+)</invoiceOperation>"
                + "\\s*<invoiceData>(.*?)</invoiceData>", Pattern.DOTALL).matcher(body);
        while (invoices.find()) {
            base.append(hex(digest("SHA3-512", invoices.group(1) + invoices.group(2))));
        }
        String expected = hex(digest("SHA3-512", base.toString()));
        if (!expected.equals(claimed)) {
            signatureProblem = "Signature mismatch for request " + requestId;
        }
    }

    private String tokenExchange(String body) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(exchangeKey.getBytes(StandardCharsets.UTF_8), "AES"));
            String encoded = Base64.getEncoder().encodeToString(
                    cipher.doFinal("single-use-token".getBytes(StandardCharsets.UTF_8)));
            return "<?xml version=\"1.0\"?><TokenExchangeResponse><encodedExchangeToken>" + encoded
                    + "</encodedExchangeToken></TokenExchangeResponse>";
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String manageInvoice(String body) {
        Matcher data = Pattern.compile("<invoiceData>(.*?)</invoiceData>", Pattern.DOTALL).matcher(body);
        while (data.find()) {
            submittedInvoices.add(new String(Base64.getDecoder().decode(data.group(1)), StandardCharsets.UTF_8));
        }
        return "<?xml version=\"1.0\"?><ManageInvoiceResponse><transactionId>TX-0001</transactionId>"
                + "</ManageInvoiceResponse>";
    }

    private String queryStatus(String body) {
        String status = switch (outcome) {
            case DONE, WARN, ERROR -> "DONE";
            case ABORTED -> "ABORTED";
            case PROCESSING -> "PROCESSING";
        };
        String messages = switch (outcome) {
            case WARN -> "<businessValidationMessages><validationResultCode>WARN</validationResultCode>"
                    + "<validationErrorCode>INCORRECT_EXCHANGE_RATE</validationErrorCode>"
                    + "<message>The exchange rate looks unusual</message></businessValidationMessages>";
            case ERROR -> "<businessValidationMessages><validationResultCode>ERROR</validationResultCode>"
                    + "<validationErrorCode>INVALID_CUSTOMER_VAT</validationErrorCode>"
                    + "<message>The customer tax number is not valid</message></businessValidationMessages>";
            default -> "";
        };
        return "<?xml version=\"1.0\"?><QueryTransactionStatusResponse><processingResults>"
                + "<processingResult><index>1</index><invoiceStatus>" + status + "</invoiceStatus>"
                + messages + "</processingResult></processingResults></QueryTransactionStatusResponse>";
    }

    private static String between(String xml, String localName) {
        Matcher matcher = Pattern.compile("<(?:[a-zA-Z]+:)?" + localName + "[^>]*>(.*?)</(?:[a-zA-Z]+:)?"
                + localName + ">", Pattern.DOTALL).matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static byte[] digest(String algorithm, String input) {
        try {
            return MessageDigest.getInstance(algorithm).digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02X", b));
        }
        return out.toString().toUpperCase(Locale.ROOT);
    }
}
