package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.FinancialSummary;
import hu.due.rewear.model.GarmentType;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiCurrencyPricingAndPnlTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private long userId;

    @BeforeEach
    void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("multicurrency-test.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("prefs.properties"));
        I18nManager i18n = new I18nManager();
        i18n.setLanguage("hu");
        ctx = new AppContext(db, prefs, i18n);
        userId = ctx.getUserDao().findByUsername("superadmin").orElseThrow().id();
    }

    @Test
    void usesDualCurrencyProductPricesOnOrdersAndSupportsSwitchingOrderCurrency() throws Exception {
        // Verify TSH product has both EUR/kg and HUF/kg configured
        GarmentType tsh = ctx.getDictionaryDao().findGarmentTypes(true).stream()
                .filter(g -> "TSH".equals(g.code()))
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("4.50").compareTo(tsh.priceEurPerKg()));
        assertEquals(0, new BigDecimal("1800").compareTo(tsh.priceHufPerKg()));

        // Pick an IN_STOCK TSH package
        InventoryPackage tshPkg = ctx.getPackageDao().findByFilter(
                        new hu.due.rewear.model.PackageFilter(null, PackageStatus.IN_STOCK, null, null, null, null, null))
                .stream()
                .filter(p -> "TSH".equals(p.garmentTypeCode()))
                .findFirst().orElseThrow();

        Customer cust = ctx.getCustomerDao().findAll(true).get(0);

        // 1. Create a HUF order and add the TSH package -> priced at 1800 HUF/kg (0 decimals)
        Order hufOrder = ctx.getOrderService().createOrder(cust.id(), CurrencyCode.HUF, null, userId);
        assertEquals(CurrencyCode.HUF, hufOrder.effectiveCurrency());

        hufOrder = ctx.getOrderService().addPackageToOrder(hufOrder.id(), tshPkg.id(), null, userId);
        BigDecimal expectedHufTotal = CurrencyCode.HUF.round(tshPkg.weightKg().multiply(new BigDecimal("1800")));
        assertEquals(0, expectedHufTotal.compareTo(hufOrder.totalPrice()));

        List<OrderItem> hufItems = ctx.getOrderItemDao().findByOrderId(hufOrder.id());
        assertEquals(1, hufItems.size());
        assertEquals(0, new BigDecimal("1800").compareTo(hufItems.get(0).pricePerKg()));

        // Ensure outgoing invoice inherits HUF currency
        Invoice hufInv = ctx.getInvoiceService().ensureInvoiceForOrder(hufOrder.id(), new BigDecimal("27"), userId);
        assertEquals(CurrencyCode.HUF, hufInv.currencyCode());
        assertEquals(0, expectedHufTotal.compareTo(hufInv.netAmount()));

        // Export HUF Delivery Note + HUF Invoice PDF
        Path hufPdf = tempDir.resolve("huf-docs.pdf");
        InvoicePdfExporter.exportCombined(List.of(
                ctx.getDeliveryNotePdfExporter().createPageSource(hufOrder, cust, hufItems),
                ctx.getInvoicePdfExporter().createPageSource(hufInv, ctx.getInvoiceService().findItems(hufInv.id()))
        ), hufPdf);
        assertTrue(Files.exists(hufPdf));
        assertEquals("%PDF-", new String(Files.readAllBytes(hufPdf), 0, 5, StandardCharsets.US_ASCII));

        // 2. Switch open order from HUF -> EUR -> unit price recalculates to 4.50 EUR/kg
        Order eurOrder = ctx.getOrderService().changeOrderCurrency(hufOrder.id(), CurrencyCode.EUR);
        assertEquals(CurrencyCode.EUR, eurOrder.effectiveCurrency());
        BigDecimal expectedEurTotal = CurrencyCode.EUR.round(tshPkg.weightKg().multiply(new BigDecimal("4.50")));
        assertEquals(0, expectedEurTotal.compareTo(eurOrder.totalPrice()));

        // Syncing existing ISSUED invoice updates it to EUR too
        Invoice updatedInv = ctx.getInvoiceService().ensureInvoiceForOrder(eurOrder.id(), new BigDecimal("27"), userId);
        assertEquals(CurrencyCode.EUR, updatedInv.currencyCode());
        assertEquals(0, expectedEurTotal.compareTo(updatedInv.netAmount()));
    }

    @Test
    void consolidatesMixedEurAndHufInvoicesAccuratelyInBothReportingCurrencies() {
        // Set deterministic FX rate: 1 EUR = 400.00 HUF
        ctx.getInvoiceService().updateEurToHufRate(new BigDecimal("400.00"));

        // Create 1 EUR outgoing income (+100 EUR gross) + 1 HUF outgoing income (+80 000 HUF gross)
        Invoice eurIncome = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, "TEST-OUT-EUR",
                "Export Buyer GmbH", null, null, null, "Export Lot",
                BigDecimal.ONE, "lot", new BigDecimal("100.00"), BigDecimal.ZERO, CurrencyCode.EUR,
                LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.PAID, userId);

        Invoice hufIncome = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, "TEST-OUT-HUF",
                "Hazai Vevő Kft.", null, null, null, "Belföldi szállítmány",
                BigDecimal.ONE, "tétel", new BigDecimal("80000"), BigDecimal.ZERO, CurrencyCode.HUF,
                LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.ISSUED, userId);

        // Create 1 EUR incoming expense (-50 EUR gross) + 1 HUF incoming expense (-40 000 HUF gross)
        Invoice eurExpense = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.INCOMING, InvoiceCategory.RAW_STOCK_PURCHASE, "TEST-IN-EUR",
                "Import Supplier GmbH", null, null, null, "Raw Textiles",
                BigDecimal.ONE, "lot", new BigDecimal("50.00"), BigDecimal.ZERO, CurrencyCode.EUR,
                LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.PAID, userId);

        Invoice hufExpense = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.INCOMING, InvoiceCategory.WAREHOUSE_RENT, "TEST-IN-HUF",
                "Raktár Bérbeadó Kft.", null, null, null, "Bérleti díj",
                BigDecimal.ONE, "hó", new BigDecimal("40000"), BigDecimal.ZERO, CurrencyCode.HUF,
                LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.ISSUED, userId);

        List<Invoice> subset = List.of(eurIncome, hufIncome, eurExpense, hufExpense);

        // Reporting in HUF:
        // Income = 100*400 + 80000 = 120 000 HUF
        // Expenses = 50*400 + 40000 = 60 000 HUF
        // Net Balance = +60 000 HUF
        FinancialSummary inHuf = ctx.getInvoiceService().computeSummary(subset, CurrencyCode.HUF, new BigDecimal("400.00"));
        assertEquals(CurrencyCode.HUF, inHuf.reportingCurrency());
        assertEquals(0, new BigDecimal("120000").compareTo(inHuf.totalIncome()));
        assertEquals(0, new BigDecimal("60000").compareTo(inHuf.totalExpenses()));
        assertEquals(0, new BigDecimal("60000").compareTo(inHuf.netBalance()));
        assertEquals(0, new BigDecimal("100.00").compareTo(inHuf.incomeEur()));
        assertEquals(0, new BigDecimal("80000").compareTo(inHuf.incomeHuf()));
        assertEquals(0, new BigDecimal("50.00").compareTo(inHuf.expenseEur()));
        assertEquals(0, new BigDecimal("40000").compareTo(inHuf.expenseHuf()));

        // Reporting in EUR:
        // Income = 100 + 80000/400 = 300.00 EUR
        // Expenses = 50 + 40000/400 = 150.00 EUR
        // Net Balance = +150.00 EUR
        FinancialSummary inEur = ctx.getInvoiceService().computeSummary(subset, CurrencyCode.EUR, new BigDecimal("400.00"));
        assertEquals(CurrencyCode.EUR, inEur.reportingCurrency());
        assertEquals(0, new BigDecimal("300.00").compareTo(inEur.totalIncome()));
        assertEquals(0, new BigDecimal("150.00").compareTo(inEur.totalExpenses()));
        assertEquals(0, new BigDecimal("150.00").compareTo(inEur.netBalance()));
    }
}
