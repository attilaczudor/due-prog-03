package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.PersonalTodo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Data Access Object for user-authored personal to-do items ({@code user_todos} table).
 */
public final class TodoDao {

    private final DatabaseManager db;

    public TodoDao(DatabaseManager db) {
        this.db = db;
    }

    public List<PersonalTodo> findByUser(long userId) {
        String sql = """
                SELECT id, user_id, title, priority, due_date, completed, created_at
                FROM user_todos
                WHERE user_id = ?
                ORDER BY completed ASC,
                         CASE priority WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,
                         COALESCE(due_date, '9999-12-31') ASC,
                         id DESC
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<PersonalTodo> list = new ArrayList<>();
                while (rs.next()) {
                    String dueRaw = rs.getString("due_date");
                    LocalDate due = (dueRaw != null && !dueRaw.isBlank()) ? LocalDate.parse(dueRaw) : null;
                    list.add(new PersonalTodo(
                            rs.getLong("id"),
                            rs.getLong("user_id"),
                            rs.getString("title"),
                            rs.getString("priority"),
                            due,
                            rs.getInt("completed") == 1,
                            DaoDateUtil.parse(rs.getString("created_at"))
                    ));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to load personal todos for user " + userId, e);
        }
    }

    public long insert(long userId, String title, String priority, LocalDate dueDate) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("To-do title is required.");
        }
        String prio = normalizePriority(priority);
        String sql = """
                INSERT INTO user_todos (user_id, title, priority, due_date, completed, created_at)
                VALUES (?, ?, ?, ?, 0, ?)
                """;
        return db.executeInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, userId);
                ps.setString(2, title.trim());
                ps.setString(3, prio);
                ps.setString(4, dueDate != null ? dueDate.toString() : null);
                ps.setString(5, DaoDateUtil.format(LocalDateTime.now()));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                    throw new SQLException("No generated key returned for user_todos insert");
                }
            }
        });
    }

    public void setCompleted(long todoId, boolean completed) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE user_todos SET completed = ? WHERE id = ?")) {
            ps.setInt(1, completed ? 1 : 0);
            ps.setLong(2, todoId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to toggle personal todo " + todoId, e);
        }
    }

    public void delete(long todoId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM user_todos WHERE id = ?")) {
            ps.setLong(1, todoId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to delete personal todo " + todoId, e);
        }
    }

    private static String normalizePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return PersonalTodo.PRIO_MEDIUM;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case PersonalTodo.PRIO_HIGH, PersonalTodo.PRIO_MEDIUM, PersonalTodo.PRIO_LOW -> u;
            default -> PersonalTodo.PRIO_MEDIUM;
        };
    }
}
