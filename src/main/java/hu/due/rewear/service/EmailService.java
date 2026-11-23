package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.EmailDao;
import hu.due.rewear.dao.SettingsDao;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.model.EmailMessage;
import hu.due.rewear.model.EmailSettings;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Coordinates corporate email addresses, Super-Admin SMTP/IMAP gateway settings,
 * internal teammate delivery, and simulated external SMTP/IMAP client workflows.
 */
public final class EmailService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailService.class);

    private final DatabaseManager db;
    private final EmailDao emailDao;
    private final SettingsDao settingsDao;
    private final UserDao userDao;

    public EmailService(DatabaseManager db, EmailDao emailDao, SettingsDao settingsDao, UserDao userDao) {
        this.db = db;
        this.emailDao = emailDao;
        this.settingsDao = settingsDao;
        this.userDao = userDao;
    }

    public EmailSettings getSettings() {
        return settingsDao.getEmailSettings();
    }

    public void saveSettings(EmailSettings settings) {
        settingsDao.saveEmailSettings(settings);
    }

    /** Generates the default corporate email address for a user name using the active company domain. */
    public String generateDefaultEmail(String firstName, String lastName, String username) {
        return settingsDao.getEmailSettings().generateUserEmail(firstName, lastName, username);
    }

    /**
     * Rewrites all active/inactive user email addresses to match the configured company domain
     * ({@code firstname.lastname@<domain>}) when invoked by Super Admin.
     */
    public int syncAllUserEmailsToDomain() {
        EmailSettings cfg = settingsDao.getEmailSettings();
        List<User> users = userDao.findAll();
        int updated = 0;
        for (User u : users) {
            String target = cfg.generateUserEmail(u.safeFirstName(), u.safeLastName(), u.username());
            if (!target.equalsIgnoreCase(u.email() != null ? u.email().trim() : "")) {
                userDao.updateEmail(u.id(), target);
                updated++;
            }
        }
        LOG.info("Synced {} user email address(es) to domain @{}", updated, cfg.companyDomain());
        return updated;
    }

    /**
     * Sends an email from {@code sender} to one or more comma/semicolon-separated addresses.
     * Matching internal company users receive an immediate {@code INBOX} copy in SQLite;
     * external addresses are logged as transmitted via the configured SMTP gateway.
     */
    public void sendEmail(User sender, String toRaw, String ccRaw, String subject, String body, String attachmentPath) {
        if (sender == null) {
            throw new IllegalArgumentException("Sender is required.");
        }
        if (toRaw == null || toRaw.isBlank()) {
            throw new IllegalArgumentException("At least one recipient (To) is required.");
        }
        String cleanSubject = (subject == null || subject.isBlank()) ? "(No Subject)" : subject.trim();
        String cleanBody = body == null ? "" : body.trim();
        EmailSettings cfg = settingsDao.getEmailSettings();
        String fromAddr = (sender.email() != null && !sender.email().isBlank())
                ? sender.email().trim()
                : cfg.generateUserEmail(sender.safeFirstName(), sender.safeLastName(), sender.username());

        List<String> allRecipients = parseAddresses(toRaw);
        allRecipients.addAll(parseAddresses(ccRaw));
        List<User> allUsers = userDao.findAll();

        List<User> internalTargets = new ArrayList<>();
        boolean hasExternal = false;
        for (String addr : allRecipients) {
            User match = findUserByEmail(allUsers, addr);
            if (match != null) {
                if (internalTargets.stream().noneMatch(u -> u.id() == match.id())) {
                    internalTargets.add(match);
                }
            } else {
                hasExternal = true;
            }
        }

        if (hasExternal && isInternalOnlyRole(sender.roleCode())) {
            throw new IllegalArgumentException(
                    "Operators and Forklift Drivers can only send emails to internal company team members (@"
                            + cfg.companyDomain() + ").");
        }

        String status = hasExternal
                ? (cfg.simulationMode()
                        ? "SENT (SMTP DEMO: " + cfg.smtpHost() + ":" + cfg.smtpPort() + ")"
                        : "SENT via SMTP (" + cfg.smtpHost() + ":" + cfg.smtpPort() + ")")
                : "DELIVERED (INTERNAL)";
        LocalDateTime now = LocalDateTime.now();

        final boolean externalFlag = hasExternal;
        db.runInTransaction(conn -> {
            // 1. Sender's SENT folder copy
            emailDao.insert(conn, sender.id(), EmailMessage.FOLDER_SENT,
                    fromAddr, toRaw.trim(), ccRaw != null ? ccRaw.trim() : "",
                    cleanSubject, cleanBody, attachmentPath, true, externalFlag, status, now);

            // 2. Deliver immediately to every matching internal teammate's INBOX
            for (User recipient : internalTargets) {
                emailDao.insert(conn, recipient.id(), EmailMessage.FOLDER_INBOX,
                        fromAddr, toRaw.trim(), ccRaw != null ? ccRaw.trim() : "",
                        cleanSubject, cleanBody, attachmentPath, false, false, "DELIVERED", now);
            }
        });
    }

    public void saveDraft(User owner, String toRaw, String ccRaw, String subject, String body, String attachmentPath) {
        EmailSettings cfg = settingsDao.getEmailSettings();
        String fromAddr = (owner.email() != null && !owner.email().isBlank())
                ? owner.email().trim()
                : cfg.generateUserEmail(owner.safeFirstName(), owner.safeLastName(), owner.username());
        db.runInTransaction(conn -> emailDao.insert(conn, owner.id(), EmailMessage.FOLDER_DRAFTS,
                fromAddr, toRaw != null ? toRaw.trim() : "", ccRaw != null ? ccRaw.trim() : "",
                subject != null ? subject.trim() : "", body != null ? body : "",
                attachmentPath, true, false, "DRAFT", LocalDateTime.now()));
    }

    /** Simulates an incoming external email (e.g. from a B2B customer/supplier via IMAP) into {@code recipient}'s Inbox. */
    public void simulateIncomingExternalEmail(User recipient, String fromAddress, String subject, String body) {
        if (recipient == null) {
            throw new IllegalArgumentException("Recipient is required.");
        }
        if (isInternalOnlyRole(recipient.roleCode())) {
            throw new IllegalArgumentException(
                    "Operators and Forklift Drivers cannot receive external emails.");
        }
        EmailSettings cfg = settingsDao.getEmailSettings();
        String toAddr = (recipient.email() != null && !recipient.email().isBlank())
                ? recipient.email().trim()
                : cfg.generateUserEmail(recipient.safeFirstName(), recipient.safeLastName(), recipient.username());
        String status = "RECEIVED (IMAP: " + cfg.imapHost() + ":" + cfg.imapPort() + ")";
        db.runInTransaction(conn -> emailDao.insert(conn, recipient.id(), EmailMessage.FOLDER_INBOX,
                fromAddress, toAddr, "", subject, body, null, false, true, status, LocalDateTime.now()));
    }

    /** Lightweight TCP socket connectivity test for the configured SMTP or IMAP host & port. */
    public String testGatewayConnection(String host, int port, boolean simulationMode) {
        if (simulationMode) {
            return "SIMULATION OK — Ready for offline demo (" + host + ":" + port + ")";
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2500);
            return "CONNECTED — TCP handshake succeeded (" + host + ":" + port + ")";
        } catch (Exception ex) {
            return "UNREACHABLE (" + host + ":" + port + "): " + ex.getMessage();
        }
    }

    public List<EmailMessage> listFolder(long userId, String folder) {
        List<EmailMessage> raw = emailDao.findByOwnerAndFolder(userId, folder);
        boolean internalOnly = userDao.findById(userId)
                .map(u -> isInternalOnlyRole(u.roleCode()))
                .orElse(false);
        if (!internalOnly) {
            return raw;
        }
        return raw.stream().filter(m -> !m.external()).toList();
    }

    public static boolean isInternalOnlyRole(RoleCode role) {
        return role == RoleCode.OPERATOR || role == RoleCode.FORKLIFT;
    }

    public int countUnreadInbox(long userId) {
        return emailDao.countUnreadInbox(userId);
    }

    public void markRead(long emailId, boolean read) {
        emailDao.markRead(emailId, read);
    }

    public void moveToTrashOrDelete(EmailMessage msg) {
        if (EmailMessage.FOLDER_TRASH.equalsIgnoreCase(msg.folder())) {
            emailDao.deleteById(msg.id());
        } else {
            emailDao.moveToFolder(msg.id(), EmailMessage.FOLDER_TRASH);
        }
    }

    private static List<String> parseAddresses(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String token : raw.split("[,;]")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static User findUserByEmail(List<User> users, String addr) {
        String lower = addr.toLowerCase(Locale.ROOT);
        for (User u : users) {
            if (u.email() != null && u.email().trim().toLowerCase(Locale.ROOT).equals(lower)) {
                return u;
            }
        }
        return null;
    }
}
