package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.DemoDataSeeder;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.dao.DictionaryDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full backups carry the database together with the document folders its rows point at, and a restore
 * either brings everything back or changes nothing.
 */
class BackupBundleTest {

    @TempDir
    Path tempDir;

    private Path dataDir;
    private DatabaseManager db;
    private BackupService backup;
    private DictionaryDao dictionary;

    private void setUp() throws IOException {
        dataDir = Files.createDirectories(tempDir.resolve("data"));
        db = new DatabaseManager(dataDir.resolve("live.db"));
        new SchemaInitializer(db).initialize();
        new DemoDataSeeder(db).seedIfEmpty();
        backup = new BackupService(db);
        dictionary = new DictionaryDao(db);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = dataDir.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private boolean hasGroup(String code) {
        return dictionary.findTargetGroups(false).stream().anyMatch(g -> code.equals(g.code()));
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Reads every entry except the manifest, in order. */
    private static Map<String, byte[]> entries(Path zip) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().equals(BackupBundle.MANIFEST)) {
                    result.put(e.getName(), zf.getInputStream(e).readAllBytes());
                }
            }
        }
        return result;
    }

    /** Builds a bundle by hand: the manifest honestly describes {@code declared}, the zip holds {@code actual}. */
    private Path handBuilt(String name, Map<String, byte[]> declared, Map<String, byte[]> actual) throws Exception {
        List<String> lines = new ArrayList<>(List.of("rewear-backup-bundle 1", "created=test"));
        for (Map.Entry<String, byte[]> e : declared.entrySet()) {
            lines.add(sha256(e.getValue()) + "\t" + e.getValue().length + "\t" + e.getKey());
        }
        return zipWithManifest(name, lines, actual);
    }

    private Path zipWithManifest(String name, List<String> manifestLines, Map<String, byte[]> actual) throws IOException {
        Path zip = tempDir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> e : actual.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(BackupBundle.MANIFEST));
            out.write(String.join("\n", manifestLines).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    private Map<String, byte[]> validBundleContent() throws IOException {
        Path real = tempDir.resolve("real.zip");
        backup.backupBundle(real);
        return entries(real);
    }

    private long filesUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private void assertNoRestoreLeftovers() throws IOException {
        try (Stream<Path> files = Files.list(dataDir)) {
            assertFalse(files.map(f -> f.getFileName().toString()).anyMatch(n -> n.startsWith(".rewear-")),
                    "Staging folders must be cleaned up");
        }
    }

    @Test
    void roundTripBringsBackDatabaseAndDocumentsAndKeepsTheOldOnesAside() throws Exception {
        setUp();
        write("invoices/2026/11/a.pdf", "invoice A");
        write("receipts/2026/11/r.png", "receipt R");
        write("mail-attachments/2026/11/m.pdf", "mail M");
        Path zip = tempDir.resolve("full.zip");

        BackupService.BundleBackup made = backup.backupBundle(zip);
        assertEquals(3, made.documentCount());
        assertTrue(BackupService.isBundle(zip));
        assertFalse(BackupService.isBundle(dataDir.resolve("live.db")), "A bare database is not a bundle");

        // Life goes on: a document is lost, another appears, the database changes.
        Files.delete(dataDir.resolve("invoices/2026/11/a.pdf"));
        write("invoices/2026/11/newer.pdf", "created after the backup");
        dictionary.insertTargetGroup("TMP", "Temp Group", "Ideiglenes");
        assertTrue(hasGroup("TMP"));

        BackupService.BundleRestore restored = backup.restoreBundle(zip);

        assertEquals(3, restored.documentCount());
        assertEquals("invoice A", Files.readString(dataDir.resolve("invoices/2026/11/a.pdf")));
        assertEquals("receipt R", Files.readString(dataDir.resolve("receipts/2026/11/r.png")));
        assertEquals("mail M", Files.readString(dataDir.resolve("mail-attachments/2026/11/m.pdf")));
        assertFalse(Files.exists(dataDir.resolve("invoices/2026/11/newer.pdf")), "State returns to the backup moment");
        assertFalse(hasGroup("TMP"));
        assertEquals("created after the backup",
                Files.readString(restored.documentsSafetyFolder().resolve("invoices/2026/11/newer.pdf")),
                "What was replaced is kept, not deleted");
        assertTrue(Files.exists(restored.databaseSafetyCopy()));
        assertNoRestoreLeftovers();
    }

    @Test
    void backupSkipsScratchFilesAndSymlinksAndRefusesBadDestinations() throws Exception {
        setUp();
        write("invoices/2026/11/real.pdf", "real");
        write("invoices/2026/11/.rewear-half-written.tmp", "scratch");
        Path outside = Files.writeString(tempDir.resolve("secret.txt"), "not part of the app");
        Files.createSymbolicLink(dataDir.resolve("invoices/link.txt"), outside);
        Path zip = tempDir.resolve("full.zip");

        backup.backupBundle(zip);

        List<String> names = new ArrayList<>(entries(zip).keySet());
        assertTrue(names.contains("files/invoices/2026/11/real.pdf"));
        assertEquals(2, names.size(), "Only the database and the real document: " + names);
        assertThrows(IllegalArgumentException.class, () -> backup.backupBundle(dataDir.resolve("invoices/inside.zip")));
        assertThrows(IllegalArgumentException.class, () -> backup.backupBundle(dataDir.resolve("live.db")));
    }

    @Test
    void aTamperedFileIsRejectedAndNothingChanges() throws Exception {
        setUp();
        write("invoices/2026/11/a.pdf", "genuine invoice");
        Map<String, byte[]> declared = validBundleContent();
        Map<String, byte[]> actual = new LinkedHashMap<>(declared);
        actual.put("files/invoices/2026/11/a.pdf", "forged invoice!".getBytes(StandardCharsets.UTF_8));
        Path zip = handBuilt("tampered.zip", declared, actual);

        dictionary.insertTargetGroup("TMP", "Temp Group", "Ideiglenes");
        IOException ex = assertThrows(IOException.class, () -> backup.restoreBundle(zip));

        assertTrue(ex.getMessage().contains("mismatch"), ex.getMessage());
        assertEquals("genuine invoice", Files.readString(dataDir.resolve("invoices/2026/11/a.pdf")));
        assertTrue(hasGroup("TMP"), "The live database must be untouched");
        assertNoRestoreLeftovers();
    }

    @Test
    void entriesThatTryToEscapeTheDataFolderAreRefused() throws Exception {
        setUp();
        Map<String, byte[]> good = validBundleContent();
        int n = 0;
        for (String evil : new String[]{
                "files/invoices/../../evil.txt", "../evil.txt", "/etc/evil.txt", "files/other/evil.txt",
                "files/invoices//double.txt", "files\\invoices\\win.txt"}) {
            Map<String, byte[]> content = new LinkedHashMap<>(good);
            content.put(evil, "payload".getBytes(StandardCharsets.UTF_8));
            Path zip = handBuilt("evil-" + (n++) + ".zip", content, content);

            assertThrows(IOException.class, () -> backup.restoreBundle(zip), evil);
        }
        assertFalse(Files.exists(tempDir.resolve("evil.txt")));
        assertFalse(Files.exists(dataDir.resolve("evil.txt")));
        assertNoRestoreLeftovers();
    }

    @Test
    void missingUnlistedAndForeignArchivesAreRefused() throws Exception {
        setUp();
        write("receipts/2026/11/r.png", "receipt");
        Map<String, byte[]> good = validBundleContent();

        Map<String, byte[]> missing = new LinkedHashMap<>(good);
        missing.remove("files/receipts/2026/11/r.png");
        assertThrows(IOException.class, () -> backup.restoreBundle(handBuilt("missing.zip", good, missing)));

        Map<String, byte[]> extra = new LinkedHashMap<>(good);
        extra.put("files/receipts/2026/11/sneaky.png", new byte[]{1});
        assertThrows(IOException.class, () -> backup.restoreBundle(handBuilt("extra.zip", good, extra)));

        Map<String, byte[]> noDatabase = new LinkedHashMap<>(good);
        noDatabase.remove(BackupBundle.DATABASE);
        assertThrows(IOException.class, () -> backup.restoreBundle(handBuilt("nodb.zip", noDatabase, noDatabase)));

        Path notABundle = zipWithManifest("plain.zip", List.of("hello"),
                Map.of("readme.txt", "hello".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> backup.restoreBundle(notABundle));
        assertThrows(IOException.class, () -> backup.restoreBundle(dataDir.resolve("live.db")),
                "A bare database is not a bundle");
        assertEquals("receipt", Files.readString(dataDir.resolve("receipts/2026/11/r.png")));
        assertNoRestoreLeftovers();
    }

    @Test
    void aDamagedDatabaseInsideAValidBundleIsRejectedBeforeAnyDocumentMoves() throws Exception {
        setUp();
        write("invoices/2026/11/a.pdf", "live invoice");
        Map<String, byte[]> content = new LinkedHashMap<>(validBundleContent());
        content.put(BackupBundle.DATABASE, "this is not sqlite, but the checksum is honest".getBytes(StandardCharsets.UTF_8));
        content.put("files/invoices/2026/11/a.pdf", "invoice from a bad bundle".getBytes(StandardCharsets.UTF_8));
        Path zip = handBuilt("bad-db.zip", content, content);

        assertThrows(IOException.class, () -> backup.restoreBundle(zip));

        assertEquals("live invoice", Files.readString(dataDir.resolve("invoices/2026/11/a.pdf")),
                "The checksums were fine, so the database check is what protects the documents");
        assertNoRestoreLeftovers();
    }

    @Test
    void sizeLiesInTheManifestAreCaughtWhileUnpacking() throws Exception {
        setUp();
        Map<String, byte[]> good = validBundleContent();
        Map<String, byte[]> actual = new LinkedHashMap<>(good);
        actual.put("files/invoices/big.bin", new byte[2048]);
        List<String> lines = new ArrayList<>(List.of("rewear-backup-bundle 1"));
        for (Map.Entry<String, byte[]> e : good.entrySet()) {
            lines.add(sha256(e.getValue()) + "\t" + e.getValue().length + "\t" + e.getKey());
        }
        // The manifest claims 10 bytes for an entry that is really 2048.
        lines.add(sha256(new byte[10]) + "\t10\tfiles/invoices/big.bin");
        Path zip = zipWithManifest("liar.zip", lines, actual);

        IOException ex = assertThrows(IOException.class, () -> backup.restoreBundle(zip));

        assertTrue(ex.getMessage().contains("size mismatch"), ex.getMessage());
        assertEquals(0, filesUnder(dataDir.resolve("invoices")));
        assertNoRestoreLeftovers();
    }

    @Test
    void bundleWithoutDocumentsEmptiesTheFoldersAndKeepsTheOldFilesAside() throws Exception {
        setUp();
        Path zip = tempDir.resolve("empty-docs.zip");
        backup.backupBundle(zip);                       // taken while no documents exist
        write("mail-attachments/2026/11/m.pdf", "added later");

        BackupService.BundleRestore restored = backup.restoreBundle(zip);

        assertEquals(0, restored.documentCount());
        assertEquals(0, filesUnder(dataDir.resolve("mail-attachments")));
        assertTrue(Files.isDirectory(dataDir.resolve("mail-attachments")), "The folder itself must exist for the next send");
        assertArrayEquals("added later".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(restored.documentsSafetyFolder().resolve("mail-attachments/2026/11/m.pdf")));
    }

    /**
     * The database is swapped last. If that step fails after the document folders already changed,
     * the folders must go back exactly as they were. The failure is forced by pinning the clock and
     * occupying the safety copy's file name with a non-empty directory.
     */
    @Test
    void aFailureAfterTheFolderSwapRestoresThePreviousDocuments() throws Exception {
        setUp();
        write("invoices/2026/11/a.pdf", "from the backup");
        Path zip = tempDir.resolve("full.zip");
        backup.backupBundle(zip);
        write("invoices/2026/11/a.pdf", "edited after the backup");
        write("receipts/2026/11/live.png", "only in the live data");
        dictionary.insertTargetGroup("TMP", "Temp Group", "Ideiglenes");

        java.time.Instant pinned = java.time.Instant.parse("2026-11-25T10:00:00Z");
        hu.due.rewear.util.BusinessClock.useForTesting(java.time.Clock.fixed(pinned, hu.due.rewear.util.BusinessClock.ZONE));
        try {
            Path blocker = Files.createDirectories(dataDir.resolve("pre-restore-20261125-110000.db.bak"));
            Files.writeString(blocker.resolve("occupied"), "x");

            assertThrows(IOException.class, () -> backup.restoreBundle(zip));
        } finally {
            hu.due.rewear.util.BusinessClock.resetForTesting();
        }

        assertEquals("edited after the backup", Files.readString(dataDir.resolve("invoices/2026/11/a.pdf")));
        assertEquals("only in the live data", Files.readString(dataDir.resolve("receipts/2026/11/live.png")));
        assertTrue(hasGroup("TMP"), "The live database must be untouched");
        assertNoRestoreLeftovers();
    }
}
