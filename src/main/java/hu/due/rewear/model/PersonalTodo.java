package hu.due.rewear.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * User-authored personal to-do item persisted in {@code user_todos}, completely separate from
 * the live system-generated operational to-do queue.
 */
public record PersonalTodo(
        long id,
        long userId,
        String title,
        String priority,
        LocalDate dueDate,
        boolean completed,
        LocalDateTime createdAt
) {
    public static final String PRIO_HIGH = "HIGH";
    public static final String PRIO_MEDIUM = "MEDIUM";
    public static final String PRIO_LOW = "LOW";
}
