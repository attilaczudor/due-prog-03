package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.Role;
import hu.due.rewear.model.RoleCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for RBAC role definitions ({@code roles} table: SUPER_ADMIN, ADMIN, MANAGER, OPERATOR).
 */
public final class RoleDao {

    private final DatabaseManager db;

    public RoleDao(DatabaseManager db) {
        this.db = db;
    }

    public List<Role> findAll() {
        String sql = "SELECT id, code, name FROM roles ORDER BY id";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Role> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list roles", e);
        }
    }

    public Optional<Role> findByCode(RoleCode code) {
        String sql = "SELECT id, code, name FROM roles WHERE code = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find role by code: " + code, e);
        }
    }

    /** Returns the id of the role with this code, creating it first if the schema has not provided it. */
    public long ensure(Connection conn, RoleCode code, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM roles WHERE code = ?")) {
            ps.setString(1, code.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return insert(conn, code, name);
    }

    public long insert(Connection conn, RoleCode code, String name) throws SQLException {
        String sql = "INSERT INTO roles (code, name) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code.name());
            ps.setString(2, name);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for roles insert");
            }
        }
    }

    private static Role mapRow(ResultSet rs) throws SQLException {
        return new Role(
                rs.getLong("id"),
                RoleCode.valueOf(rs.getString("code")),
                rs.getString("name")
        );
    }
}
