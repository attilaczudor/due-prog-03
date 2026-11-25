package hu.due.rewear.nav;

import java.util.List;

/**
 * The three operations this application needs from the Online Számla interface.
 *
 * <p>It is an interface so that the reporting service can be exercised end to end without the live service:
 * the tests run a real HTTP server on the machine and a real client against it, which checks the request
 * bytes and the response handling, while the production endpoint is never contacted.
 */
public interface NavClient {

    /** Obtains the single-use token that a {@link #manageInvoice} call must carry. */
    String tokenExchange();

    /**
     * Sends invoices and returns the transaction id NAV assigns, which is the evidence that the report was
     * accepted for processing.
     */
    String manageInvoice(String exchangeToken, List<Submission> submissions);

    /** Asks what became of a transaction. */
    List<NavResponses.ProcessingResult> queryTransactionStatus(String transactionId);

    /** One invoice going out: what is being done to it, and the document itself. */
    record Submission(hu.due.rewear.model.NavOperation operation, String invoiceXml) { }
}
