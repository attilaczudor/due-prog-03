package hu.due.rewear.model;

public record Role(long id, RoleCode code, String name) {
    @Override
    public String toString() {
        return name + " (" + code + ")";
    }
}
