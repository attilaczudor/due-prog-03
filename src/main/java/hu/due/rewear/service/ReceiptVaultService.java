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

/**
 * Manages the portable Expense Receipt Vault ({@code ./data/receipts/}) anchored alongside the SQLite database.
 * Copies scanned vendor PDFs/PNGs/JPGs into dated folders and persists safe, portable relative paths
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

    String storeReceipt(Invoice invoice, Path source) throws IOException {
        if (!Files.isRegularFile(source) || !Files.isReadable(source) || Files.size(source) == 0) {
            throw new IOException("Receipt must be a readable, non-empty file.");
        }
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        if (!java.util.Set.of(".pdf", ".png", ".jpg", ".jpeg").contains(extension)) {
            throw new IOException("Choose a PDF, PNG, or JPEG receipt.");
        }
        return InvoiceFiles.store(receiptsDir, invoice, extension, dest -> {
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            byte[] expected = switch (extension) {
                case ".pdf" -> new byte[]{'%', 'P', 'D', 'F', '-'};
                case ".png" -> new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
                default -> new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff};
            };
            try (var input = Files.newInputStream(dest)) {
                if (!java.util.Arrays.equals(input.readNBytes(expected.length), expected)) {
                    throw new IOException("Receipt contents do not match the selected file format.");
                }
            }
        });
    }

    /** Copies {@code sourceFile} into the portable receipts vault and links it to {@code invoiceId}. */
    public Invoice attachReceipt(long invoiceId, Path sourceFile) throws IOException {
        Objects.requireNonNull(sourceFile, "sourceFile");
        Invoice inv = invoiceDao.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));

        String storedFileName = storeReceipt(inv, sourceFile);
        try {
            if (!invoiceDao.replaceAttachmentPath(invoiceId, inv.attachmentPath(), storedFileName)) {
                throw new IOException("The invoice receipt changed. Reload the invoice and try again.");
            }
        } catch (IOException | RuntimeException ex) {
            deleteStoredReceipt(storedFileName);
            throw ex;
        }
        resolveAttachment(inv).ifPresent(this::deleteUnlinkedReceipt);
        LOG.info("Attached receipt {} to invoice {}", storedFileName, inv.invoiceNumber());
        return invoiceDao.findById(invoiceId).orElseThrow();
    }

    /** Resolves an invoice's portable receipt filename back to an existing absolute file inside the vault. */
    public Optional<Path> resolveAttachment(Invoice inv) {
        return inv == null ? Optional.empty() : InvoiceFiles.resolve(receiptsDir, inv.attachmentPath());
    }

    /** Removes the receipt link from SQLite and deletes the stored file from the vault if present. */
    public Invoice removeAttachment(long invoiceId) throws IOException {
        Invoice inv = invoiceDao.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));
        Optional<Path> existing = resolveAttachment(inv);
        if (!invoiceDao.replaceAttachmentPath(invoiceId, inv.attachmentPath(), null)) {
            throw new IOException("The invoice receipt changed. Reload the invoice and try again.");
        }
        existing.ifPresent(this::deleteUnlinkedReceipt);
        LOG.info("Removed receipt attachment from invoice {}", inv.invoiceNumber());
        return invoiceDao.findById(invoiceId).orElseThrow();
    }

    private void deleteUnlinkedReceipt(Path receipt) {
        try {
            Files.deleteIfExists(receipt);
        } catch (IOException ex) {
            LOG.warn("Could not remove unlinked receipt {}", receipt.getFileName(), ex);
        }
    }

    void deleteStoredReceipt(String storedPath) {
        InvoiceFiles.resolve(receiptsDir, storedPath).ifPresent(this::deleteUnlinkedReceipt);
    }

    /** Counts files currently stored in the portable receipts vault for Admin diagnostics. */
    public long countVaultFiles() {
        return InvoiceFiles.count(receiptsDir);
    }
}
