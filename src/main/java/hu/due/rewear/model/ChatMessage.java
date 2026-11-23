package hu.due.rewear.model;

import java.time.LocalDateTime;

/**
 * Single chronological message posted inside an internal {@link ChatRoom}.
 */
public record ChatMessage(
        long id,
        long roomId,
        long senderId,
        String senderName,
        String senderUsername,
        RoleCode senderRoleCode,
        String body,
        LocalDateTime createdAt
) {
}
