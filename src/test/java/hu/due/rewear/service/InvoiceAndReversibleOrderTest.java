package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.FinancialSummary;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.model.PartnerType;
import hu.due.rewear.model.PackageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceAndReversibleOrderTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private long userId;

    @BeforeEach
    void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("inv-order-test.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("prefs.properties"));
        I18nManager i18n = new I18nManager();
        i18n.setLanguage("hu");
        ctx = new AppContext(db, prefs, i18n);
        userId = ctx.getUserDao().findByUsername("superadmin").orElseThrow().id();
    }

    @Test
    void manualInvoicesSavePartnerVatAndReceiptTogether() throws Exception {
        var service = ctx.getInvoiceService();
        var vault = ctx.getReceiptVaultService();
        var tax = ctx.getFinanceConfigDao().findTaxTypeByCode("HU-5").orElseThrow();
        var supplier = new InvoiceService.ManualPartner(null, "Manual supplier", "TEST-2345", "", "", "HU", false);
        Path receipt = Files.writeString(tempDir.resolve("receipt.pdf"), "%PDF-1.4\n", StandardCharsets.US_ASCII);
        var today = LocalDate.now();
        var expense = new InvoiceService.ManualEntry(InvoiceDirection.INCOMING, InvoiceCategory.OTHER_EXPENSE,
                "EXP-RECEIPT-1", supplier, List.of(), new BigDecimal("100.00"), tax, "EUR",
                today, today, InvoiceStatus.ISSUED, userId, receipt);
        Invoice saved = service.saveManualEntry(expense, vault);
        assertEquals(PartnerType.SUPPLIER, ctx.getCustomerDao().findById(saved.customerId()).orElseThrow().partnerType());
        assertEquals("HU-5", saved.effectiveTaxTypeCode());
        assertEquals(0, new BigDecimal("105.00").compareTo(saved.grossAmount()));
        assertTrue(vault.resolveAttachment(saved).isPresent());
        assertEquals(1, service.findItems(saved.id()).size());
        assertEquals(1, ctx.getInvoiceArchiveService().countInvoices());

        long totalPartners = ctx.getCustomerDao().findAll(false).size();
        var other = new InvoiceService.ManualPartner(null, "Discarded supplier", "TEST-6789", "", "", "HU", false);
        var duplicate = new InvoiceService.ManualEntry(expense.direction(), expense.category(), expense.invoiceNumber(),
                other, List.of(), BigDecimal.TEN, tax, "EUR", today, today, expense.status(), userId, receipt);
        assertThrows(hu.due.rewear.dao.DaoException.class, () -> service.saveManualEntry(duplicate, vault));
        assertEquals(totalPartners, ctx.getCustomerDao().findAll(false).size());
        assertEquals(1, vault.countVaultFiles());

        var archiveFailure = new InvoiceService.ManualEntry(expense.direction(), expense.category(), "EXP-ARCHIVE-FAIL",
                other, List.of(), BigDecimal.TEN, tax, "EUR", today, today, expense.status(), userId, receipt);
        try (var conn = ctx.getDatabaseManager().getConnection(); var statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER reject_pdf_path BEFORE UPDATE OF archived_pdf_path ON invoices
                    BEGIN SELECT RAISE(ABORT, 'Simulated archive link failure'); END
                    """);
            assertThrows(hu.due.rewear.dao.DaoException.class, () -> service.saveManualEntry(archiveFailure, vault));
            statement.execute("DROP TRIGGER reject_pdf_path");
        }
        assertEquals(totalPartners, ctx.getCustomerDao().findAll(false).size());
        assertEquals(1, ctx.getInvoiceArchiveService().countInvoices());
        assertEquals(1, vault.countVaultFiles());

        Path badFile = Files.writeString(tempDir.resolve("invalid.pdf"), "not a PDF");
        var invalid = new InvoiceService.ManualEntry(expense.direction(), expense.category(), "EXP-INVALID",
                other, List.of(), BigDecimal.TEN, tax, "EUR", today, today, expense.status(), userId, badFile);
        assertThrows(java.io.IOException.class, () -> service.saveManualEntry(invalid, vault));
        assertEquals(totalPartners, ctx.getCustomerDao().findAll(false).size());
        assertEquals(1, vault.countVaultFiles());

        var existing = new InvoiceService.ManualPartner(saved.customerId(), supplier.name(), supplier.taxNumber(),
                supplier.address(), supplier.email(), supplier.country(), true);
        var income = new InvoiceService.ManualEntry(InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES,
                null, existing, List.of(new InvoiceService.InvoiceLineInput("Item", BigDecimal.ONE, "db", BigDecimal.TEN)),
                null, tax, "EUR", today, today, InvoiceStatus.ISSUED, userId, null);
        Invoice linked = service.saveManualEntry(income, vault);
        assertEquals(saved.customerId(), linked.customerId());
        assertEquals(PartnerType.BOTH, ctx.getCustomerDao().findById(linked.customerId()).orElseThrow().partnerType());
    }

    @Test
    void orderInvoiceRequiresLatestReviewedLinesAndReusesExistingInvoice() {
        var partner = ctx.getCustomerDao().findBuyers(true).get(0);
        var order = ctx.getOrderService().createOrder(partner.id(), "EUR", null, userId);
        var pkg = ctx.getPackageDao().findByFilter(null).stream().filter(p -> p.status() == PackageStatus.IN_STOCK)
                .findFirst().orElseThrow();
        ctx.getOrderService().addPackageToOrder(order.id(), pkg.id(), BigDecimal.TEN, userId);
        var service = ctx.getInvoiceService();
        var reviewed = service.reviewOrder(order.id());
        ctx.getOrderService().updateOrderItemPrice(order.id(), reviewed.items().get(0).id(), new BigDecimal("12.75"));
        assertThrows(InvoiceService.OrderChangedException.class, () -> service.saveReviewedOrder(reviewed, null, userId));
        assertTrue(ctx.getInvoiceDao().findByOrderId(order.id()).isEmpty());
        var latest = service.reviewOrder(order.id());
        Invoice saved = service.saveReviewedOrder(latest, null, userId);
        assertEquals(partner.id(), saved.customerId());
        assertEquals(0, latest.items().get(0).lineTotal().compareTo(saved.netAmount()));
        assertEquals(saved.id(), service.saveReviewedOrder(latest, null, userId).id());
        assertEquals(1, ctx.getInvoiceArchiveService().countInvoices());
    }

    @Test
    void supportsReversibleOrderStatusTransitionsIncludingUndoShipAndRestoreCancelled() {
        Order packingOrder = ctx.getOrderDao().findAll().stream()
                .filter(o -> o.status() == OrderStatus.PACKING)
                .findFirst().orElseThrow();
        List<OrderItem> items = ctx.getOrderItemDao().findByOrderId(packingOrder.id());
        assertTrue(!items.isEmpty());

        // 1. Ship the order -> packages become SHIPPED
        Order shipped = ctx.getOrderService().changeOrderStatus(packingOrder.id(), OrderStatus.SHIPPED, userId);
        assertEquals(OrderStatus.SHIPPED, shipped.status());
        assertNotNull(shipped.shippedAt());
        for (OrderItem oi : items) {
            InventoryPackage p = ctx.getPackageDao().findById(oi.packageId()).orElseThrow();
            assertEquals(PackageStatus.SHIPPED, p.status());
        }

        // 2. Undo shipment -> revert SHIPPED back to PACKING -> packages return to RESERVED
        Order reverted = ctx.getOrderService().changeOrderStatus(packingOrder.id(), OrderStatus.PACKING, userId);
        assertEquals(OrderStatus.PACKING, reverted.status());
        assertNull(reverted.shippedAt());
        for (OrderItem oi : items) {
            InventoryPackage p = ctx.getPackageDao().findById(oi.packageId()).orElseThrow();
            assertEquals(PackageStatus.RESERVED, p.status());
        }

        // 3. Cancel order -> packages released back to IN_STOCK
        Order cancelled = ctx.getOrderService().changeOrderStatus(packingOrder.id(), OrderStatus.CANCELLED, userId);
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        for (OrderItem oi : items) {
            InventoryPackage p = ctx.getPackageDao().findById(oi.packageId()).orElseThrow();
            assertEquals(PackageStatus.IN_STOCK, p.status());
        }

        // 4. Restore cancelled order back to DRAFT -> packages re-reserved cleanly
        Order restored = ctx.getOrderService().changeOrderStatus(packingOrder.id(), OrderStatus.DRAFT, userId);
        assertEquals(OrderStatus.DRAFT, restored.status());
        for (OrderItem oi : items) {
            InventoryPackage p = ctx.getPackageDao().findById(oi.packageId()).orElseThrow();
            assertEquals(PackageStatus.RESERVED, p.status());
        }
    }

    @Test
    void managesOutgoingAndIncomingInvoicesCalculatesPnlAndExportsCombinedPdf() throws Exception {
        // Seeded starter invoices should exist
        List<Invoice> initial = ctx.getInvoiceDao().findAll();
        assertTrue(initial.size() >= 5, "Starter outgoing + incoming invoices should be seeded");

        // Create a manual incoming expense invoice
        Invoice expense = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.INCOMING,
                InvoiceCategory.TRANSPORT_LOGISTICS,
                null,
                "TransEuro Freight Kft.",
                "HU-12345678-2-41",
                "1117 Budapest, Budafoki út 56.",
                "billing@transeuro.hu",
                "Pallet freight Budapest -> Dunaújváros",
                BigDecimal.ONE,
                "fuvar",
                new BigDecimal("100.00"),
                new BigDecimal("27.00"),
                LocalDate.now(),
                LocalDate.now().plusDays(14),
                InvoiceStatus.ISSUED,
                userId
        );
        assertEquals(new BigDecimal("127.00"), expense.grossAmount());
        assertEquals(InvoiceStatus.ISSUED, expense.status());

        // Mark paid then revert back to ISSUED
        Invoice paid = ctx.getInvoiceService().changeStatus(expense.id(), InvoiceStatus.PAID);
        assertEquals(InvoiceStatus.PAID, paid.status());
        assertNotNull(paid.paidDate());

        Invoice reverted = ctx.getInvoiceService().changeStatus(expense.id(), InvoiceStatus.ISSUED);
        assertEquals(InvoiceStatus.ISSUED, reverted.status());
        assertNull(reverted.paidDate());

        // Financial summary math check (separate per-currency ledgers)
        FinancialSummary summary = ctx.getInvoiceService().computeSummary(ctx.getInvoiceDao().findAll());
        var eurTotals = summary.forCurrency("EUR", 2);
        var hufTotals = summary.forCurrency("HUF", 0);
        assertTrue(eurTotals.totalIncome().compareTo(BigDecimal.ZERO) > 0
                || hufTotals.totalIncome().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(eurTotals.totalExpenses().compareTo(BigDecimal.ZERO) > 0);
        assertEquals(eurTotals.totalIncome().subtract(eurTotals.totalExpenses()), eurTotals.netBalance());
        assertEquals(hufTotals.totalIncome().subtract(hufTotals.totalExpenses()), hufTotals.netBalance());

        // Combined Delivery Note + Invoice A4 PDF export
        Order order = ctx.getOrderDao().findAll().stream()
                .filter(o -> !ctx.getOrderItemDao().findByOrderId(o.id()).isEmpty())
                .findFirst().orElseThrow();
        Customer cust = ctx.getCustomerDao().findById(order.customerId()).orElseThrow();
        Invoice orderInv = ctx.getInvoiceService().ensureInvoiceForOrder(order.id(), InvoiceService.DEFAULT_VAT_RATE, userId);

        DocumentPageSource dnSrc = ctx.getDeliveryNotePdfExporter()
                .createPageSource(order, cust, ctx.getOrderItemDao().findByOrderId(order.id()));
        DocumentPageSource invSrc = ctx.getInvoicePdfExporter()
                .createPageSource(orderInv, ctx.getInvoiceService().findItems(orderInv.id()));

        Path combinedPdf = tempDir.resolve("combined-order-docs.pdf");
        InvoicePdfExporter.exportCombined(List.of(dnSrc, invSrc), combinedPdf);
        assertTrue(Files.exists(combinedPdf));
        byte[] bytes = Files.readAllBytes(combinedPdf);
        assertTrue(bytes.length > 5000);
        assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.US_ASCII));
    }
}
