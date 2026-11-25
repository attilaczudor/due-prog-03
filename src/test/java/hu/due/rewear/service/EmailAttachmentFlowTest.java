package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.EmailMessage;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises attachments through the real {@link EmailService}, database and vault together.
 * <p>
 * Covers the guarantees the vault exists to give: an attachment survives its export being deleted,
 * the Sent copy and Inbox copies share one file that lives until the last message is gone, and a
 * refused send leaves nothing behind.
 */
class EmailAttachmentFlowTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4\n% delivery note\n".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private User manager;
    private User operator;

    private void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("flow-test.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(db, prefs, i18n);
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        operator = ctx.getUserDao().findByUsername("operator").orElseThrow();
        assertTrue(operator.email() != null && !operator.email().isBlank(),
                "The demo operator needs an internal address for these tests");
    }

    /** Mirrors what the document preview does: an exported file in its own {@code exports/mail-*} folder. */
    private Path exportedPdf(String folder, String name) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("exports").resolve(folder));
        return Files.write(dir.resolve(name), PDF_BYTES);
    }

    private long vaultFileCount() throws IOException {
        try (Stream<Path> walk = Files.walk(ctx.getMailAttachmentVault().getRoot())) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    /** Messages in a folder that carry an attachment; the demo data seeds plain mail that is not relevant here. */
    private List<EmailMessage> withAttachment(User owner, String folder) {
        return ctx.getEmailDao().findByOwnerAndFolder(owner.id(), folder).stream()
                .filter(EmailMessage::hasAttachment).toList();
    }

    private EmailMessage onlyAttached(User owner, String folder) {
        List<EmailMessage> messages = withAttachment(owner, folder);
        assertEquals(1, messages.size(), owner.username() + " " + folder);
        return messages.get(0);
    }

    @Test
    void sentAttachmentIsCopiedIntoTheVaultAndSharedByBothMailboxes() throws IOException {
        setUp();
        Path export = exportedPdf("mail-1", "2026-11-24-Duna-Vintage-Kft-125.5.pdf");

        ctx.getEmailService().sendEmail(manager, operator.email(), "", "Delivery note", "Attached.", export.toString());

        EmailMessage sent = onlyAttached(manager, EmailMessage.FOLDER_SENT);
        EmailMessage received = onlyAttached(operator, EmailMessage.FOLDER_INBOX);
        assertFalse(Path.of(sent.attachmentPath()).isAbsolute(), "The row must hold a portable relative path");
        assertEquals(sent.attachmentPath(), received.attachmentPath(), "Both mailboxes share one stored file");

        // The original export can vanish without affecting the message.
        Files.delete(export);
        Files.delete(export.getParent());
        Path stored = ctx.getEmailService().resolveAttachment(sent).orElseThrow();
        assertTrue(stored.startsWith(ctx.getMailAttachmentVault().getRoot()));
        assertArrayEquals(PDF_BYTES, Files.readAllBytes(stored));
        assertEquals(stored, ctx.getEmailService().resolveAttachment(received).orElseThrow());
        assertEquals(1, vaultFileCount());
    }

    @Test
    void exportFolderBecomesScratchOnceTheMailIsSent() throws IOException {
        setUp();
        Path export = exportedPdf("mail-2", "note.pdf");
        Path exports = tempDir.resolve("exports");
        Files.setLastModifiedTime(export.getParent(), FileTime.from(Instant.now().minus(Duration.ofDays(30))));

        ctx.getEmailService().sendEmail(manager, operator.email(), "", "Delivery note", "Attached.", export.toString());

        int removed = MailExportJanitor.pruneUnreferenced(exports, ctx.getEmailDao().findAttachmentPaths(),
                Duration.ofDays(7), Instant.now());

        assertEquals(1, removed, "No mail row points into exports any more, so the folder is reclaimable");
        assertTrue(ctx.getEmailService().resolveAttachment(onlyAttached(manager, EmailMessage.FOLDER_SENT)).isPresent(),
                "The attachment itself must be unaffected");
    }

    @Test
    void attachmentLivesUntilTheLastMessageReferencingItIsDeleted() throws IOException {
        setUp();
        Path export = exportedPdf("mail-3", "note.pdf");
        ctx.getEmailService().sendEmail(manager, operator.email(), "", "Delivery note", "Attached.", export.toString());
        assertEquals(1, vaultFileCount());

        // Sender deletes their copy: move to trash, then delete from trash.
        ctx.getEmailService().moveToTrashOrDelete(onlyAttached(manager, EmailMessage.FOLDER_SENT));
        ctx.getEmailService().moveToTrashOrDelete(onlyAttached(manager, EmailMessage.FOLDER_TRASH));
        assertEquals(1, vaultFileCount(), "The recipient still has the message, so the file must stay");

        // Recipient deletes theirs.
        ctx.getEmailService().moveToTrashOrDelete(onlyAttached(operator, EmailMessage.FOLDER_INBOX));
        ctx.getEmailService().moveToTrashOrDelete(onlyAttached(operator, EmailMessage.FOLDER_TRASH));
        assertEquals(0, vaultFileCount(), "With no message left, the stored file is released");
    }

    @Test
    void aRefusedSendLeavesNoFileAndNoMessageBehind() throws IOException {
        setUp();
        Path export = exportedPdf("mail-4", "note.pdf");
        int sentBefore = ctx.getEmailDao().findByOwnerAndFolder(manager.id(), EmailMessage.FOLDER_SENT).size();

        // Operators may only write to internal addresses, so this is refused before any copy is made.
        assertThrows(IllegalArgumentException.class, () -> ctx.getEmailService()
                .sendEmail(operator, "partner@example.com", "", "Hello", "Body", export.toString()));
        assertEquals(0, vaultFileCount(), "A refused send must not copy the attachment");

        // An attachment type that is not allowed is refused, and the message is not sent at all.
        Path script = Files.write(export.getParent().resolve("run.command"), "#!/bin/sh\n".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IllegalArgumentException.class, () -> ctx.getEmailService()
                .sendEmail(manager, operator.email(), "", "Tool", "Body", script.toString()));
        assertEquals(0, vaultFileCount());
        assertEquals(sentBefore, ctx.getEmailDao().findByOwnerAndFolder(manager.id(), EmailMessage.FOLDER_SENT).size(),
                "No Sent copy may exist for a message that was not sent");
    }

    @Test
    void draftAttachmentIsStoredInTheVaultToo() throws IOException {
        setUp();
        Path export = exportedPdf("mail-5", "draft-note.pdf");

        ctx.getEmailService().saveDraft(manager, operator.email(), "", "Draft", "Body", export.toString());
        Files.delete(export);

        EmailMessage draft = onlyAttached(manager, EmailMessage.FOLDER_DRAFTS);
        assertFalse(Path.of(draft.attachmentPath()).isAbsolute());
        assertTrue(ctx.getEmailService().resolveAttachment(draft).isPresent(),
                "A draft must not depend on a file the composer happened to point at");
    }

    @Test
    void messagesSentBeforeTheVaultStillOpenButNeverLaunchAScript() throws IOException {
        setUp();
        Path legacyPdf = exportedPdf("mail-6", "legacy.pdf");
        Path legacyScript = Files.write(legacyPdf.getParent().resolve("legacy.command"),
                "#!/bin/sh\n".getBytes(StandardCharsets.US_ASCII));

        DatabaseManager db = ctx.getDatabaseManager();
        for (Path legacy : new Path[]{legacyPdf, legacyScript}) {
            db.runInTransaction(conn -> ctx.getEmailDao().insert(conn, manager.id(), EmailMessage.FOLDER_DRAFTS,
                    "manager@example.com", "operator@example.com", "", "Legacy " + legacy.getFileName(), "Body",
                    legacy.toString(), true, false, "DRAFT", BusinessClock.now()));
        }

        List<EmailMessage> rows = withAttachment(manager, EmailMessage.FOLDER_DRAFTS);
        EmailMessage pdfRow = rows.stream().filter(m -> m.attachmentPath().endsWith("legacy.pdf")).findFirst().orElseThrow();
        EmailMessage scriptRow = rows.stream().filter(m -> m.attachmentPath().endsWith("legacy.command")).findFirst().orElseThrow();

        assertTrue(ctx.getEmailService().resolveAttachment(pdfRow).isPresent(), "Old messages must keep working");
        assertTrue(ctx.getEmailService().resolveAttachment(scriptRow).isEmpty(),
                "A message must never make the viewer open a script");
    }
}
