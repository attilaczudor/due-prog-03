package hu.due.rewear.model;

import java.time.LocalDateTime;

public record Customer(
        long id,
        String name,
        String contactName,
        String email,
        String phone,
        String address,
        String taxNumber,
        boolean active,
        LocalDateTime createdAt
) {
    @Override
    public String toString() {
        return name;
    }
}
