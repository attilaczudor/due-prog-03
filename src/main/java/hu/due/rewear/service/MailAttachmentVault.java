package hu.due.rewear.service;

import hu.due.rewear.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Durable storage for e-mail attachments ({@code ./data/mail-attachments/}), anchored beside the
 * SQLite database like the invoice archive and the receipt vault.
 * <p>
 * Each attachment is <b>copied</b> in when a message is sent or saved as a draft, and the mail rows
 * store a portable relative path ({@code YYYY/MM/name.ext}). Before this vault a mail row held the
 * absolute path of whatever file the composer happened to point at, so a moved, renamed or cleaned-up
 * export silently broke the attachment, and the viewer opened whatever path the row contained.
 * <p>
 * Only a small allow-list of document and image types is accepted, each checked against its file
 * signature where one exists. That is also what the viewer relies on before handing a file to the
 * operating system, so an executable or script can neither be attached nor launched from a message.
 */
public final class MailAttachmentVault {

    private static final Logger LOG = LoggerFactory.getLogger(MailAttachmentVault.class);

    /** Largest attachment accepted, in bytes. */
    public static final long DEFAULT_MAX_BYTES = 25L * 1024 * 1024;

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final byte[] ZIP = {'P', 'K', 3, 4};
    private static final byte[] NO_SIGNATURE = {};

    /** Allowed extensions mapped to the signature their content must start with. */
    private static final Map<String, byte[]> ALLOWED = Map.of(
            "pdf", PDF,
            "png", PNG,
            "jpg", JPEG,
            "jpeg", JPEG,
            "docx", ZIP,
            "xlsx", ZIP,
            "pptx", ZIP,
            "csv", NO_SIGNATURE,
            "txt", NO_SIGNATURE);

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("uuuu/MM", Locale.ROOT);

    private final Path root;
    private final long maxBytes;
    private final Supplier<LocalDate> today;

    public MailAttachmentVault(Path root) {
        this(root, DEFAULT_MAX_BYTES, BusinessClock::today);
    }

