package hu.due.rewear.model;

import java.time.LocalDateTime;

public record StockMovement(
        long id,
        long packageId,
        String packageQrCode,
        MovementType movementType,
        Long fromLocationId,
        String fromLocationCode,
        Long toLocationId,
        String toLocationCode,
        long userId,
        String username,
        String userFullName,
        String note,
        LocalDateTime createdAt
) {
}
