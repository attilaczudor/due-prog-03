package hu.due.rewear.service;

import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The portable archive for PDFs produced when an invoice is saved. */
public final class InvoiceArchiveService {
    private static final Logger LOG = LoggerFactory.getLogger(InvoiceArchiveService.class);
    private final Path directory;
    private final InvoicePdfExporter exporter;

    public InvoiceArchiveService(Path directory, InvoicePdfExporter exporter) {
        this.directory = directory.toAbsolutePath().normalize();
        this.exporter = exporter;
    }

    public Path getDirectory() {
        return directory;
    }

    public long countInvoices() {
        return InvoiceFiles.count(directory);
    }

    String store(Invoice invoice, List<InvoiceItem> items) throws IOException {
        return InvoiceFiles.store(directory, invoice, ".pdf", file -> exporter.export(invoice, items, file));
    }

    Optional<Path> resolve(String storedPath) {
        return InvoiceFiles.resolve(directory, storedPath);
    }

    void delete(String storedPath) {
        resolve(storedPath).ifPresent(file -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ex) {
                LOG.warn("Could not remove unlinked invoice PDF {}", file, ex);
            }
        });
    }
}
