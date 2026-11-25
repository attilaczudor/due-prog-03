package hu.due.rewear.service;

import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the mail attachment vault in isolation: where files land, what is refused, what can be
 * resolved for opening, and what housekeeping may remove.
 */
class MailAttachmentVaultTest {

    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final byte[] PDF_BYTES = "%PDF-1.4\n% test document\n".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path tempDir;

    private MailAttachmentVault vault() {
        return vault(MailAttachmentVault.DEFAULT_MAX_BYTES);
    }

    private MailAttachmentVault vault(long maxBytes) {
        return new MailAttachmentVault(tempDir.resolve("vault"), maxBytes, () -> LocalDate.of(2026, 11, 25));
    }

    private Path source(String name, byte[] content) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("source"));
        Path file = dir.resolve(name);
        Files.write(file, content);
        return file;
    }

    private long storedFileCount() throws IOException {
        Path root = tempDir.resolve("vault");
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void storesInADatedFolderAndOutlivesTheSourceFile() throws IOException {
        MailAttachmentVault vault = vault();
        Path original = source("Delivery note.pdf", PDF_BYTES);

        String stored = vault.store(original);

        assertEquals("2026/11/Delivery-note.pdf", stored, "Stored paths are portable, relative and dated");
        Files.delete(original);
        Optional<Path> resolved = vault.resolve(stored);
        assertTrue(resolved.isPresent(), "The vault copy must not depend on the original file");
        assertArrayEquals(PDF_BYTES, Files.readAllBytes(resolved.get()));
        assertTrue(vault.contains(stored));
    }

    @Test
    void sameNameTwiceKeepsBothFiles() throws IOException {
        MailAttachmentVault vault = vault();
        String first = vault.store(source("invoice.pdf", PDF_BYTES));
        String second = vault.store(source("invoice.pdf", PDF_BYTES));

        assertEquals("2026/11/invoice.pdf", first);
        assertEquals("2026/11/invoice-2.pdf", second);
        assertEquals(2, storedFileCount());
    }

    @Test
    void foldsAccentsAndStripsUnsafeCharactersFromTheName() throws IOException {
        MailAttachmentVault vault = vault();

        assertEquals("2026/11/Szamla-vegleges-2026.pdf",
                vault.store(source("Számla (végleges) 2026.pdf", PDF_BYTES)));
        assertEquals("2026/11/attachment.pdf", vault.store(source("###.pdf", PDF_BYTES)));
    }

    @Test
    void refusesUnsupportedMissingEmptyAndOversizedFiles() throws IOException {
        MailAttachmentVault vault = vault(10);

        for (String dangerous : new String[]{"run.sh", "tool.command", "setup.exe", "Calc.app", "noextension"}) {
            Path file = source(dangerous, "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
            assertThrows(IllegalArgumentException.class, () -> vault.store(file), dangerous);
        }
        assertThrows(IllegalArgumentException.class, () -> vault.store(tempDir.resolve("source/missing.pdf")));
        assertThrows(IllegalArgumentException.class, () -> vault.store(source("empty.pdf", new byte[0])));
        assertThrows(IllegalArgumentException.class,
                () -> vault.store(source("big.pdf", "%PDF-1.4 and a lot more than ten bytes".getBytes(StandardCharsets.US_ASCII))));
        assertEquals(0, storedFileCount(), "Every refused file must leave the vault untouched");
    }

    @Test
    void refusesContentThatDoesNotMatchTheExtension() throws IOException {
        MailAttachmentVault vault = vault();
        Path disguised = source("report.pdf", "#!/bin/sh\nrm -rf ~\n".getBytes(StandardCharsets.US_ASCII));

        assertThrows(IllegalArgumentException.class, () -> vault.store(disguised));
        assertEquals(0, storedFileCount(), "A rejected copy must not leave a file or a staging remnant behind");
    }

    @Test
    void resolveNeverLeavesTheVault() throws IOException {
        MailAttachmentVault vault = vault();
        String stored = vault.store(source("ok.pdf", PDF_BYTES));
        Path outside = Files.write(tempDir.resolve("outside.pdf"), PDF_BYTES);

        assertTrue(vault.resolve(stored).isPresent());
        assertTrue(vault.resolve("../outside.pdf").isEmpty(), "A parent segment must not escape the vault");
        assertTrue(vault.resolve("2026/../../outside.pdf").isEmpty(), "Non-normalised paths are refused");
        assertTrue(vault.resolve("").isEmpty());
        assertTrue(vault.resolve(null).isEmpty());

        Path link = vault.getRoot().resolve("2026/11/link.pdf");
        Files.createSymbolicLink(link, outside);
        assertTrue(vault.resolve("2026/11/link.pdf").isEmpty(), "A symlink leading out of the vault is refused");
    }

    @Test
    void legacyAbsolutePathsOpenOnlyWhenPresentAndAnAllowedType() throws IOException {
        MailAttachmentVault vault = vault();
        Path legacyPdf = source("old-export.pdf", PDF_BYTES);
        Path legacyScript = source("launch.command", "#!/bin/sh\n".getBytes(StandardCharsets.US_ASCII));

        assertEquals(Optional.of(legacyPdf), vault.resolve(legacyPdf.toString()),
                "Messages sent before the vault existed must keep working");
        assertTrue(vault.resolve(legacyScript.toString()).isEmpty(), "A message must never launch a script");
        assertTrue(vault.resolve(tempDir.resolve("source/gone.pdf").toString()).isEmpty());
        assertFalse(vault.contains(legacyPdf.toString()), "A legacy path is usable but is not inside the vault");
    }

    @Test
    void pruneRemovesOnlyOldUnreferencedFiles() throws IOException {
        MailAttachmentVault vault = vault();
        String referenced = vault.store(source("referenced.pdf", PDF_BYTES));
        String orphan = vault.store(source("orphan.pdf", PDF_BYTES));
        String recent = vault.store(source("recent.pdf", PDF_BYTES));

        FileTime old = FileTime.from(NOW.minus(Duration.ofDays(3)));
        Files.setLastModifiedTime(vault.resolve(referenced).orElseThrow(), old);
        Files.setLastModifiedTime(vault.resolve(orphan).orElseThrow(), old);
        Files.setLastModifiedTime(vault.resolve(recent).orElseThrow(), FileTime.from(NOW.minus(Duration.ofHours(1))));

        int removed = vault.pruneUnreferenced(Set.of(referenced), Duration.ofDays(1), NOW);

        assertEquals(1, removed);
        assertTrue(vault.contains(referenced), "A referenced file is never pruned, however old");
        assertFalse(vault.contains(orphan), "An old file no mail row references is reclaimed");
        assertTrue(vault.contains(recent), "A fresh file may belong to a message still being sent");
    }

    /**
     * At 23:30 UTC on 30 November it is already 1 December in Budapest. With the machine in Los Angeles a
     * system-clock call would still say 30 November, so this fails if the vault ever stops using the
     * business clock for its dated folders.
     */
    @Test
    void datedFolderFollowsTheBusinessDayAtAMonthBoundary() throws IOException {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            BusinessClock.useForTesting(Clock.fixed(Instant.parse("2026-11-30T23:30:00Z"), BusinessClock.ZONE));

            String stored = new MailAttachmentVault(tempDir.resolve("business-day-vault"))
                    .store(source("boundary.pdf", PDF_BYTES));

            assertEquals("2026/12/boundary.pdf", stored);
        } finally {
            BusinessClock.resetForTesting();
            TimeZone.setDefault(original);
        }
    }
}