    /** Test seam: a smaller size limit and a fixed date source. */
    MailAttachmentVault(Path root, long maxBytes, Supplier<LocalDate> today) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.today = Objects.requireNonNull(today, "today");
    }

    public Path getRoot() {
        return root;
    }

    /** The file types the vault accepts, as a readable list for error messages. */
    public static String allowedTypesDescription() {
        return "PDF, PNG, JPEG, DOCX, XLSX, PPTX, CSV or TXT";
    }

    /**
     * Whether {@code path} has an extension the vault accepts. Used by the viewer as a last check
     * before opening a legacy attachment that predates the vault.
     */
    public static boolean isAllowedFile(Path path) {
        return path != null && path.getFileName() != null
                && ALLOWED.containsKey(extensionOf(path.getFileName().toString()));
    }

    /**
     * Copies {@code source} into the vault and returns its portable relative path.
     *
     * @throws IllegalArgumentException if the file is missing, empty, too large, of an unsupported
     *                                  type, or its content does not match its extension
     * @throws IOException              if the copy itself fails
     */
    public String store(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IllegalArgumentException("The attachment must be a readable file.");
        }
        String name = source.getFileName().toString();
        String extension = extensionOf(name);
        byte[] signature = ALLOWED.get(extension);
        if (signature == null) {
            throw new IllegalArgumentException("Attachments must be " + allowedTypesDescription() + " files.");
        }
        long size = Files.size(source);
        if (size == 0) {
            throw new IllegalArgumentException("The attachment is empty.");
        }
        if (size > maxBytes) {
            throw new IllegalArgumentException("The attachment is larger than " + (maxBytes / (1024 * 1024)) + " MB.");
        }

        String month = today.get().format(YEAR_MONTH);
        Path directory = root.resolve(month);
        Files.createDirectories(directory);
        Path staged = Files.createTempFile(directory, ".rewear-", ".tmp");
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            requireSignature(staged, signature);
            String stem = safeStem(name);
            for (int attempt = 1; ; attempt++) {
                String candidate = (attempt == 1 ? stem : stem + "-" + attempt) + "." + extension;
                Path destination = directory.resolve(candidate);
                try {
                    Files.createFile(destination);
                } catch (FileAlreadyExistsException taken) {
                    continue;
                }
                try {
                    move(staged, destination);
                } catch (IOException failed) {
                    Files.deleteIfExists(destination);
                    throw failed;
                }
                LOG.info("Stored mail attachment {}", month + "/" + candidate);
                return month + "/" + candidate;
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** True when {@code stored} is a vault-relative path that currently resolves to a vault file. */
    public boolean contains(String stored) {
        return resolveStored(stored).isPresent();
    }

    /**
     * Resolves a mail row's attachment path to a file that is safe to open.
     * <p>
     * A vault-relative path must stay inside the vault: absolute paths, {@code ..} segments and
     * symbolic links that lead out are all refused. A legacy absolute path, written before the vault
     * existed, is still honoured when the file exists and is one of the allowed types, so old
     * messages keep working without ever opening an executable.
     */
    public Optional<Path> resolve(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        Optional<Path> inVault = resolveStored(stored);
        if (inVault.isPresent()) {
            return inVault;
        }
        try {
            Path legacy = Path.of(stored);
            if (legacy.isAbsolute() && Files.isRegularFile(legacy) && isAllowedFile(legacy)) {
                return Optional.of(legacy);
            }
        } catch (RuntimeException unparsable) {
            LOG.debug("Ignoring unparsable attachment path '{}'", stored);
        }
        return Optional.empty();
    }

    /** Removes a vault file. Paths that are not inside the vault are left alone. */
    public void delete(String stored) {
        resolveStored(stored).ifPresent(file -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ex) {
                LOG.debug("Could not delete mail attachment {}", file, ex);
            }
        });
    }

    /**
     * Deletes vault files that no mail row references and that are older than {@code minAge}.
     * <p>
     * Covers the rare gap between copying a file in and saving the mail row (a crash, or a database
     * error after the copy). The age guard keeps a message that is still being sent safe.
     *
     * @return how many files were removed
     */
    public int pruneUnreferenced(Set<String> referencedPaths, Duration minAge, Instant now) {
        Set<Path> keep = new HashSet<>();
        for (String stored : referencedPaths) {
            resolveStored(stored).ifPresent(keep::add);
        }
        Instant cutoff = now.minus(minAge);
        int removed = 0;
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).toList()) {
                try {
                    if (keep.contains(file) || Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) {
                        continue;
                    }
                    Files.deleteIfExists(file);
                    removed++;
                } catch (IOException perFile) {
                    LOG.debug("Could not prune mail attachment {}", file, perFile);
                }
            }
        } catch (IOException | java.io.UncheckedIOException ex) {
            LOG.debug("Could not scan the mail attachment vault {}", root, ex);
        }
        if (removed > 0) {
            LOG.info("Pruned {} unreferenced mail attachment(s)", removed);
        }
        return removed;
    }

    private Optional<Path> resolveStored(String stored) {
        return InvoiceFiles.resolve(root, stored);
    }

    private static void requireSignature(Path file, byte[] signature) throws IOException {
        if (signature.length == 0) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            if (!Arrays.equals(in.readNBytes(signature.length), signature)) {
                throw new IllegalArgumentException("The attachment's contents do not match its file type.");
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** A filesystem-safe, ASCII file stem: accents folded, everything else collapsed to hyphens. */
    private static String safeStem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String stem = dot < 0 ? fileName : fileName.substring(0, dot);
        String folded = Normalizer.normalize(stem, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .replaceAll("[^a-zA-Z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (folded.isEmpty()) {
            return "attachment";
        }
        return folded.substring(0, Math.min(folded.length(), 80)).replaceAll("-+$", "");
    }
}
