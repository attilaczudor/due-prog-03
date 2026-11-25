package hu.due.rewear.model;

import java.time.LocalDateTime;

/**
 * Persisted email message belonging to a specific user's mailbox folder
 * ({@code INBOX}, {@code SENT}, {@code DRAFTS}, or {@code TRASH}).
 */
public record EmailMessage(
        long id,
        long ownerUserId,
        String folder,
        String fromAddress,
        String toAddresses,
        String ccAddresses,
        String subject,
        String body,
        String attachmentPath,
        boolean read,
        boolean external,
        String deliveryStatus,
        LocalDateTime createdAt
) {
    public static final String FOLDER_INBOX = "INBOX";
    public static final String FOLDER_SENT = "SENT";
    public static final String FOLDER_DRAFTS = "DRAFTS";
    public static final String FOLDER_TRASH = "TRASH";

    public boolean hasAttachment() {
        return attachmentPath != null && !attachmentPath.isBlank();
    }

    public EmailMessage withRead(boolean value) {
        return new EmailMessage(id, ownerUserId, folder, fromAddress, toAddresses, ccAddresses, subject, body,
                attachmentPath, value, external, deliveryStatus, createdAt);
    }
}
