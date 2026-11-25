package hu.due.rewear.service;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * What invoicing tells a reporting integration, without knowing which one it is.
 *
 * <p>The calls arrive inside the transaction that issued the document, so a queued report and the invoice it
 * belongs to are committed together or not at all. An invoice that exists but was never queued would be an
 * invoice the authority silently never hears about, which is the failure this arrangement exists to prevent.
 */
public interface InvoiceReportingHook {

    /** Nothing is wired up: used until a reporting integration registers itself, and in tests that do not need one. */
    InvoiceReportingHook NONE = new InvoiceReportingHook() { };

    /** An invoice has just been issued. */
    default void onInvoiceIssued(Connection conn, long invoiceId, long userId) throws SQLException { }

    /** A credit note has just been issued against an original invoice. */
    default void onCreditNoteIssued(Connection conn, long creditNoteId, long originalInvoiceId, long userId)
            throws SQLException { }
}
