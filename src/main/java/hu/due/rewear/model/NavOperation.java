package hu.due.rewear.model;

/**
 * What the tax authority is being told about an invoice.
 *
 * <p>{@code STORNO} and {@code ANNUL} are not the same thing and the difference matters. A {@code STORNO} is a
 * commercial cancellation: the invoice was real, it is being withdrawn, and the withdrawal is itself a
 * reportable document. An {@code ANNUL} says the earlier <em>report</em> was wrong — it was sent by mistake,
 * or with data that was never on any invoice. Technical annulment has to be approved by a human in NAV's own
 * web interface before it takes effect, so this application can request one but can never complete one.
 */
public enum NavOperation {
    CREATE,
    MODIFY,
    STORNO,
    ANNUL
}
