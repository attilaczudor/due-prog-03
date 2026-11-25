package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Provides safe SQLite database backup (via {@code VACUUM INTO}) and validated restore.
 */
public final class BackupService {

    private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DatabaseManager db;

    public BackupService(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Creates a consistent, compacted standalone copy of the active SQLite database.
     */
    public void backupDatabase(Path destinationPath) throws IOException {
        Path absTarget = destinationPath.toAbsolutePath().normalize();
        Path activeDatabase = db.getDatabasePath().normalize();
        if (absTarget.equals(activeDatabase) || (Files.exists(absTarget) && Files.isSameFile(absTarget, activeDatabase))) {
            throw new IllegalArgumentException("Backup destination cannot overwrite the active database file.");
        }
        if (absTarget.getParent() != null) {
            Files.createDirectories(absTarget.getParent());
        }
        Path stagingDirectory = Files.createTempDirectory(absTarget.getParent(), ".rewear-backup-");
        Path snapshot = stagingDirectory.resolve("snapshot.db");
        try {
            String escaped = snapshot.toString().replace("'", "''");
            try (Connection conn = db.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("VACUUM INTO '" + escaped + "'");
            } catch (SQLException e) {
                throw new IOException("Database backup failed: " + e.getMessage(), e);
            }
            Files.move(snapshot, absTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("SQLite database backed up to {}", absTarget);
        } finally {
            Files.deleteIfExists(snapshot);
            Files.deleteIfExists(stagingDirectory);
        }
    }

    /**
     * Validates a SQLite backup file (magic header + required schema tables),
     * saves a safety copy of the current DB, and restores the backup into place.
     */
    public Path restoreDatabase(Path sourceBackup) throws IOException {
        Path absSource = sourceBackup.toAbsolutePath();
        if (!Files.isRegularFile(absSource)) {
            throw new IOException("Backup file does not exist: " + absSource);
        }
        validateSqliteBackup(absSource);

        Path activeDb = db.getDatabasePath();
        Path safetyCopy = activeDb.resolveSibling(
                "pre-restore-" + TS_FMT.format(LocalDateTime.now()) + ".db.bak"
        );
        if (Files.exists(activeDb)) {
            Files.copy(activeDb, safetyCopy, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.copy(absSource, activeDb, StandardCopyOption.REPLACE_EXISTING);
        LOG.info("Database restored from {} (safety backup saved at {})", absSource, safetyCopy);
        return safetyCopy;
    }

    private void validateSqliteBackup(Path candidate) throws IOException {
        byte[] header = new byte[16];
        try (InputStream is = Files.newInputStream(candidate)) {
            int read = is.read(header);
            if (read < 16) {
                throw new IOException("File is too small to be a valid SQLite database.");
            }
            for (int i = 0; i < 16; i++) {
                if (header[i] != SQLITE_MAGIC[i]) {
                    throw new IOException("Selected file is not a valid SQLite 3 database.");
                }
            }
        }

        Set<String> requiredTables = Set.of("users", "roles", "inventory_packages", "orders");
        int found = 0;
        String url = "jdbc:sqlite:" + candidate;
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('users','roles','inventory_packages','orders')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (requiredTables.contains(rs.getString(1))) {
                    found++;
                }
            }
        } catch (SQLException e) {
            throw new IOException("Could not verify backup schema: " + e.getMessage(), e);
        }
        if (found < requiredTables.size()) {
            throw new IOException("Backup file is missing required ReWear WMS tables.");
        }
    }
}
