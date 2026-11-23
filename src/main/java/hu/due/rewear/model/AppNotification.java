package hu.due.rewear.model;

import java.time.LocalDateTime;

/**
 * Targeted operational alert delivered to a specific warehouse user (e.g. forklift putaway request,
 * order staging alert, order ready-to-ship alert, or overdue invoice warning).
 */
public record AppNotification(
        long id,
        long userId,
        String category,
        String title,
        String message,
        String targetView,
        boolean read,
        LocalDateTime createdAt
) {
    public static final String CAT_PUTAWAY = "PUTAWAY";
    public static final String CAT_ORDER_STAGING = "ORDER_STAGING";
    public static final String CAT_ORDER_READY = "ORDER_READY";
    public static final String CAT_INVOICE = "INVOICE";
    public static final String CAT_SYSTEM = "SYSTEM";
}
