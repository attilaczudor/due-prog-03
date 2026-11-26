package hu.due.rewear.nav;

import hu.due.rewear.util.BusinessClock;
import hu.due.rewear.model.NavSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Talks to the Online Számla service over HTTPS.
 *
 * <p>The base URL is passed in rather than read from the environment enumeration, so the tests can point a
 * real client at a real server running on this machine. Nothing else about the client changes between the two.
 */
public final class NavHttpClient implements NavClient {
    private static final Logger log = LoggerFactory.getLogger(NavHttpClient.class);

    private final String baseUrl;
    private final NavSettings settings;
    private final NavCredentials credentials;
    private final HttpClient http;

    public NavHttpClient(String baseUrl, NavSettings settings, NavCredentials credentials) {
        this(baseUrl, settings, credentials, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    NavHttpClient(String baseUrl, NavSettings settings, NavCredentials credentials, HttpClient http) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.settings = settings;
        this.credentials = credentials;
        this.http = http;
    }

    @Override
    public String tokenExchange() {
        Instant at = BusinessClock.clock().instant();
        String requestId = NavRequestSigner.newRequestId();
        String reply = post("/tokenExchange",
                NavEnvelope.tokenExchange(settings, credentials, requestId, at));
        String encoded = NavResponses.text(reply, "encodedExchangeToken");
        if (encoded == null || encoded.isBlank()) {
            throw new NavException("NAV accepted the token request but returned no token", true);
        }
        return NavRequestSigner.decodeExchangeToken(encoded, credentials.exchangeKey());
    }

    @Override
    public String manageInvoice(String exchangeToken, List<Submission> submissions) {
        if (submissions.isEmpty()) {
            throw new IllegalArgumentException("Nothing to submit");
        }
        List<NavEnvelope.InvoiceOperation> operations = new ArrayList<>();
        for (Submission submission : submissions) {
            String base64 = Base64.getEncoder()
                    .encodeToString(submission.invoiceXml().getBytes(StandardCharsets.UTF_8));
            operations.add(new NavEnvelope.InvoiceOperation(submission.operation().name(), base64));
        }
        Instant at = BusinessClock.clock().instant();
        String requestId = NavRequestSigner.newRequestId();
        String reply = post("/manageInvoice", NavEnvelope.manageInvoice(
                settings, credentials, requestId, at, exchangeToken, operations));
        String transactionId = NavResponses.text(reply, "transactionId");
        if (transactionId == null || transactionId.isBlank()) {
            throw new NavException("NAV accepted the invoices but returned no transaction id", true);
        }
        return transactionId;
    }

    @Override
    public List<NavResponses.ProcessingResult> queryTransactionStatus(String transactionId) {
        Instant at = BusinessClock.clock().instant();
        String requestId = NavRequestSigner.newRequestId();
        String reply = post("/queryTransactionStatus", NavEnvelope.queryTransactionStatus(
                settings, credentials, requestId, at, transactionId));
        return NavResponses.processingResults(NavResponses.parse(reply));
    }

    private String post(String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/xml")
                .header("Accept", "application/xml")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            // The service was unreachable. That is an outage, not a rejection: the invoice is still
            // reportable and the queue should come back to it.
            throw new NavException("Could not reach NAV at " + baseUrl + path + ": " + ex.getMessage(),
                    true, null, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new NavException("The call to NAV was interrupted", true, null, ex);
        }
        if (response.statusCode() >= 300) {
            log.warn("NAV {} returned HTTP {}", path, response.statusCode());
            throw NavResponses.toException(response.body(), response.statusCode());
        }
        return response.body();
    }
}
