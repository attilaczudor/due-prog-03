package hu.due.rewear.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Order(
        long id,
        String orderNumber,
        long customerId,
        String customerName,
        OrderStatus status,
        BigDecimal totalWeightKg,
        BigDecimal totalPrice,
        String notes,
        long createdBy,
        String createdByFullName,
        LocalDateTime createdAt,
        LocalDateTime shippedAt
) {
}
