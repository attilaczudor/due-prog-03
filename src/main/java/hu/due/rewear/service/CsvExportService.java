package hu.due.rewear.service;

import hu.due.rewear.i18n.CsvTranslationLoader;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.StockMovement;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Exports inventory tables and stock movement audit logs to Excel-friendly UTF-8 CSV (with BOM).
 */
public final class CsvExportService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final I18nManager i18n;

    public CsvExportService(I18nManager i18n) {
        this.i18n = i18n;
    }

    public void exportPackages(List<InventoryPackage> packages, Path targetPath) throws IOException {
        if (targetPath.getParent() != null) {
            Files.createDirectories(targetPath.getParent());
        }
        String lang = i18n.getActiveLanguage();
        try (BufferedWriter w = Files.newBufferedWriter(targetPath, StandardCharsets.UTF_8)) {
            // UTF-8 BOM ensures Excel opens Hungarian accented text cleanly
            w.write('\uFEFF');
            writeRow(w, List.of(
                    "QR Code", "Target Code", "Target", "Type Code", "Garment Type",
                    "Quality Code", "Quality Grade", "Package Code", "Package Type",
                    "Weight (kg)", "Location", "Status", "Created By", "Created At", "Notes"
            ));
            for (InventoryPackage p : packages) {
                writeRow(w, List.of(
                        p.qrCode(),
                        p.targetGroupCode(),
                        p.localizedTargetGroup(lang),
                        p.garmentTypeCode(),
                        p.localizedGarmentType(lang),
                        p.qualityGradeCode(),
                        p.localizedQualityGrade(lang),
                        p.packageTypeCode(),
                        p.localizedPackageType(lang),
                        p.weightKg().stripTrailingZeros().toPlainString(),
                        p.locationCode(),
                        i18n.t("status.package." + p.status().name()),
                        p.createdByFullName(),
                        p.createdAt() != null ? DT_FMT.format(p.createdAt()) : "",
                        p.notes() != null ? p.notes() : ""
                ));
            }
        }
    }

    public void exportStockMovements(List<StockMovement> movements, Path targetPath) throws IOException {
        if (targetPath.getParent() != null) {
            Files.createDirectories(targetPath.getParent());
        }
        try (BufferedWriter w = Files.newBufferedWriter(targetPath, StandardCharsets.UTF_8)) {
            w.write('\uFEFF');
            writeRow(w, List.of(
                    "ID", "Timestamp", "QR Code", "Movement Type",
                    "From Location", "To Location", "Operator", "Note"
            ));
            for (StockMovement m : movements) {
                writeRow(w, List.of(
                        Long.toString(m.id()),
                        m.createdAt() != null ? DT_FMT.format(m.createdAt()) : "",
                        m.packageQrCode(),
                        i18n.t("movement." + m.movementType().name()),
                        m.fromLocationCode() != null ? m.fromLocationCode() : "",
                        m.toLocationCode() != null ? m.toLocationCode() : "",
                        m.userFullName() + " (" + m.username() + ")",
                        m.note() != null ? m.note() : ""
                ));
            }
        }
    }

    public void exportInvoices(List<Invoice> invoices, Path targetPath) throws IOException {
        if (targetPath.getParent() != null) {
            Files.createDirectories(targetPath.getParent());
        }
        try (BufferedWriter w = Files.newBufferedWriter(targetPath, StandardCharsets.UTF_8)) {
            w.write('\uFEFF');
            writeRow(w, List.of(
                    "Invoice Number", "Direction", "Category", "Order Ref", "Partner",
                    "Tax Number", "Status", "Issue Date", "Due Date", "Paid Date",
                    "Net (EUR)", "VAT %", "VAT (EUR)", "Gross (EUR)"
            ));
            for (Invoice inv : invoices) {
                writeRow(w, List.of(
                        inv.invoiceNumber(),
                        i18n.t("invoice.direction." + inv.direction().name()),
                        i18n.t("invoice.category." + inv.category().name()),
                        inv.orderNumber() != null ? inv.orderNumber() : "",
                        inv.partnerName(),
                        inv.partnerTaxNumber() != null ? inv.partnerTaxNumber() : "",
                        i18n.t("status.invoice." + inv.status().name()),
                        inv.issueDate().toString(),
                        inv.dueDate().toString(),
                        inv.paidDate() != null ? inv.paidDate().toString() : "",
                        inv.netAmount().toPlainString(),
                        inv.vatRate().toPlainString(),
                        inv.vatAmount().toPlainString(),
                        inv.grossAmount().toPlainString()
                ));
            }
        }
    }

    private static void writeRow(BufferedWriter w, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                w.write(',');
            }
            w.write(CsvTranslationLoader.escapeCsvField(cells.get(i)));
        }
        w.newLine();
    }
}
