package hu.due.rewear.model;

/**
 * Database role record binding an integer primary key to a {@link RoleCode} and human-readable title.
 */
public record Role(long id, RoleCode code, String name) {
    @Override
    public String toString() {
        return name + " (" + code + ")";
    }
}
