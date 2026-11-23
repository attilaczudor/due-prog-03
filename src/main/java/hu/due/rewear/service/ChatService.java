package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.ChatDao;
import hu.due.rewear.model.ChatMessage;
import hu.due.rewear.model.ChatRoom;
import hu.due.rewear.model.User;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Element/Matrix-style internal company messaging service supporting group rooms and 1-on-1 direct messages.
 */
public final class ChatService {

    private final DatabaseManager db;
    private final ChatDao chatDao;

    public ChatService(DatabaseManager db, ChatDao chatDao) {
        this.db = db;
        this.chatDao = chatDao;
    }

    public List<ChatRoom> listRoomsForUser(long userId) {
        return chatDao.findRoomsForUser(userId);
    }

    public List<ChatMessage> listMessages(long roomId) {
        return chatDao.findMessages(roomId);
    }

    public List<Long> listMemberIds(long roomId) {
        return chatDao.findMemberIds(roomId);
    }

    public int countTotalUnread(long userId) {
        return chatDao.countTotalUnreadForUser(userId);
    }

    /** Creates a new group room and adds the creator plus all chosen member user IDs atomically. */
    public long createRoom(String name, String topic, long creatorId, Collection<Long> memberIds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Room name is required.");
        }
        String cleanName = name.trim();
        if (!cleanName.startsWith("#")) {
            cleanName = "#" + cleanName;
        }
        final String finalName = cleanName;
        return db.executeInTransaction(conn -> {
            long roomId = chatDao.createRoom(conn, finalName, ChatRoom.TYPE_ROOM,
                    topic != null ? topic.trim() : "", creatorId, LocalDateTime.now());
            chatDao.addMember(conn, roomId, creatorId);
            if (memberIds != null) {
                for (Long uid : memberIds) {
                    if (uid != null) {
                        chatDao.addMember(conn, roomId, uid);
                    }
                }
            }
            return roomId;
        });
    }

    /** Returns an existing 1-on-1 direct message room between {@code me} and {@code other}, or creates one. */
    public long getOrCreateDirectMessage(User me, User other) {
        if (me == null || other == null || me.id() == other.id()) {
            throw new IllegalArgumentException("Select another teammate for a direct message.");
        }
        Optional<Long> existing = chatDao.findDirectRoomBetween(me.id(), other.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        String dmName = me.displayName() + " ↔ " + other.displayName();
        return db.executeInTransaction(conn -> {
            long roomId = chatDao.createRoom(conn, dmName, ChatRoom.TYPE_DIRECT,
                    "Direct Message", me.id(), LocalDateTime.now());
            chatDao.addMember(conn, roomId, me.id());
            chatDao.addMember(conn, roomId, other.id());
            return roomId;
        });
    }

    public void addMemberToRoom(long roomId, long userId) {
        db.runInTransaction(conn -> chatDao.addMember(conn, roomId, userId));
    }

    public long sendMessage(long roomId, long senderId, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Message text cannot be empty.");
        }
        LocalDateTime now = LocalDateTime.now();
        long msgId = db.executeInTransaction(conn -> chatDao.insertMessage(conn, roomId, senderId, body.trim(), now));
        chatDao.markRoomRead(roomId, senderId, now);
        return msgId;
    }

    public void markRoomRead(long roomId, long userId) {
        chatDao.markRoomRead(roomId, userId, LocalDateTime.now());
    }
}
