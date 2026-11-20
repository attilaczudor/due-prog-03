package hu.due.rewear.model;

import java.time.LocalDateTime;

public record User(
        long id,
        String username,
        String passwordHash,
        String fullName,
        long roleId,
        RoleCode roleCode,
        String roleName,
        boolean active,
        LocalDateTime createdAt
) {
}
