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
}
