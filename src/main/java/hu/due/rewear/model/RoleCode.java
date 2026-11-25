package hu.due.rewear.model;

/** Warehouse roles. OPERATOR is the persisted code for the station manager. */
public enum RoleCode {
    SUPER_ADMIN,
    ADMIN,
    MANAGER,
    OPERATOR,
    SORTER,
    SORTING_HELPER,
    FORKLIFT;

    public boolean usesInternalMailOnly() {
        return this == OPERATOR || this == SORTER || this == SORTING_HELPER || this == FORKLIFT;
    }
}
