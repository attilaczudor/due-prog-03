package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.NotificationDao;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.i18n.I18nManager.ExternalSourceStatus;
import hu.due.rewear.model.AppNotification;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Operational notification bus delivering targeted alerts to specific users or role groups
 * (e.g. notifying Forklift Drivers when an Operator creates packages or when an Order needs staging).
 */
public final class NotificationService {
    public static final String TRANSLATIONS_TARGET = "admin.translations";

    private final DatabaseManager db;
    private final NotificationDao notificationDao;
    private final UserDao userDao;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public NotificationService(DatabaseManager db, NotificationDao notificationDao, UserDao userDao) {
        this.db = db;
        this.notificationDao = notificationDao;
        this.userDao = userDao;
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void fireChanged() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        }
    }

    /** Delivers a notification to all active users holding any of {@code targetRoles}. */
    public int notifyRoles(Set<RoleCode> targetRoles, String category, String title, String message, String targetView) {
        List<User> recipients = userDao.findAll().stream()
                .filter(u -> u.active() && targetRoles.contains(u.roleCode()))
                .toList();
        if (recipients.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        db.runInTransaction(conn -> {
            for (User u : recipients) {
                notificationDao.insert(conn, u.id(), category, title, message, targetView, now);
            }
        });
        fireChanged();
        return recipients.size();
    }

    public void notifyUser(long userId, String category, String title, String message, String targetView) {
        db.runInTransaction(conn -> notificationDao.insert(conn, userId, category, title, message, targetView, LocalDateTime.now()));
        fireChanged();
    }

    void record(java.sql.Connection conn, long userId, String category, String title, String message,
                String targetView) throws java.sql.SQLException {
        notificationDao.insert(conn, userId, category, title, message, targetView, LocalDateTime.now());
    }

    void publishCommitted() {
        fireChanged();
    }

    public List<AppNotification> listForUser(long userId, int limit) {
        return notificationDao.findForUser(userId, limit);
    }

    public int countUnread(long userId) {
        return notificationDao.countUnread(userId);
    }

    public int checkTranslationUpdate(User user, ExternalSourceStatus status, I18nManager i18n) {
        if (user.roleCode() != RoleCode.ADMIN && user.roleCode() != RoleCode.SUPER_ADMIN) return 0;
        String message = switch (status) {
            case CURRENT -> "";
            case RELOAD_REQUIRED -> i18n.t("admin.i18n.alert.changed");
            case UNAVAILABLE -> i18n.t("admin.i18n.alert.unavailable");
        };
        notificationDao.reconcileTranslationAlert(user.id(),
                status == ExternalSourceStatus.CURRENT ? "" : status.name(),
                i18n.t("admin.i18n.alert.title"), message, TRANSLATIONS_TARGET);
        return notificationDao.countUnreadForTarget(user.id(), TRANSLATIONS_TARGET);
    }

    public void markRead(long notificationId) {
        notificationDao.markRead(notificationId);
        fireChanged();
    }

    public void markAllRead(long userId) {
        notificationDao.markAllRead(userId);
        fireChanged();
    }
}
