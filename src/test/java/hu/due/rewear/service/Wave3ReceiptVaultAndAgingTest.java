package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.DaoException;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.PartnerAgingStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Wave3ReceiptVaultAndAgingTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private long adminId;

    @BeforeEach
    void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("wave3-test.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        ctx = new AppContext(db, prefs, new I18nManager());
        adminId = ctx.getUserDao().findByUsername("admin").orElseThrow().id();
    }

    @Test
    void attachesResolvesAndRemovesReceiptsInPortableVault() throws Exception {
        LocalDate issueDate = LocalDate.of(2025, 3, 9);
        var archive = ctx.getInvoiceArchiveService();
        var vault = ctx.getReceiptVaultService();
        assertTrue(Files.isDirectory(archive.getDirectory()));
        assertTrue(Files.isDirectory(vault.getReceiptsDir()));
        Invoice expense = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.INCOMING, InvoiceCategory.UTILITIES, null, null,
                "MVM Next Zrt.", "26713111-2-44", "1081 Budapest", "szamlazas@mvm.hu",
                "Monthly Electricity", BigDecimal.ONE, "hó", new BigDecimal("42000"),
                new BigDecimal("27"), "HU-27", "HUF",
                issueDate, issueDate.plusDays(14), InvoiceStatus.ISSUED, adminId);

        String datedPath = "2025/03/2025-03-09-MVM-Next-Zrt-53340";
        try (var conn = ctx.getDatabaseManager().getConnection()) {
            assertEquals(datedPath + ".pdf", ctx.getInvoiceDao().findArchivedPdfPath(conn, expense.id()));
        }
        try (var pdf = Files.newInputStream(archive.getDirectory().resolve(datedPath + ".pdf"))) {
            assertEquals("%PDF-", new String(pdf.readNBytes(5), StandardCharsets.US_ASCII));
        }
        assertEquals(1, archive.countInvoices());

        assertFalse(expense.hasAttachment());
        assertEquals(0L, ctx.getReceiptVaultService().countVaultFiles());

        // Attach a scanned PDF receipt
        Path sourceReceipt = tempDir.resolve("mvm-bill-nov.pdf");
        Files.writeString(sourceReceipt, "%PDF-1.4 fake receipt content", StandardCharsets.UTF_8);

        Invoice attached = ctx.getReceiptVaultService().attachReceipt(expense.id(), sourceReceipt);
        assertTrue(attached.hasAttachment());
        assertEquals(datedPath + ".pdf", attached.attachmentPath());
        assertEquals(1L, ctx.getReceiptVaultService().countVaultFiles());

        Path resolved = ctx.getReceiptVaultService().resolveAttachment(attached).orElseThrow();
        assertTrue(Files.isRegularFile(resolved));
        assertTrue(resolved.startsWith(ctx.getReceiptVaultService().getReceiptsDir()));

        Path replacement = tempDir.resolve("replacement.pdf");
        Files.writeString(replacement, "%PDF-1.4 replacement receipt", StandardCharsets.UTF_8);
        try (var conn = ctx.getDatabaseManager().getConnection(); var statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER reject_receipt_update BEFORE UPDATE OF attachment_path ON invoices
                    BEGIN SELECT RAISE(ABORT, 'Simulated database write failure'); END
                    """);
            assertThrows(DaoException.class,
                    () -> ctx.getReceiptVaultService().attachReceipt(expense.id(), replacement));
            assertEquals(attached.attachmentPath(), ctx.getInvoiceDao().findById(expense.id()).orElseThrow().attachmentPath());
            assertEquals(Files.readString(sourceReceipt), Files.readString(resolved));
            assertEquals(1L, ctx.getReceiptVaultService().countVaultFiles());
            statement.execute("DROP TRIGGER reject_receipt_update");
        }
        Invoice replaced = ctx.getReceiptVaultService().attachReceipt(expense.id(), replacement);
        assertEquals(datedPath + "-" + expense.invoiceNumber() + ".pdf", replaced.attachmentPath());
        assertEquals(Files.readString(replacement),
                Files.readString(ctx.getReceiptVaultService().resolveAttachment(replaced).orElseThrow()));
        assertFalse(Files.exists(resolved));
        assertEquals(1L, ctx.getReceiptVaultService().countVaultFiles());

        // Remove attachment -> cleans both DB reference and file on disk
        Invoice removed = ctx.getReceiptVaultService().removeAttachment(expense.id());
        assertFalse(removed.hasAttachment());
        assertTrue(ctx.getReceiptVaultService().resolveAttachment(removed).isEmpty());
        assertEquals(0L, ctx.getReceiptVaultService().countVaultFiles());

        Path legacy = Files.copy(sourceReceipt, vault.getReceiptsDir().resolve("legacy.pdf"));
        assertTrue(ctx.getInvoiceDao().replaceAttachmentPath(expense.id(), null, "legacy.pdf"));
        assertEquals(legacy, vault.resolveAttachment(ctx.getInvoiceDao().findById(expense.id()).orElseThrow()).orElseThrow());
        assertTrue(ctx.getInvoiceDao().replaceAttachmentPath(expense.id(), "legacy.pdf", "../mvm-bill-nov.pdf"));
        assertTrue(vault.resolveAttachment(ctx.getInvoiceDao().findById(expense.id()).orElseThrow()).isEmpty());
    }

    @Test
    void computesMultiCurrencyPartnerAgingBucketsAndExportsStatementPdf() throws Exception {
        Customer partner = ctx.getCustomerDao().findBuyers(true).get(0);
        LocalDate asOf = LocalDate.of(2026, 11, 22);

        // 1. Current (not yet due): 100 EUR
        Invoice iCurrent = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, partner.id(),
                partner.name(), partner.taxNumber(), partner.address(), partner.email(),
                "Current Shipment", BigDecimal.TEN, "kg", new BigDecimal("10.00"),
                BigDecimal.ZERO, "EU-0", "EUR",
                asOf.minusDays(2), asOf.plusDays(12), InvoiceStatus.ISSUED, adminId);

        // 2. Overdue 1-30 days (15 days past due): 200 EUR
        Invoice i15 = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, partner.id(),
                partner.name(), partner.taxNumber(), partner.address(), partner.email(),
                "15d Past Due", new BigDecimal("20"), "kg", new BigDecimal("10.00"),
                BigDecimal.ZERO, "EU-0", "EUR",
                asOf.minusDays(29), asOf.minusDays(15), InvoiceStatus.OVERDUE, adminId);

        // 3. Overdue 31-60 days (45 days past due): 300 EUR
        Invoice i45 = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, partner.id(),
                partner.name(), partner.taxNumber(), partner.address(), partner.email(),
                "45d Past Due", new BigDecimal("30"), "kg", new BigDecimal("10.00"),
                BigDecimal.ZERO, "EU-0", "EUR",
                asOf.minusDays(60), asOf.minusDays(45), InvoiceStatus.OVERDUE, adminId);

        // 4. Overdue 60+ days (75 days past due): 400 EUR
        Invoice i75 = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, partner.id(),
                partner.name(), partner.taxNumber(), partner.address(), partner.email(),
                "75d Past Due", new BigDecimal("40"), "kg", new BigDecimal("10.00"),
                BigDecimal.ZERO, "EU-0", "EUR",
                asOf.minusDays(90), asOf.minusDays(75), InvoiceStatus.OVERDUE, adminId);

        // 5. Separate HUF open invoice (20 days past due): 100 000 HUF (0% for clean test math)
        Invoice iHuf = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, partner.id(),
                partner.name(), partner.taxNumber(), partner.address(), partner.email(),
                "Domestic HUF Batch", new BigDecimal("50"), "kg", new BigDecimal("2000"),
                BigDecimal.ZERO, "AAM-0", "HUF",
                asOf.minusDays(34), asOf.minusDays(20), InvoiceStatus.OVERDUE, adminId);

        List<Invoice> batch = List.of(iCurrent, i15, i45, i75, iHuf);
        Map<String, Integer> dec = ctx.getFinanceConfigDao().decimalPlacesMap();
        PartnerAgingStatement stmt = ctx.getPartnerStatementService().computeStatement(partner, batch, dec, asOf);

        PartnerAgingStatement.CurrencyAging eur = stmt.byCurrency().get("EUR");
        assertEquals(0, new BigDecimal("100.00").compareTo(eur.currentNotDue()));
        assertEquals(0, new BigDecimal("200.00").compareTo(eur.overdue1to30()));
        assertEquals(0, new BigDecimal("300.00").compareTo(eur.overdue31to60()));
        assertEquals(0, new BigDecimal("400.00").compareTo(eur.overdue60Plus()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(eur.totalOpen()));
        assertEquals(4, eur.openInvoiceCount());

        PartnerAgingStatement.CurrencyAging huf = stmt.byCurrency().get("HUF");
        assertEquals(0, new BigDecimal("100000").compareTo(huf.overdue1to30()));
        assertEquals(0, new BigDecimal("100000").compareTo(huf.totalOpen()));
        assertEquals(1, huf.openInvoiceCount());

        // Export Brutalist A4 PDF statement
        Path pdf = tempDir.resolve("partner-aging-statement.pdf");
        ctx.getPartnerStatementService().export(stmt, batch, dec, pdf);
        assertTrue(Files.exists(pdf) && Files.size(pdf) > 2000);
    }
}
