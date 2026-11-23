package hu.due.rewear.model;

/**
 * Hierarchical RBAC role codes: immutable {@code SUPER_ADMIN}, {@code ADMIN}, {@code MANAGER},
 * {@code OPERATOR}, and {@code FORKLIFT} (in-house transporter).
 */
public enum RoleCode {
    SUPER_ADMIN,
    ADMIN,
    MANAGER,
    OPERATOR,
    FORKLIFT
}
