package hu.due.rewear.model;

import java.time.LocalDateTime;

/**
 * Internal team messaging channel ({@code ROOM}) or 1-on-1 direct message conversation ({@code DIRECT})
 * modeled after Element/Matrix workspace chat.
 */
public record ChatRoom(
        long id,
        String name,
        String roomType,
        String topic,
        long createdBy,
        LocalDateTime createdAt,
        int unreadCount
) {
    public static final String TYPE_ROOM = "ROOM";
    public static final String TYPE_DIRECT = "DIRECT";

    public boolean isDirect() {
        return TYPE_DIRECT.equalsIgnoreCase(roomType);
    }
}
