package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.EmailMessage;
import hu.due.rewear.util.BusinessClock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;

/**
 * Data Access Object for per-user email mailboxes ({@code emails} table).
 */
public final class EmailDao {

    private final DatabaseManager db;

    public EmailDao(DatabaseManager db) {
        this.db = db;
    }

    public List<EmailMessage> findByOwnerAndFolder(long ownerUserId, String folder) {
        return findByOwnerAndFolders(ownerUserId, List.of(folder));
    }

    public List<EmailMessage> findByOwnerAndFolders(long ownerUserId, List<String> folders) {
        if (folders.isEmpty()) return List.of();
        String sql = """
                SELECT id, owner_user_id, folder, from_address, to_addresses, cc_addresses,
                       subject, body, attachment_path, is_read, is_external, delivery_status, created_at
                FROM emails
                WHERE owner_user_id = ? AND folder IN (%s)
                ORDER BY created_at DESC, id DESC
                """.formatted(String.join(", ", Collections.nCopies(folders.size(), "?")));
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ownerUserId);
            for (int i = 0; i < folders.size(); i++) {
                ps.setString(i + 2, folders.get(i).toUpperCase(Locale.ROOT));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<EmailMessage> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to load emails for mailbox folders", e);
        }
    }

    public int countUnreadInbox(long ownerUserId) {
        String sql = "SELECT COUNT(*) FROM emails WHERE owner_user_id = ? AND folder = 'INBOX' AND is_read = 0";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ownerUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to count unread emails", e);
        }
    }

    public long countAll() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM emails");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new DaoException("Failed to count emails", e);
        }
    }

    public long insert(Connection conn, long ownerUserId, String folder, String fromAddress,
                       String toAddresses, String ccAddresses, String subject, String body,
                       String attachmentPath, boolean isRead, boolean isExternal,
                       String deliveryStatus, LocalDateTime createdAt) throws SQLException {
        String sql = """
                INSERT INTO emails (owner_user_id, folder, from_address, to_addresses, cc_addresses,
                                    subject, body, attachment_path, is_read, is_external, delivery_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, ownerUserId);
            ps.setString(2, folder.toUpperCase(Locale.ROOT));
            ps.setString(3, fromAddress);
            ps.setString(4, toAddresses);
            ps.setString(5, ccAddresses != null ? ccAddresses : "");
            ps.setString(6, subject);
            ps.setString(7, body);
            ps.setString(8, attachmentPath);
            ps.setInt(9, isRead ? 1 : 0);
            ps.setInt(10, isExternal ? 1 : 0);
            ps.setString(11, deliveryStatus != null ? deliveryStatus : "DELIVERED");
            ps.setString(12, DaoDateUtil.format(createdAt != null ? createdAt : BusinessClock.now()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for emails insert");
            }
        }
    }

    /**
     * Returns every distinct attachment path stored on any mail row, in any folder (drafts and
     * trash included). A file is only safe to delete when it is absent from this set.
     */
    public Set<String> findAttachmentPaths() {
        String sql = "SELECT DISTINCT attachment_path FROM emails "
                + "WHERE attachment_path IS NOT NULL AND attachment_path <> ''";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            Set<String> paths = new HashSet<>();
            while (rs.next()) {
                paths.add(rs.getString(1));
            }
            return paths;
        } catch (SQLException e) {
            throw new DaoException("Failed to list mail attachment paths", e);
        }
    }

    /** Counts mail rows, in any folder, whose attachment is exactly {@code attachmentPath}. */
    public int countByAttachmentPath(String attachmentPath) {
        String sql = "SELECT COUNT(*) FROM emails WHERE attachment_path = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, attachmentPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to count mail rows for an attachment", e);
        }
    }

    public void markRead(long emailId, boolean read) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE emails SET is_read = ? WHERE id = ?")) {
            ps.setInt(1, read ? 1 : 0);
            ps.setLong(2, emailId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update read status for email " + emailId, e);
        }
    }

    public void moveToFolder(long emailId, String folder) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE emails SET folder = ? WHERE id = ?")) {
            ps.setString(1, folder.toUpperCase(Locale.ROOT));
            ps.setLong(2, emailId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to move email " + emailId + " to " + folder, e);
        }
    }

    public void deleteById(long emailId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM emails WHERE id = ?")) {
            ps.setLong(1, emailId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to delete email " + emailId, e);
        }
    }

    private static EmailMessage mapRow(ResultSet rs) throws SQLException {
        return new EmailMessage(
                rs.getLong("id"),
                rs.getLong("owner_user_id"),
                rs.getString("folder"),
                rs.getString("from_address"),
                rs.getString("to_addresses"),
                rs.getString("cc_addresses"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("attachment_path"),
                rs.getInt("is_read") == 1,
                rs.getInt("is_external") == 1,
                rs.getString("delivery_status"),
                DaoDateUtil.parse(rs.getString("created_at"))
        );
    }
}
