package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.AppNotification;
import hu.due.rewear.util.BusinessClock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for per-user operational notifications ({@code notifications} table).
 */
public final class NotificationDao {

    private final DatabaseManager db;

    public NotificationDao(DatabaseManager db) {
        this.db = db;
    }

    public List<AppNotification> findForUser(long userId, int limit) {
        String sql = """
                SELECT id, user_id, category, title, message, target_view, is_read, created_at
                FROM notifications
                WHERE user_id = ?
                ORDER BY is_read ASC, created_at DESC, id DESC
                LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<AppNotification> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new AppNotification(
                            rs.getLong("id"),
                            rs.getLong("user_id"),
                            rs.getString("category"),
                            rs.getString("title"),
                            rs.getString("message"),
                            rs.getString("target_view"),
                            rs.getInt("is_read") == 1,
                            DaoDateUtil.parse(rs.getString("created_at"))
                    ));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to load notifications for user " + userId, e);
        }
    }

    public int countUnread(long userId) {
        String sql = "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = 0";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to count unread notifications", e);
        }
    }

    public int countUnreadForTarget(long userId, String targetView) {
        String sql = "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = 0 AND target_view = ?";
        try (var conn = db.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, targetView);
            try (var rows = ps.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to count unread notifications for " + targetView, ex);
        }
    }

    public void reconcileTranslationAlert(long userId, String state, String title, String message, String targetView) {
        String key = "admin.translation_alert." + userId;
        try (var conn = db.getConnection();
             var ps = conn.prepareStatement("SELECT setting_value FROM app_settings WHERE setting_key = ?")) {
            ps.setString(1, key);
            try (var rows = ps.executeQuery()) {
                if (state.equals(rows.next() ? rows.getString(1) : "")) return;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read translation alert state", ex);
        }
        db.runInTransaction(conn -> {
            try (var ps = conn.prepareStatement("""
                    INSERT INTO app_settings(setting_key, setting_value)
                    SELECT ?, ?
                    WHERE ? <> '' OR EXISTS(SELECT 1 FROM app_settings WHERE setting_key = ?)
                    ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value
                    WHERE app_settings.setting_value <> excluded.setting_value
                    """)) {
                ps.setString(1, key);
                ps.setString(2, state);
                ps.setString(3, state);
                ps.setString(4, key);
                if (ps.executeUpdate() == 0) return;
            }
            try (var ps = conn.prepareStatement("""
                    UPDATE notifications SET is_read = 1
                    WHERE user_id = ? AND is_read = 0 AND target_view = ?
                    """)) {
                ps.setLong(1, userId);
                ps.setString(2, targetView);
                ps.executeUpdate();
            }
            if (!state.isEmpty()) {
                insert(conn, userId, "SYSTEM", title, message, targetView, BusinessClock.now());
            }
        });
    }

    public long insert(Connection conn, long userId, String category, String title,
                       String message, String targetView, LocalDateTime createdAt) throws SQLException {
        String sql = """
                INSERT INTO notifications (user_id, category, title, message, target_view, is_read, created_at)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, category);
            ps.setString(3, title);
            ps.setString(4, message);
            ps.setString(5, targetView);
            ps.setString(6, DaoDateUtil.format(createdAt != null ? createdAt : BusinessClock.now()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for notifications insert");
            }
        }
    }

    public void markRead(long notificationId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE notifications SET is_read = 1 WHERE id = ?")) {
            ps.setLong(1, notificationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to mark notification read: " + notificationId, e);
        }
    }

    public void markAllRead(long userId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE notifications SET is_read = 1 WHERE user_id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to mark all notifications read", e);
        }
    }
}
