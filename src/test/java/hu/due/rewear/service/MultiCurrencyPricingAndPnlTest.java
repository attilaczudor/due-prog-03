package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.CurrencyDef;
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
import hu.due.rewear.model.PartnerType;
import hu.due.rewear.model.TaxType;
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
    void appliesCountryBasedVatAndSupportsDynamicCurrenciesAndBuyersVsProviders() throws Exception {
        // 1. Verify buyers vs providers separation
        List<Customer> buyers = ctx.getCustomerDao().findBuyers(true);
        List<Customer> suppliers = ctx.getCustomerDao().findSuppliers(true);
        assertTrue(buyers.stream().anyMatch(c -> "HU".equals(c.effectiveCountryCode())));
        assertTrue(buyers.stream().anyMatch(c -> "AT".equals(c.effectiveCountryCode())));
        assertTrue(suppliers.stream().anyMatch(c -> "DE".equals(c.effectiveCountryCode())));
        assertTrue(buyers.stream().allMatch(c -> c.effectivePartnerType().isBuyer()));
        assertTrue(suppliers.stream().allMatch(c -> c.effectivePartnerType().isSupplier()));

        // 2. Verify automatic VAT rules by country: Hungary -> 27% (HU-27), Austria -> 0% (EU-0), US -> 0% (EXPORT-0)
        Customer huBuyer = buyers.stream().filter(c -> "HU".equals(c.effectiveCountryCode())).findFirst().orElseThrow();
        Customer atBuyer = buyers.stream().filter(c -> "AT".equals(c.effectiveCountryCode())).findFirst().orElseThrow();

        TaxType huTax = ctx.getInvoiceService().resolveDefaultTaxForCustomer(huBuyer);
        TaxType atTax = ctx.getInvoiceService().resolveDefaultTaxForCustomer(atBuyer);
        TaxType usTax = ctx.getFinanceConfigDao().resolveDefaultTaxTypeForCountry("US");

        assertEquals("HU-27", huTax.code());
        assertEquals(0, new BigDecimal("27").compareTo(huTax.ratePercent()));
        assertEquals("EU-0", atTax.code());
        assertEquals(0, BigDecimal.ZERO.compareTo(atTax.ratePercent()));
        assertEquals("EXPORT-0", usTax.code());
        assertEquals(0, BigDecimal.ZERO.compareTo(usTax.ratePercent()));

        // 3. Add a custom currency (GBP) and a custom tax type in Invoice Settings
        ctx.getFinanceConfigDao().insertCurrency("GBP", "British Pound", "Angol font", "£", 2);
        assertTrue(ctx.getFinanceConfigDao().findCurrencyByCode("GBP").isPresent());

        // 4. Create an Austrian EUR order -> auto-invoice gets 0% VAT (EU-0)
        List<InventoryPackage> inStock = ctx.getPackageDao().findByFilter(
                new hu.due.rewear.model.PackageFilter(null, PackageStatus.IN_STOCK, null, null, null, null, null));
        InventoryPackage pkgAt = inStock.stream().filter(p -> "TSH".equals(p.garmentTypeCode())).findFirst().orElseThrow();
        InventoryPackage pkgHu = inStock.stream().filter(p -> p.id() != pkgAt.id() && "TSH".equals(p.garmentTypeCode())).findFirst().orElseThrow();

        GarmentType tsh = ctx.getDictionaryDao().findGarmentTypes(true).stream()
                .filter(g -> "TSH".equals(g.code())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("4.50").compareTo(tsh.priceEurPerKg()));
        assertEquals(0, new BigDecimal("1800").compareTo(tsh.priceHufPerKg()));

        Order atOrder = ctx.getOrderService().createOrder(atBuyer.id(), "EUR", null, userId);
        atOrder = ctx.getOrderService().addPackageToOrder(atOrder.id(), pkgAt.id(), null, userId);
        Invoice atInv = ctx.getInvoiceService().ensureInvoiceForOrder(atOrder.id(), userId);
        assertEquals("EUR", atInv.effectiveCurrencyCode());
        assertEquals("EU-0", atInv.effectiveTaxTypeCode());
        assertEquals(0, BigDecimal.ZERO.compareTo(atInv.vatAmount()));
        assertEquals(0, atInv.netAmount().compareTo(atInv.grossAmount()));

        // 5. Create a Hungarian HUF order -> auto-invoice gets 27% VAT (HU-27)
        Order huOrder = ctx.getOrderService().createOrder(huBuyer.id(), "HUF", null, userId);
        huOrder = ctx.getOrderService().addPackageToOrder(huOrder.id(), pkgHu.id(), null, userId);
        BigDecimal expectedHuNet = CurrencyDef.roundForScale(pkgHu.weightKg().multiply(new BigDecimal("1800")), 0);
        assertEquals(0, expectedHuNet.compareTo(huOrder.totalPrice()));

        Invoice huInv = ctx.getInvoiceService().ensureInvoiceForOrder(huOrder.id(), userId);
        assertEquals("HUF", huInv.effectiveCurrencyCode());
        assertEquals("HU-27", huInv.effectiveTaxTypeCode());
        assertTrue(huInv.vatAmount().compareTo(BigDecimal.ZERO) > 0);
        assertEquals(0, huInv.netAmount().add(huInv.vatAmount()).compareTo(huInv.grossAmount()));

        // Export both PDFs cleanly
        Path pdf = tempDir.resolve("at-and-hu-invoices.pdf");
        InvoicePdfExporter.exportCombined(List.of(
                ctx.getInvoicePdfExporter().createPageSource(atInv, ctx.getInvoiceService().findItems(atInv.id())),
                ctx.getInvoicePdfExporter().createPageSource(huInv, ctx.getInvoiceService().findItems(huInv.id()))
        ), pdf);
        assertTrue(Files.exists(pdf));
        assertEquals("%PDF-", new String(Files.readAllBytes(pdf), 0, 5, StandardCharsets.US_ASCII));
    }

    @Test
    void keepsEurHufAndUsdLedgersStrictlySeparateWithoutCrossCurrencyConversion() {
        TaxType tax0 = ctx.getFinanceConfigDao().findTaxTypeByCode("EU-0").orElseThrow();
        TaxType tax27 = ctx.getFinanceConfigDao().findTaxTypeByCode("HU-27").orElseThrow();

        Invoice eurIncome = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, "SEP-OUT-EUR", null,
                "Alpine GmbH", "ATU68219034", "Wien", "office@alpine.at",
                "Export Lot", BigDecimal.ONE, "lot", new BigDecimal("500.00"), tax0, "EUR",
                LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.PAID, userId);

        Invoice eurExpense = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.INCOMING, InvoiceCategory.RAW_STOCK_PURCHASE, "SEP-IN-EUR", null,
                "EuroTex GmbH", "DE294810221", "München", "billing@eurotex.de",
                "Import Lot", BigDecimal.ONE, "lot", new BigDecimal("200.00"), tax0, "EUR",
                LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.PAID, userId);

        Invoice hufIncome = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, "SEP-OUT-HUF", null,
                "Duna Vintage Kft.", "24891234-2-07", "Dunaújváros", "marta@dunavintage.hu",
                "Belföldi tétel", BigDecimal.ONE, "tétel", new BigDecimal("100000"), tax27, "HUF",
                LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.ISSUED, userId);

        Invoice usdIncome = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, "SEP-OUT-USD", null,
                "US Vintage LLC", "US-991822", "New York", "buy@usvintage.com",
                "Export Pallet", BigDecimal.ONE, "pallet", new BigDecimal("750.00"), tax0, "USD",
                LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.ISSUED, userId);

        // Also test creating a US partner with USD currency
        long usPartnerId = ctx.getCustomerDao().insert("US Vintage LLC", PartnerType.CUSTOMER, "John Doe",
                "buy@usvintage.com", "+1 555 0100", "New York", "US-991822", "US", "USD");
        assertEquals("USD", ctx.getCustomerDao().findById(usPartnerId).orElseThrow().effectiveCurrencyCode());

        FinancialSummary summary = ctx.getInvoiceService().computeSummary(
                List.of(eurIncome, eurExpense, hufIncome, usdIncome));

        // EUR bucket: +500.00 income, -200.00 expense, +300.00 net
        var eur = summary.forCurrency("EUR", 2);
        assertEquals(0, new BigDecimal("500.00").compareTo(eur.totalIncome()));
        assertEquals(0, new BigDecimal("200.00").compareTo(eur.totalExpenses()));
        assertEquals(0, new BigDecimal("300.00").compareTo(eur.netBalance()));

        // HUF bucket: +127 000 gross income (100 000 + 27% VAT), 0 expense, +127 000 net
        var huf = summary.forCurrency("HUF", 0);
        assertEquals(0, new BigDecimal("127000").compareTo(huf.totalIncome()));
        assertEquals(0, BigDecimal.ZERO.compareTo(huf.totalExpenses()));
        assertEquals(0, new BigDecimal("127000").compareTo(huf.netBalance()));

        // USD bucket: +750.00 income, 0 expense, +750.00 net
        var usd = summary.forCurrency("USD", 2);
        assertEquals(0, new BigDecimal("750.00").compareTo(usd.totalIncome()));
        assertEquals(0, new BigDecimal("750.00").compareTo(usd.netBalance()));
    }
}
