package hu.due.rewear.model;

import java.time.LocalDateTime;

/** One recorded step in a report's history: what it moved from, what to, and what NAV or the operator said. */
public record NavReportEvent(
        long id,
        long reportId,
        NavReportState fromState,
        NavReportState toState,
        String detail,
        Long createdBy,
        String createdByName,
        LocalDateTime createdAt) {
}
