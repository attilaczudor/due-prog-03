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

/**
 * Data Access Object for application user accounts ({@code users} table), handling BCrypt password hashes,
 * split family/given names, editable {@code @handle} usernames, profile avatars, and RBAC role assignments.
 */
public final class UserDao {

    private static final String BASE_SELECT = """
            SELECT u.id, u.username, u.password_hash, u.full_name, u.last_name, u.first_name,
                   u.email, u.phone, u.avatar_png, u.assigned_station_id,
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
        String clean = fullName.replaceAll("\\s*\\([^)]*\\)\\s*", " ").trim();
        String[] parts = clean.split("\\s+", 2);
        String ln = parts.length > 0 ? parts[0] : "";
        String fn = parts.length > 1 ? parts[1] : "";
        String sql = """
                INSERT INTO users (username, password_hash, full_name, last_name, first_name, role_id, active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username.trim());
            ps.setString(2, passwordHash);
            ps.setString(3, clean);
            ps.setString(4, ln);
            ps.setString(5, fn);
            ps.setLong(6, roleId);
            ps.setInt(7, active ? 1 : 0);
            ps.setString(8, DaoDateUtil.format(createdAt));
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
        String clean = fullName.replaceAll("\\s*\\([^)]*\\)\\s*", " ").trim();
        String[] parts = clean.split("\\s+", 2);
        String ln = parts.length > 0 ? parts[0] : "";
        String fn = parts.length > 1 ? parts[1] : "";
        updateProfile(userId, ln, fn, roleId, active);
    }

    public void updateProfile(long userId, String lastName, String firstName, long roleId, boolean active) {
        String ln = lastName == null ? "" : lastName.trim();
        String fn = firstName == null ? "" : firstName.trim();
        String full = (ln + " " + fn).trim();
        String sql = "UPDATE users SET full_name = ?, last_name = ?, first_name = ?, role_id = ?, active = ? WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, full);
            ps.setString(2, ln);
            ps.setString(3, fn);
            ps.setLong(4, roleId);
            ps.setInt(5, active ? 1 : 0);
            ps.setLong(6, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update user profile: " + userId, e);
        }
    }

    public void updateEmail(long userId, String email) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET email = ? WHERE id = ?")) {
            ps.setString(1, email != null ? email.trim() : null);
            ps.setLong(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update user email: " + userId, e);
        }
    }

    public void updateManagedAccount(long userId, String lastName, String firstName, long roleId,
                                     boolean active, Long stationId, String email, String passwordHash) {
        String ln = lastName == null ? "" : lastName.trim();
        String fn = firstName == null ? "" : firstName.trim();
        String sql = """
                UPDATE users
                SET full_name = ?, last_name = ?, first_name = ?, role_id = ?, active = ?, assigned_station_id = ?,
                    email = COALESCE(?, email), password_hash = COALESCE(?, password_hash)
                WHERE id = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, (ln + " " + fn).trim());
            ps.setString(2, ln);
            ps.setString(3, fn);
            ps.setLong(4, roleId);
            ps.setInt(5, active ? 1 : 0);
            ps.setObject(6, stationId);
            ps.setString(7, email == null ? null : email.trim());
            ps.setString(8, passwordHash);
            ps.setLong(9, userId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("User not found: " + userId);
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to update user account: " + userId, e);
        }
    }

    public void updateAccountSettings(long userId, String fullName, String phone, byte[] avatarPng) {
        String clean = fullName.replaceAll("\\s*\\([^)]*\\)\\s*", " ").trim();
        String[] parts = clean.split("\\s+", 2);
        updateAccountSettings(userId, null, parts[0], parts.length > 1 ? parts[1] : "", phone, avatarPng, null);
    }

    public void updateAccountSettings(long userId, String username, String lastName, String firstName,
                                      String phone, byte[] avatarPng, Long roleId) {
        String ln = lastName == null ? "" : lastName.trim();
        String fn = firstName == null ? "" : firstName.trim();
        String full = (ln + " " + fn).trim();
        StringBuilder sql = new StringBuilder(
                "UPDATE users SET full_name = ?, last_name = ?, first_name = ?, phone = ?, avatar_png = ?");
        if (username != null && !username.isBlank()) {
            sql.append(", username = ?");
        }
        if (roleId != null) {
            sql.append(", role_id = ?");
        }
        sql.append(" WHERE id = ?");

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, full);
            ps.setString(idx++, ln);
            ps.setString(idx++, fn);
            ps.setString(idx++, phone == null || phone.isBlank() ? null : phone.trim());
            if (avatarPng != null && avatarPng.length > 0) {
                ps.setBytes(idx++, avatarPng);
            } else {
                ps.setNull(idx++, java.sql.Types.BLOB);
            }
            if (username != null && !username.isBlank()) {
                ps.setString(idx++, username.trim());
            }
            if (roleId != null) {
                ps.setLong(idx++, roleId);
            }
            ps.setLong(idx, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update user account settings: " + userId, e);
        }
    }

    public void updateAssignedStation(long userId, Long stationId) {
        String sql = "UPDATE users SET assigned_station_id = ? WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (stationId != null && stationId > 0) {
                ps.setLong(1, stationId);
            } else {
                ps.setNull(1, java.sql.Types.INTEGER);
            }
            ps.setLong(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update assigned station for user: " + userId, e);
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
        long stRaw = rs.getLong("assigned_station_id");
        Long stationId = rs.wasNull() ? null : stRaw;
        return new User(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("full_name"),
                rs.getString("last_name"),
                rs.getString("first_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getBytes("avatar_png"),
                rs.getLong("role_id"),
                RoleCode.valueOf(rs.getString("role_code")),
                rs.getString("role_name"),
                rs.getInt("active") == 1,
                DaoDateUtil.parse(rs.getString("created_at")),
                stationId
        );
    }
}
