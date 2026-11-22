package hu.due.rewear.service;

import hu.due.rewear.dao.InvoiceDao;
import hu.due.rewear.model.Invoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Manages the portable Expense Receipt Vault ({@code ./data/receipts/}) anchored alongside the SQLite database.
 * Copies scanned vendor PDFs/PNGs/JPGs into the vault and persists only safe, portable relative filenames
 * inside {@code invoices.attachment_path} so attachments travel seamlessly when moving the app folder.
 */
public final class ReceiptVaultService {

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptVaultService.class);

    private final Path receiptsDir;
    private final InvoiceDao invoiceDao;

    public ReceiptVaultService(Path receiptsDir, InvoiceDao invoiceDao) {
        this.receiptsDir = Objects.requireNonNull(receiptsDir, "receiptsDir").toAbsolutePath().normalize();
        this.invoiceDao = Objects.requireNonNull(invoiceDao, "invoiceDao");
    }

    public Path getReceiptsDir() {
        return receiptsDir;
    }

    /** Copies {@code sourceFile} into the portable receipts vault and links it to {@code invoiceId}. */
    public Invoice attachReceipt(long invoiceId, Path sourceFile) throws IOException {
        Objects.requireNonNull(sourceFile, "sourceFile");
        if (!Files.isRegularFile(sourceFile)) {
            throw new IOException("Receipt file does not exist: " + sourceFile);
        }
        Invoice inv = invoiceDao.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));

        Files.createDirectories(receiptsDir);
        String ext = extractSafeExtension(sourceFile.getFileName().toString());
        String base = sanitizeSegment(inv.invoiceNumber());
        String storedFileName = base + "-receipt" + ext;
        Path dest = receiptsDir.resolve(storedFileName).normalize();
        if (!dest.startsWith(receiptsDir)) {
            throw new IOException("Invalid receipt destination path.");
        }

        // Remove previous file if replacing with a different extension
        resolveAttachment(inv).ifPresent(old -> {
            if (!old.equals(dest)) {
                try {
                    Files.deleteIfExists(old);
                } catch (IOException ignored) {
                    // Best-effort cleanup
                }
            }
        });

        Files.copy(sourceFile, dest, StandardCopyOption.REPLACE_EXISTING);
        invoiceDao.updateAttachmentPath(invoiceId, storedFileName);
        LOG.info("Attached receipt {} to invoice {}", storedFileName, inv.invoiceNumber());
        return invoiceDao.findById(invoiceId).orElseThrow();
    }

    /** Resolves an invoice's portable receipt filename back to an existing absolute file inside the vault. */
    public Optional<Path> resolveAttachment(Invoice inv) {
        if (inv == null || !inv.hasAttachment()) {
            return Optional.empty();
        }
        Path candidate = receiptsDir.resolve(inv.attachmentPath().trim()).normalize();
        if (!candidate.startsWith(receiptsDir) || !Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    /** Removes the receipt link from SQLite and deletes the stored file from the vault if present. */
    public Invoice removeAttachment(long invoiceId) throws IOException {
        Invoice inv = invoiceDao.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));
        Optional<Path> existing = resolveAttachment(inv);
        invoiceDao.updateAttachmentPath(invoiceId, null);
        if (existing.isPresent()) {
            Files.deleteIfExists(existing.get());
        }
        LOG.info("Removed receipt attachment from invoice {}", inv.invoiceNumber());
        return invoiceDao.findById(invoiceId).orElseThrow();
    }

    /** Counts files currently stored in the portable receipts vault for Admin diagnostics. */
    public long countVaultFiles() {
        if (!Files.isDirectory(receiptsDir)) {
            return 0L;
        }
        try (Stream<Path> s = Files.list(receiptsDir)) {
            return s.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String sanitizeSegment(String raw) {
        String clean = (raw == null ? "INV" : raw.trim()).replaceAll("[^A-Za-z0-9_-]", "_");
        return clean.isEmpty() ? "INV" : clean;
    }

    private static String extractSafeExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return ".pdf";
        }
        String ext = filename.substring(dot).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case ".pdf", ".png", ".jpg", ".jpeg" -> ext;
            default -> ".pdf";
        };
    }
}
