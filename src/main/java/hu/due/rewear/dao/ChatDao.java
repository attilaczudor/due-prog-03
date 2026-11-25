package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.ChatMessage;
import hu.due.rewear.model.ChatRoom;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.util.BusinessClock;

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
 * Data Access Object for Element-style internal team messaging rooms, direct messages,
 * room memberships, and chronological chat messages.
 */
public final class ChatDao {

    private final DatabaseManager db;

    public ChatDao(DatabaseManager db) {
        this.db = db;
    }

    public long countRooms() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM chat_rooms");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new DaoException("Failed to count chat rooms", e);
        }
    }

    /** Lists all rooms and direct message threads where {@code userId} is a member, with live unread counts. */
    public List<ChatRoom> findRoomsForUser(long userId) {
        String sql = """
                SELECT r.id, r.name, r.room_type, r.topic, r.created_by, r.created_at,
                       (SELECT COUNT(*) FROM chat_messages m
                        WHERE m.room_id = r.id
                          AND m.sender_id <> ?
                          AND (mem.last_read_at IS NULL OR m.created_at > mem.last_read_at)) AS unread_count
                FROM chat_rooms r
                JOIN chat_room_members mem ON mem.room_id = r.id
                WHERE mem.user_id = ?
                ORDER BY CASE WHEN r.room_type = 'ROOM' THEN 0 ELSE 1 END, r.name
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ChatRoom> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new ChatRoom(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("room_type"),
                            rs.getString("topic"),
                            rs.getLong("created_by"),
                            DaoDateUtil.parse(rs.getString("created_at")),
                            rs.getInt("unread_count")
                    ));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to list chat rooms for user " + userId, e);
        }
    }

    /** Finds an existing 1-on-1 DIRECT room shared by exactly {@code userA} and {@code userB}. */
    public Optional<Long> findDirectRoomBetween(long userA, long userB) {
        String sql = """
                SELECT r.id
                FROM chat_rooms r
                JOIN chat_room_members m1 ON m1.room_id = r.id AND m1.user_id = ?
                JOIN chat_room_members m2 ON m2.room_id = r.id AND m2.user_id = ?
                WHERE r.room_type = 'DIRECT'
                LIMIT 1
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userA);
            ps.setLong(2, userB);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find direct room", e);
        }
    }

    public long createRoom(Connection conn, String name, String roomType, String topic,
                           long createdBy, LocalDateTime createdAt) throws SQLException {
        String sql = """
                INSERT INTO chat_rooms (name, room_type, topic, created_by, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, roomType);
            ps.setString(3, topic != null ? topic : "");
            ps.setLong(4, createdBy);
            ps.setString(5, DaoDateUtil.format(createdAt != null ? createdAt : BusinessClock.now()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for chat_rooms insert");
            }
        }
    }

    public void addMember(Connection conn, long roomId, long userId) throws SQLException {
        String sql = "INSERT OR IGNORE INTO chat_room_members (room_id, user_id, last_read_at) VALUES (?, ?, NULL)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, roomId);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    /** Ensures {@code adminUserId} is enrolled as a member of every group {@code ROOM} (never private DMs). */
    public void ensureAdminEnrolledInAllGroupRooms(long adminUserId) {
        String sql = """
                INSERT OR IGNORE INTO chat_room_members (room_id, user_id, last_read_at)
                SELECT id, ?, NULL FROM chat_rooms WHERE room_type = 'ROOM'
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, adminUserId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to enroll admin in all group rooms", e);
        }
    }

    public List<Long> findMemberIds(long roomId) {
        String sql = "SELECT user_id FROM chat_room_members WHERE room_id = ? ORDER BY user_id";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to list room members for room " + roomId, e);
        }
    }

    public List<ChatMessage> findMessages(long roomId) {
        String sql = """
                SELECT m.id, m.room_id, m.sender_id, u.full_name, u.username, r.code AS role_code,
                       m.body, m.created_at
                FROM chat_messages m
                JOIN users u ON u.id = m.sender_id
                JOIN roles r ON r.id = u.role_id
                WHERE m.room_id = ?
                ORDER BY m.created_at ASC, m.id ASC
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ChatMessage> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new ChatMessage(
                            rs.getLong("id"),
                            rs.getLong("room_id"),
                            rs.getLong("sender_id"),
                            rs.getString("full_name"),
                            rs.getString("username"),
                            RoleCode.valueOf(rs.getString("role_code")),
                            rs.getString("body"),
                            DaoDateUtil.parse(rs.getString("created_at"))
                    ));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to load chat messages for room " + roomId, e);
        }
    }

    public long insertMessage(Connection conn, long roomId, long senderId, String body,
                              LocalDateTime createdAt) throws SQLException {
        String sql = "INSERT INTO chat_messages (room_id, sender_id, body, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, roomId);
            ps.setLong(2, senderId);
            ps.setString(3, body);
            ps.setString(4, DaoDateUtil.format(createdAt != null ? createdAt : BusinessClock.now()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for chat_messages insert");
            }
        }
    }

    public void markRoomRead(long roomId, long userId, LocalDateTime timestamp) {
        String sql = "UPDATE chat_room_members SET last_read_at = ? WHERE room_id = ? AND user_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DaoDateUtil.format(timestamp != null ? timestamp : BusinessClock.now()));
            ps.setLong(2, roomId);
            ps.setLong(3, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to mark chat room read", e);
        }
    }

    public int countTotalUnreadForUser(long userId) {
        String sql = """
                SELECT COUNT(*)
                FROM chat_messages m
                JOIN chat_room_members mem ON mem.room_id = m.room_id AND mem.user_id = ?
                WHERE m.sender_id <> ?
                  AND (mem.last_read_at IS NULL OR m.created_at > mem.last_read_at)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to count unread chat messages", e);
        }
    }
}
