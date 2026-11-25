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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards e-mail attachment storage against housekeeping data loss.
 * <p>
 * An exported {@code exports/mail-*} file is the only copy of a sent attachment: the mail rows point
 * at it by absolute path. The janitor must therefore never delete a folder a mail still references,
 * however old it is, and must only reclaim folders that are both old and unreferenced.
 */
class MailExportJanitorTest {

    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    @TempDir
    Path tempDir;

    private Path batch(Path exports, String name, Instant modified) throws IOException {
        Path dir = Files.createDirectories(exports.resolve(name));
        Files.writeString(dir.resolve("invoice.pdf"), "%PDF-1.4 test");
        Files.setLastModifiedTime(dir, FileTime.from(modified));
        return dir;
    }

    private static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    @Test
    void removesOnlyFoldersThatAreBothOldAndUnreferenced() throws IOException {
        Path exports = Files.createDirectories(tempDir.resolve("exports"));
        Path oldOrphan = batch(exports, "mail-111", daysAgo(30));
        Path oldReferenced = batch(exports, "mail-222", daysAgo(30));
        Path freshOrphan = batch(exports, "mail-333", daysAgo(1));
        Path notAMailBatch = batch(exports, "reports-444", daysAgo(30));

        int removed = MailExportJanitor.pruneUnreferenced(exports,
                Set.of(oldReferenced.resolve("invoice.pdf").toString()), RETENTION, NOW);

        assertEquals(1, removed);
        assertFalse(Files.exists(oldOrphan), "An old folder nothing references should be reclaimed, contents included");
        assertTrue(Files.exists(oldReferenced.resolve("invoice.pdf")),
                "A folder a mail row still references must survive however old it is");
        assertTrue(Files.exists(freshOrphan), "A recent folder may belong to a message still being composed");
        assertTrue(Files.exists(notAMailBatch), "Folders that are not mail staging folders are never touched");
    }

    @Test
    void referenceSurvivesMovingTheApplicationDirectory() throws IOException {
        Path exports = Files.createDirectories(tempDir.resolve("moved-app/exports"));
        Path batchDir = batch(exports, "mail-555", daysAgo(60));

        // The stored absolute path still carries the previous install location's prefix.
        String storedBeforeMove = "/Users/someone/old-location/exports/mail-555/invoice.pdf";

        int removed = MailExportJanitor.pruneUnreferenced(exports, Set.of(storedBeforeMove), RETENTION, NOW);

        assertEquals(0, removed);
        assertTrue(Files.exists(batchDir), "Matching by folder name keeps relocated attachments safe");
    }

    @Test
    void blankAndUnparsableReferencesProtectNothingAndDoNotThrow() throws IOException {
        Path exports = Files.createDirectories(tempDir.resolve("exports"));
        Path orphan = batch(exports, "mail-666", daysAgo(30));

        int removed = MailExportJanitor.pruneUnreferenced(exports,
                Set.of("", "   ", "bad\u0000path"), RETENTION, NOW);

        assertEquals(1, removed);
        assertFalse(Files.exists(orphan));
    }

    @Test
    void missingExportsDirectoryIsNotAnError() {
        assertEquals(0, MailExportJanitor.pruneUnreferenced(
                tempDir.resolve("does-not-exist"), Set.of(), RETENTION, NOW));
    }

    /**
     * Messages sent before the attachment vault existed still point at an {@code exports/mail-*} file by
     * absolute path, and that file is their only copy. Housekeeping must keep protecting those rows for
     * as long as they exist, however old the file is.
     */
    @Test
    void legacyAttachmentSurvivesHousekeepingUntilItsMailIsDeleted() throws IOException {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("janitor-test.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        AppContext ctx = new AppContext(db, prefs, i18n);

        Path exports = Files.createDirectories(tempDir.resolve("exports"));
        Path attachmentDir = batch(exports, "mail-777", daysAgo(90));
        Path attachment = attachmentDir.resolve("invoice.pdf").toAbsolutePath();

        User manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        // A pre-vault row, written the way the old service stored attachments: the absolute path.
        db.runInTransaction(conn -> ctx.getEmailDao().insert(conn, manager.id(), EmailMessage.FOLDER_SENT,
                "manager@example.com", "operator@example.com", "", "Legacy delivery note", "Attached.",
                attachment.toString(), true, false, "DELIVERED (INTERNAL)", BusinessClock.now()));

        assertTrue(ctx.getEmailDao().findAttachmentPaths().contains(attachment.toString()),
                "The stored path must be discoverable so housekeeping can see it is in use");

        int removedWhileReferenced = MailExportJanitor.pruneUnreferenced(
                exports, ctx.getEmailDao().findAttachmentPaths(), RETENTION, NOW);

        assertEquals(0, removedWhileReferenced);
        assertTrue(Files.exists(attachment), "A 90-day-old attachment on a live message must not be deleted");

        // Once every message pointing at the file is gone, the folder becomes reclaimable.
        for (EmailMessage m : ctx.getEmailDao().findByOwnerAndFolder(manager.id(), EmailMessage.FOLDER_SENT)) {
            ctx.getEmailDao().deleteById(m.id());
        }

        int removedAfterDelete = MailExportJanitor.pruneUnreferenced(
                exports, ctx.getEmailDao().findAttachmentPaths(), RETENTION, NOW);

        assertEquals(1, removedAfterDelete);
        assertFalse(Files.exists(attachmentDir));
    }
}
