package hu.due.rewear.model;

import java.time.LocalDateTime;

/**
 * Immutable warehouse audit log record capturing a single physical movement or status change on a package.
 */
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
