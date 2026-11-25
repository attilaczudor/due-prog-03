package hu.due.rewear.service;

import hu.due.rewear.model.Invoice;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/** Shared naming and portable paths for invoice PDFs and their source documents. */
final class InvoiceFiles {
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("uuuu/MM", Locale.ROOT);

    @FunctionalInterface
    interface Writer {
        void write(Path destination) throws IOException;
    }

    private InvoiceFiles() { }

    static String baseName(Invoice invoice) {
        return invoice.issueDate() + "-" + safeSegment(invoice.partnerName(), "partner") + "-"
                + invoice.grossAmount().stripTrailingZeros().toPlainString();
    }

    static String store(Path root, Invoice invoice, String extension, Writer writer) throws IOException {
        String month = invoice.issueDate().format(YEAR_MONTH);
        Files.createDirectories(root);
        Path date = Path.of(month);
        Path yearDir = createDateDirectory(root.resolve(date.getParent()));
        Path directory = createDateDirectory(yearDir.resolve(date.getFileName()));
        String base = baseName(invoice);
        String duplicate = base + "-" + safeSegment(invoice.invoiceNumber(), "invoice");
        Path staged = Files.createTempFile(directory, ".rewear-", ".tmp");
        try {
            writer.write(staged);
            for (int attempt = 0; ; attempt++) {
                String stem = attempt == 0 ? base : attempt == 1 ? duplicate : duplicate + "-" + attempt;
                Path destination = directory.resolve(stem + extension);
                try {
                    Files.createFile(destination);
                } catch (FileAlreadyExistsException ex) {
                    continue;
                }
                try {
                    try {
                        Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException ex) {
                        Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException ex) {
                    deleteAfterFailure(destination, ex);
                    throw ex;
                }
                return month + "/" + destination.getFileName();
            }
        } catch (IOException | RuntimeException ex) {
            deleteAfterFailure(staged, ex);
            throw ex;
        }
    }

    private static Path createDateDirectory(Path directory) throws IOException {
        try {
            return Files.createDirectory(directory);
        } catch (FileAlreadyExistsException ex) {
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return directory;
            throw ex;
        }
    }

    static Optional<Path> resolve(Path root, String storedPath) {
        if (storedPath == null || storedPath.isBlank()) return Optional.empty();
        try {
            Path relative = Path.of(storedPath);
            if (relative.isAbsolute() || !relative.equals(relative.normalize())) return Optional.empty();
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || !candidate.toRealPath().startsWith(root.toRealPath())) return Optional.empty();
            return Optional.of(candidate);
        } catch (IOException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    static long count(Path root) {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.getFileName().toString().startsWith(".rewear-")).count();
        } catch (IOException | java.io.UncheckedIOException ex) {
            return 0L;
        }
    }

    private static String safeSegment(String raw, String fallback) {
        if (raw == null) return fallback;
        String name = Normalizer.normalize(raw, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .replaceAll("[^a-zA-Z0-9]+", "-").replaceAll("^-|-$", "");
        if (name.isEmpty()) return fallback;
        return name.substring(0, Math.min(name.length(), 80)).replaceAll("-$", "");
    }

    private static void deleteAfterFailure(Path path, Exception failure) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }
}
