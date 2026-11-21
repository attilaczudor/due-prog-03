package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class UserDao {

    private static final String BASE_SELECT = """
            SELECT u.id, u.username, u.password_hash, u.full_name, u.email, u.phone, u.avatar_png,
                   u.role_id, r.code AS role_code, r.name AS role_name, u.active, u.created_at
            FROM users u
            JOIN roles r ON r.id = u.role_id
            """;

    private final DatabaseManager db;

    public UserDao(DatabaseManager db) {
        this.db = db;
    }

    public List<User> findAll() {
        String sql = BASE_SELECT + " ORDER BY u.id";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<User> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list users", e);
        }
    }

    public Optional<User> findById(long id) {
        String sql = BASE_SELECT + " WHERE u.id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find user by id: " + id, e);
        }
    }

    public Optional<User> findByUsername(String username) {
        String sql = BASE_SELECT + " WHERE lower(u.username) = lower(?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find user by username: " + username, e);
        }
    }

    public long count() {
        String sql = "SELECT COUNT(*) FROM users";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new DaoException("Failed to count users", e);
        }
    }

    public long insert(String username, String passwordHash, String fullName, long roleId, boolean active) {
        return db.executeInTransaction(conn -> insert(conn, username, passwordHash, fullName, roleId, active, LocalDateTime.now()));
    }

    public long insert(Connection conn, String username, String passwordHash, String fullName,
                       long roleId, boolean active, LocalDateTime createdAt) throws SQLException {
        String sql = """
                INSERT INTO users (username, password_hash, full_name, role_id, active, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username.trim());
            ps.setString(2, passwordHash);
            ps.setString(3, fullName.trim());
            ps.setLong(4, roleId);
            ps.setInt(5, active ? 1 : 0);
            ps.setString(6, DaoDateUtil.format(createdAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for users insert");
            }
        }
    }

    public void updateProfile(long userId, String fullName, long roleId, boolean active) {
        String sql = "UPDATE users SET full_name = ?, role_id = ?, active = ? WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fullName.trim());
            ps.setLong(2, roleId);
            ps.setInt(3, active ? 1 : 0);
            ps.setLong(4, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update user profile: " + userId, e);
        }
    }

    public void updateAccountSettings(long userId, String fullName, String email, String phone, byte[] avatarPng) {
        String sql = "UPDATE users SET full_name = ?, email = ?, phone = ?, avatar_png = ? WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fullName.trim());
            ps.setString(2, email == null || email.isBlank() ? null : email.trim());
            ps.setString(3, phone == null || phone.isBlank() ? null : phone.trim());
            if (avatarPng != null && avatarPng.length > 0) {
                ps.setBytes(4, avatarPng);
            } else {
                ps.setNull(4, java.sql.Types.BLOB);
            }
            ps.setLong(5, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update user account settings: " + userId, e);
        }
    }

    public void updatePasswordHash(long userId, String passwordHash) {
        String sql = "UPDATE users SET password_hash = ? WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, passwordHash);
            ps.setLong(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update password hash for user: " + userId, e);
        }
    }

    private static User mapRow(ResultSet rs) throws SQLException {
        return new User(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getBytes("avatar_png"),
                rs.getLong("role_id"),
                RoleCode.valueOf(rs.getString("role_code")),
                rs.getString("role_name"),
                rs.getInt("active") == 1,
                DaoDateUtil.parse(rs.getString("created_at"))
        );
    }
}
