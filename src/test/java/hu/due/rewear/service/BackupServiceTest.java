package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.DemoDataSeeder;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.dao.DictionaryDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void backsUpAndRestoresDatabaseAndRejectsCorruptFiles() throws IOException {
        Path dbFile = tempDir.resolve("live.db");
        DatabaseManager db = new DatabaseManager(dbFile);
        new SchemaInitializer(db).initialize();
        new DemoDataSeeder(db).seedIfEmpty();

        DictionaryDao dictDao = new DictionaryDao(db);
        BackupService backupService = new BackupService(db);

        // 1. Take backup before mutation
        Path snapshot = tempDir.resolve("snapshot.db");
        backupService.backupDatabase(snapshot);
        backupService.backupDatabase(snapshot);
        assertThrows(IllegalArgumentException.class, () -> backupService.backupDatabase(tempDir.resolve("./live.db")));
        assertTrue(Files.exists(snapshot));
        assertTrue(Files.size(snapshot) > 4096);

        // 2. Mutate live DB by adding a new target group
        dictDao.insertTargetGroup("TMP", "Temp Group", "Ideiglenes");
        assertTrue(dictDao.findTargetGroups(false).stream().anyMatch(g -> "TMP".equals(g.code())));

        // 3. Restore snapshot -> TMP disappears and safety backup exists
        Path safetyCopy = backupService.restoreDatabase(snapshot);
        assertTrue(Files.exists(safetyCopy));
        assertFalse(dictDao.findTargetGroups(false).stream().anyMatch(g -> "TMP".equals(g.code())));

        // 4. Reject non-SQLite file
        Path invalidFile = tempDir.resolve("not-a-db.txt");
        Files.writeString(invalidFile, "just plain text, not sqlite", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> backupService.restoreDatabase(invalidFile));
    }

    private static boolean integrityOk(Path file) {
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file, readOnly.toProperties());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
            return rs.next() && "ok".equalsIgnoreCase(rs.getString(1)) && !rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean leftoverRestoreFiles(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(f -> f.getFileName().toString())
                    .anyMatch(n -> n.startsWith(".rewear-restore-") || n.startsWith(".rewear-backup-"));
        }
    }

    private DatabaseManager seededDatabase(String name) {
        DatabaseManager db = new DatabaseManager(tempDir.resolve(name));
        new SchemaInitializer(db).initialize();
        new DemoDataSeeder(db).seedIfEmpty();
        return db;
    }

    /**
     * Header and table names live on the first pages, so damage anywhere else used to slip through
     * validation and replace a healthy live database. Only a full integrity scan catches it.
     */
    @Test
    void rejectsBackupDamagedMidFileAndLeavesLiveDatabaseUntouched() throws IOException {
        DatabaseManager db = seededDatabase("live.db");
        BackupService backup = new BackupService(db);
        Path good = tempDir.resolve("good.db");
        backup.backupDatabase(good);

        Path damaged = tempDir.resolve("damaged.db");
        Files.copy(good, damaged);
        try (FileChannel ch = FileChannel.open(damaged, StandardOpenOption.WRITE)) {
            long middle = (Files.size(damaged) / 2 / 4096) * 4096;
            ch.write(ByteBuffer.wrap(new byte[16384]), middle);
        }
        assertFalse(integrityOk(damaged), "Precondition: the test file must really be damaged");

        DictionaryDao dict = new DictionaryDao(db);
        dict.insertTargetGroup("KEEP", "Keep", "Marad");

        assertThrows(IOException.class, () -> backup.restoreDatabase(damaged));

        assertTrue(dict.findTargetGroups(false).stream().anyMatch(g -> "KEEP".equals(g.code())),
                "A rejected restore must leave the live database exactly as it was");
        try (var files = Files.list(tempDir)) {
            assertFalse(files.anyMatch(f -> f.getFileName().toString().startsWith("pre-restore-")),
                    "No safety copy should be made for a restore that never started");
        }
        assertFalse(leftoverRestoreFiles(tempDir), "No staging debris may remain after a rejected restore");
    }

    /**
     * A leftover rollback journal beside the database would be replayed by SQLite into the freshly
     * restored file, corrupting it. Restore must remove stale sidecars before swapping the file in.
     */
    @Test
    void restoreRemovesStaleJournalSidecarsAndLeavesAnIntactDatabase() throws IOException {
        DatabaseManager db = seededDatabase("live.db");
        BackupService backup = new BackupService(db);
        Path snapshot = tempDir.resolve("snapshot.db");
        backup.backupDatabase(snapshot);

        Path journal = tempDir.resolve("live.db-journal");
        Files.writeString(journal, "stale journal from an interrupted write");

        Path safety = backup.restoreDatabase(snapshot);

        assertFalse(Files.exists(journal), "A stale journal must not survive the file replacement");
        assertTrue(integrityOk(tempDir.resolve("live.db")), "The restored database must be intact");
        assertTrue(integrityOk(safety), "The safety copy must be a consistent, usable database");
        assertFalse(leftoverRestoreFiles(tempDir), "No staging files may remain after a successful restore");
    }

    @Test
    void refusesToRestoreTheLiveDatabaseOntoItself() {
        DatabaseManager db = seededDatabase("live.db");
        BackupService backup = new BackupService(db);

        assertThrows(IllegalArgumentException.class,
                () -> backup.restoreDatabase(db.getDatabasePath()));
        assertTrue(integrityOk(db.getDatabasePath()));
    }
}
