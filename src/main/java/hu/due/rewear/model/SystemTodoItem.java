package hu.due.rewear.model;

/**
 * Read-only, dynamically computed operational task shown in the System To-Do List on {@code TodoView},
 * ordered logically by priority and workflow urgency for the signed-in user's role.
 */
public record SystemTodoItem(
        int priorityRank,
        String priorityBadge,
        String category,
        String summary,
        String detail,
        String targetView
) {
}
