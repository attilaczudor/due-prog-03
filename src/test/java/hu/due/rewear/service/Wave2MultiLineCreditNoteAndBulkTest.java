package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.FinancialSummary;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceItem;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.StockValuation;
import hu.due.rewear.model.WarehouseLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Wave2MultiLineCreditNoteAndBulkTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private long adminId;

    @BeforeEach
    void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("wave2-test.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        ctx = new AppContext(db, prefs, new I18nManager());
        adminId = ctx.getUserDao().findByUsername("admin").orElseThrow().id();
    }

    @Test
    void createsMultiLineManualInvoiceWithAccurateTotals() {
        List<InvoiceService.InvoiceLineInput> lines = List.of(
                new InvoiceService.InvoiceLineInput("Sorted Ladies T-Shirts (Grade A)", new BigDecimal("100"), "kg", new BigDecimal("4.50")),
                new InvoiceService.InvoiceLineInput("Pallet & Stretch Wrap", new BigDecimal("2"), "db", new BigDecimal("15.00")),
                new InvoiceService.InvoiceLineInput("EU Freight Dispatch", new BigDecimal("1"), "db", new BigDecimal("120.00"))
        );

        Invoice inv = ctx.getInvoiceService().createMultiLineManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "Alpine Second-Hand GmbH", "ATU68219034", "1010 Wien", "office@alpine.at",
                lines, BigDecimal.ZERO, "EU-0", "EUR",
                LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.ISSUED, adminId);

        List<InvoiceItem> persisted = ctx.getInvoiceService().findItems(inv.id());
        assertEquals(3, persisted.size());
        // 450.00 + 30.00 + 120.00 = 600.00 EUR net, 0% VAT -> 600.00 EUR gross
        assertEquals(0, new BigDecimal("600.00").compareTo(inv.netAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(inv.vatAmount()));
        assertEquals(0, new BigDecimal("600.00").compareTo(inv.grossAmount()));
    }

    @Test
    void issuesCreditNotesForUnpaidAndPaidInvoicesAndExportsPdf() throws Exception {
        // 1. Unpaid ISSUED invoice -> original becomes CANCELLED, CN is CANCELLED (printable reversal)
        Invoice unpaid = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "Test Buyer Kft.", "11111111-2-07", "2400 Dunaújváros", "buyer@test.hu",
                "Mixed Bales", new BigDecimal("10"), "kg", new BigDecimal("2000"),
                new BigDecimal("27"), "HU-27", "HUF",
                LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.ISSUED, adminId);

        Invoice cnUnpaid = ctx.getInvoiceService().issueCreditNote(unpaid.id(), adminId);
        assertTrue(cnUnpaid.invoiceNumber().startsWith("CN-"));
        assertEquals(InvoiceCategory.CREDIT_NOTE, cnUnpaid.category());
        assertEquals(InvoiceStatus.CANCELLED, ctx.getInvoiceDao().findById(unpaid.id()).orElseThrow().status());
        assertEquals(InvoiceStatus.CANCELLED, cnUnpaid.status());

        Path cnPdf = tempDir.resolve("credit-note.pdf");
        ctx.getInvoicePdfExporter().export(cnUnpaid, ctx.getInvoiceService().findItems(cnUnpaid.id()), cnPdf);
        assertTrue(Files.exists(cnPdf) && Files.size(cnPdf) > 2000);

        // 2. PAID outgoing USD invoice -> original stays PAID, CN is INCOMING+PAID so USD ledger nets to 0
        Invoice paidUsd = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "US Vintage LLC", "US-998877", "New York, NY", "ap@usvintage.com",
                "Cream Denim", new BigDecimal("50"), "kg", new BigDecimal("10.00"),
                BigDecimal.ZERO, "EXPORT-0", "USD",
                LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.PAID, adminId);

        Invoice cnPaid = ctx.getInvoiceService().issueCreditNote(paidUsd.id(), adminId);
        assertEquals(InvoiceStatus.PAID, ctx.getInvoiceDao().findById(paidUsd.id()).orElseThrow().status());
        assertEquals(InvoiceDirection.INCOMING, cnPaid.direction());
        assertEquals(InvoiceStatus.PAID, cnPaid.status());
        assertThrows(IllegalStateException.class, () -> ctx.getInvoiceService().issueCreditNote(paidUsd.id(), adminId));

        FinancialSummary summary = ctx.getInvoiceService().computeSummary(List.of(paidUsd, cnPaid));
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.forCurrency("USD", 2).netBalance()));
    }

    @Test
    void computesLiveStockValuationAndExportsFinancialPnlPdf() throws Exception {
        StockValuation val = ctx.getPackageDao().sumInStockValuation();
        assertTrue(val.packageCount() > 0);
        assertTrue(val.totalWeightKg().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(val.totalEur().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(val.totalHuf().compareTo(BigDecimal.ZERO) > 0);

        List<Invoice> invoices = ctx.getInvoiceDao().findAll();
        FinancialSummary summary = ctx.getInvoiceService().computeSummary(invoices);
        Path reportPdf = tempDir.resolve("financial-pnl-report.pdf");
        ctx.getFinancialReportPdfExporter().export("ALL", summary, val, invoices,
                ctx.getFinanceConfigDao().decimalPlacesMap(), reportPdf);
        assertTrue(Files.exists(reportPdf) && Files.size(reportPdf) > 2000);
    }

    @Test
    void bulkRelocatesMultiplePackagesAtomicallyAndRollsBackOnShipped() {
        List<WarehouseLocation> locs = ctx.getLocationDao().findAll(true);
        WarehouseLocation targetLoc = locs.get(locs.size() - 1);

        List<InventoryPackage> inStock = ctx.getPackageDao().findByFilter(null).stream()
                .filter(p -> p.status() == PackageStatus.IN_STOCK && p.locationId() != targetLoc.id())
                .limit(3)
                .toList();
        assertTrue(inStock.size() >= 2);

        // Include a SHIPPED package -> entire transaction must roll back with IllegalStateException
        InventoryPackage shipped = ctx.getPackageDao().findByFilter(null).stream()
                .filter(p -> p.status() == PackageStatus.SHIPPED)
                .findFirst().orElseThrow();
        List<Long> badBatch = List.of(inStock.get(0).id(), shipped.id());
        long originalLocId = inStock.get(0).locationId();

        assertThrows(IllegalStateException.class,
                () -> ctx.getInventoryService().relocatePackages(badBatch, targetLoc.id(), "Should rollback", adminId));
        assertEquals(originalLocId, ctx.getPackageDao().findById(inStock.get(0).id()).orElseThrow().locationId());

        // Valid multi-package bulk relocate succeeds atomically
        List<Long> validIds = inStock.stream().map(InventoryPackage::id).toList();
        int moved = ctx.getInventoryService().relocatePackages(validIds, targetLoc.id(), "Rack consolidation", adminId);
        assertEquals(validIds.size(), moved);
        for (Long id : validIds) {
            assertEquals(targetLoc.id(), ctx.getPackageDao().findById(id).orElseThrow().locationId());
        }
    }
}
