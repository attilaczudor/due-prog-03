package hu.due.rewear.nav;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.Set;

/**
 * Keeps the NAV technical user's secrets in a file beside the database, readable only by the account that
 * runs the application.
 *
 * <p>Two decisions are worth stating plainly, because the comfortable alternatives are worse.
 *
 * <p><b>Why not the database.</b> The database is copied: backup bundles, restores onto a second machine, a
 * file handed to somebody for support. Credentials that can file tax returns on the company's behalf should
 * not travel in any of those, so they are kept outside and the backup never sees them.
 *
 * <p><b>Why not encrypted.</b> A desktop application has nowhere to hide a key from someone who already has
 * the machine: a key stored next to the ciphertext is decoration, and decoration that is described as
 * encryption is worse than none, because somebody will trust it. The protection here is the one the operating
 * system actually provides — owner-only file permissions, the same arrangement an SSH private key relies on.
 * Where the platform cannot express that (Windows, in practice), the file is still written, and
 * {@link #isProtected()} reports false so the administrator is told rather than reassured.
 */
public final class NavCredentialStore {
    private static final Logger log = LoggerFactory.getLogger(NavCredentialStore.class);
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final Path file;

    public NavCredentialStore(Path file) {
        this.file = file;
    }

    /** Where the secrets live, for an administrator who needs to find or remove the file. */
    public Path file() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /** True when the file is on disk and no account other than the owner can read it. */
    public boolean isProtected() {
        try {
            return exists() && Files.getPosixFilePermissions(file).equals(OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException ex) {
            return false;
        }
    }

    public NavCredentials load() {
        if (!exists()) {
            return new NavCredentials(null, null, null);
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the NAV credentials file", ex);
        }
        return new NavCredentials(
                props.getProperty("password"), props.getProperty("signKey"), props.getProperty("exchangeKey"));
    }

    public void save(NavCredentials credentials) {
        Properties props = new Properties();
        props.setProperty("password", nullToEmpty(credentials.password()));
        props.setProperty("signKey", nullToEmpty(credentials.signKey()));
        props.setProperty("exchangeKey", nullToEmpty(credentials.exchangeKey()));
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // The file is created with owner-only permissions from the start rather than widened and then
            // narrowed, so there is no moment in which another account could read it.
            if (!Files.exists(file)) {
                try {
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
                } catch (UnsupportedOperationException ex) {
                    Files.createFile(file);
                }
            }
            try (var out = Files.newOutputStream(file)) {
                props.store(out, "ReWear WMS — NAV technical user. Keep this file private; it is not backed up.");
            }
            try {
                Files.setPosixFilePermissions(file, OWNER_ONLY);
            } catch (UnsupportedOperationException ex) {
                log.warn("This platform cannot restrict the NAV credentials file to its owner: {}", file);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not write the NAV credentials file", ex);
        }
    }

    public void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not remove the NAV credentials file", ex);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
