package hu.due.rewear.nav;

/**
 * A call to the tax authority did not succeed.
 *
 * <p>{@link #retryable()} separates the two cases that must never be confused. A network outage, a timeout or
 * a server fault is retryable: the same report sent again later may well go through. A rejection on the
 * content — a malformed number, a missing exemption code, a bad signature — is not: sending the same bytes
 * again produces the same rejection, and a queue that retries it forever only hides the problem.
 */
public class NavException extends RuntimeException {
    private final boolean retryable;
    private final String errorCode;

    public NavException(String message, boolean retryable, String errorCode, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.errorCode = errorCode;
    }

    public NavException(String message, boolean retryable) {
        this(message, retryable, null, null);
    }

    public boolean retryable() {
        return retryable;
    }

    /** NAV's own error code when it gave one, otherwise null. */
    public String errorCode() {
        return errorCode;
    }
}
