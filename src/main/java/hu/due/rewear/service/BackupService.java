package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Provides safe SQLite database backup (via {@code VACUUM INTO}) and validated restore.
 */
public final class BackupService {

    private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** SQLite files that sit beside the database and must not survive a file replacement. */
    private static final String[] SIDECAR_SUFFIXES = {"-journal", "-wal", "-shm"};

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
     * Restores a previously taken backup over the live database.
     * <p>
     * The candidate is fully validated (header, required tables and a {@code PRAGMA integrity_check})
     * before anything is touched. The live database is then preserved as a consistent safety copy
     * taken with {@code VACUUM INTO} rather than a raw file copy, which could capture a write in
     * flight. The restored file is staged next to the live one and moved into place in a single step,
     * so a failure part-way through (for example a full disk) leaves the live database intact instead
     * of half overwritten. Stale journal sidecars are removed first because SQLite would otherwise
     * replay an old journal into the newly restored file.
     *
     * @return the safety copy of the database as it was before the restore
     */
    public Path restoreDatabase(Path sourceBackup) throws IOException {
        Path absSource = sourceBackup.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absSource)) {
            throw new IOException("Backup file does not exist: " + absSource);
        }
        Path activeDb = db.getDatabasePath();
        if (absSource.equals(activeDb.normalize()) || Files.isSameFile(absSource, activeDb)) {
            throw new IllegalArgumentException("The live database cannot be restored onto itself.");
        }
        validateSqliteBackup(absSource);

        Path safetyCopy = activeDb.resolveSibling(
                "pre-restore-" + TS_FMT.format(BusinessClock.now()) + ".db.bak"
        );
        if (Files.exists(activeDb)) {
            backupDatabase(safetyCopy);
        }

        Path staged = Files.createTempFile(activeDb.toAbsolutePath().getParent(), ".rewear-restore-", ".tmp");
        try {
            Files.copy(absSource, staged, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(staged) != Files.size(absSource)) {
                throw new IOException("Restore copy was incomplete; the live database was not changed.");
            }
            for (String suffix : SIDECAR_SUFFIXES) {
                Files.deleteIfExists(activeDb.resolveSibling(activeDb.getFileName() + suffix));
            }
            try {
                Files.move(staged, activeDb, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(staged, activeDb, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
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
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        String url = "jdbc:sqlite:" + candidate;
        try (Connection conn = DriverManager.getConnection(url, readOnly.toProperties())) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('users','roles','inventory_packages','orders')");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (requiredTables.contains(rs.getString(1))) {
                        found++;
                    }
                }
            }
            if (found < requiredTables.size()) {
                throw new IOException("Backup file is missing required ReWear WMS tables.");
            }
            // The header and table names live on the first pages, so a file damaged anywhere else
            // would pass the checks above. Only a full integrity scan notices that.
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
                boolean intact = rs.next() && "ok".equalsIgnoreCase(rs.getString(1)) && !rs.next();
                if (!intact) {
                    throw new IOException("Backup file is damaged (SQLite integrity check failed).");
                }
            }
        } catch (SQLException e) {
            throw new IOException("Could not verify backup: " + e.getMessage(), e);
        }
    }
}
