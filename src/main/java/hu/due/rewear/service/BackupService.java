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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Provides safe SQLite database backup (via {@code VACUUM INTO}), full backup bundles (database plus the
 * invoice, receipt and mail attachment folders) and validated restore of either.
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
            snapshotInto(snapshot);
            moveIntoPlace(snapshot, absTarget);
            LOG.info("SQLite database backed up to {}", absTarget);
        } finally {
            Files.deleteIfExists(snapshot);
            Files.deleteIfExists(stagingDirectory);
        }
    }

    private void snapshotInto(Path snapshot) throws IOException {
        String escaped = snapshot.toString().replace("'", "''");
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("VACUUM INTO '" + escaped + "'");
        } catch (SQLException e) {
            throw new IOException("Database backup failed: " + e.getMessage(), e);
        }
    }

    private static void moveIntoPlace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path dataDirectory() {
        return db.getDatabasePath().toAbsolutePath().normalize().getParent();
    }

    /** What a full backup contained. */
    public record BundleBackup(Path file, int documentCount, long totalBytes) { }

    /** Where the pre-restore copies of the previous state were kept, and how many documents came back. */
    public record BundleRestore(Path databaseSafetyCopy, Path documentsSafetyFolder, int documentCount) { }

    /** True when the file is a full backup bundle rather than a bare database file. */
    public static boolean isBundle(Path file) {
        return BackupBundle.looksLikeBundle(file);
    }

    /**
     * Writes a full backup: a consistent database snapshot plus every invoice, receipt and mail
     * attachment file, with a checksum manifest so a damaged copy is noticed before it is restored.
     * The file is staged beside the destination and moved into place, so a failure never leaves a
     * half-written backup under the final name.
     */
    public BundleBackup backupBundle(Path destination) throws IOException {
        Path absTarget = destination.toAbsolutePath().normalize();
        Path dataDir = dataDirectory();
        for (String folder : BackupBundle.FOLDERS) {
            if (absTarget.startsWith(dataDir.resolve(folder))) {
                throw new IllegalArgumentException("A backup cannot be saved inside the " + folder + " folder it contains.");
            }
        }
        if (absTarget.equals(db.getDatabasePath().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Backup destination cannot overwrite the active database file.");
        }
        Files.createDirectories(absTarget.getParent());
        Path staging = Files.createTempDirectory(absTarget.getParent(), ".rewear-backup-");
        try {
            Path snapshot = staging.resolve("snapshot.db");
            snapshotInto(snapshot);
            Path bundle = staging.resolve("bundle.zip");
            BackupBundle.Written written = BackupBundle.write(bundle, snapshot, dataDir);
            moveIntoPlace(bundle, absTarget);
            LOG.info("Full backup written to {} ({} documents, {} bytes)", absTarget, written.files(), written.bytes());
            return new BundleBackup(absTarget, written.files(), written.bytes());
        } finally {
            deleteTree(staging);
        }
    }

    /**
     * Restores a full backup bundle: database and documents together.
     * <p>
     * Everything is verified and unpacked into a staging folder first (manifest, checksums, database
     * integrity), so a bad bundle changes nothing. The current document folders are then moved aside, not
     * deleted, the staged ones take their place, and the database is swapped last. If any step fails the
     * moves are undone and the previous state is back exactly as it was.
     */
    public BundleRestore restoreBundle(Path bundle) throws IOException {
        Path absBundle = bundle.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absBundle)) {
            throw new IOException("Backup file does not exist: " + absBundle);
        }
        Path dataDir = dataDirectory();
        Path staging = Files.createTempDirectory(dataDir, ".rewear-restore-");
        List<Path[]> moves = new ArrayList<>();
        Path documentsSafety = null;
        try {
            BackupBundle.Extracted extracted;
            try {
                extracted = BackupBundle.extract(absBundle, staging);
            } catch (java.util.zip.ZipException e) {
                throw new IOException("Not a valid backup bundle: " + e.getMessage(), e);
            }
            validateSqliteBackup(extracted.database());

            documentsSafety = uniqueDirectory(dataDir, "pre-restore-" + TS_FMT.format(BusinessClock.now()) + "-files");
            try {
                for (String folder : BackupBundle.FOLDERS) {
                    Path live = dataDir.resolve(folder);
                    Path incoming = staging.resolve("files").resolve(folder);
                    Files.createDirectories(incoming);
                    if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                        Path aside = documentsSafety.resolve(folder);
                        moveDirectory(live, aside);
                        moves.add(new Path[]{live, aside});
                    }
                    moveDirectory(incoming, live);
                    moves.add(new Path[]{incoming, live});
                }
                Path databaseSafety = restoreDatabase(extracted.database());
                LOG.info("Full backup restored from {} ({} documents; previous documents kept in {})",
                        absBundle, extracted.files(), documentsSafety);
                return new BundleRestore(databaseSafety, documentsSafety, extracted.files());
            } catch (IOException | RuntimeException e) {
                rollBack(moves, e);
                throw e;
            }
        } finally {
            deleteTree(staging);
        }
    }

    private static Path uniqueDirectory(Path parent, String name) throws IOException {
        for (int attempt = 1; ; attempt++) {
            Path candidate = parent.resolve(attempt == 1 ? name : name + "-" + attempt);
            try {
                return Files.createDirectory(candidate);
            } catch (FileAlreadyExistsException ex) {
                // try the next suffix
            }
        }
    }

    private static void moveDirectory(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to);
        }
    }

    /** Undoes completed moves newest-first; the originals are never deleted, so a failed undo loses nothing. */
    private static void rollBack(List<Path[]> moves, Exception cause) {
        for (int i = moves.size() - 1; i >= 0; i--) {
            Path from = moves.get(i)[0];
            Path to = moves.get(i)[1];
            try {
                moveDirectory(to, from);
            } catch (IOException undo) {
                LOG.error("Could not undo restore step {} -> {}; the previous data is still at {}", from, to, to, undo);
                cause.addSuppressed(undo);
            }
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warn("Could not remove temporary backup file {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.warn("Could not clean up {}: {}", root, e.getMessage());
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
