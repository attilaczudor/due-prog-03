package hu.due.rewear.model;

/**
 * Where an invoice stands with the tax authority.
 *
 * <p>The obligation is met only when NAV has processed the report successfully. Anything short of
 * {@link #DONE} or {@link #WARN} means the invoice is, as far as the law is concerned, unreported.
 */
public enum NavReportState {
    /** Queued, nothing sent yet. */
    PENDING,
    /** Accepted by NAV and given a transaction id; the outcome is not known yet. */
    SUBMITTED,
    /** NAV saved the report with no complaints. */
    DONE,
    /** NAV saved the report but raised business warnings. Reported, but somebody should read the warnings. */
    WARN,
    /** NAV rejected it. The invoice is NOT reported and somebody has to act. */
    ERROR,
    /** NAV abandoned the transaction. Same consequence as {@link #ERROR}: not reported. */
    ABORTED,
    /** A technical annulment was sent; it takes effect only once a human approves it in NAV's web interface. */
    ANNUL_REQUESTED;

    /** True while the report has not reached a resting place and the queue should keep working on it. */
    public boolean isOpen() {
        return this == PENDING || this == SUBMITTED;
    }

    /** True when the invoice counts as reported. */
    public boolean isReported() {
        return this == DONE || this == WARN;
    }

    /** True when somebody has to do something: the invoice is issued and the authority does not know. */
    public boolean needsAttention() {
        return this == ERROR || this == ABORTED;
    }
}
