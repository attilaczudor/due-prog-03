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
