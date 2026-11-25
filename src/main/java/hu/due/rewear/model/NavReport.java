package hu.due.rewear.model;

import java.time.LocalDateTime;

/**
 * One invoice's standing with the tax authority.
 *
 * <p>The invoice number and partner are carried alongside so that an operator reading the reporting queue
 * sees what the row is about without a second query.
 */
public record NavReport(
        long id,
        long invoiceId,
        String invoiceNumber,
        String partnerName,
        NavOperation operation,
        NavReportState state,
        String requestId,
        String transactionId,
        int invoiceIndex,
        String responseCode,
        String message,
        int attemptCount,
        LocalDateTime nextAttemptAt,
        LocalDateTime submittedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** True when the queue may pick this row up now. */
    public boolean isDue(LocalDateTime now) {
        return state == NavReportState.PENDING && (nextAttemptAt == null || !nextAttemptAt.isAfter(now));
    }
}
