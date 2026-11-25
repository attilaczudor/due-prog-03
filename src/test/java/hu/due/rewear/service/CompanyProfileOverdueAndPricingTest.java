package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CompanyProfile;
import hu.due.rewear.model.Customer;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyProfileOverdueAndPricingTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private long adminId;

    @BeforeEach
    void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("v21-test.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        ctx = new AppContext(db, prefs, new I18nManager());
        adminId = ctx.getUserDao().findByUsername("admin").orElseThrow().id();
    }

    @Test
    void persistsCompanyProfileAndPerCurrencyIbansAndExportsPdf() throws Exception {
        ctx.getSettingsDao().set("company.address", "2400 Dunaújváros, Régi út 12.");
        assertEquals(new CompanyProfile.Address("Régi út 12.", "Dunaújváros", "2400"),
                ctx.getSettingsDao().getCompanyProfile().addressParts());
        CompanyProfile saved = new CompanyProfile(
                "Czudor Textile Export Kft.",
                "2400 Dunaújváros, Vasmű út 10.",
                "HU-12345678-2-07",
                "finance@czudortextile.hu",
                "+36 30 111 2222",
                Map.of(
                        "HUF", "HU11 1177 3061 0000 1111 2222 3333",
                        "EUR", "HU22 1177 3061 0000 4444 5555 6666",
                        "USD", "HU33 1177 3061 0000 7777 8888 9999"
                )
        );
        ctx.getSettingsDao().saveCompanyProfile(saved);

        CompanyProfile reloaded = ctx.getSettingsDao().getCompanyProfile();
        assertEquals("Czudor Textile Export Kft.", reloaded.name());
        assertEquals("HU-12345678-2-07", reloaded.taxNumber());
        assertEquals("HU11 1177 3061 0000 1111 2222 3333", reloaded.ibanForCurrency("HUF"));
        assertEquals("HU22 1177 3061 0000 4444 5555 6666", reloaded.ibanForCurrency("eur"));
        assertEquals("HU33 1177 3061 0000 7777 8888 9999", reloaded.ibanForCurrency("USD"));
        assertEquals("", reloaded.ibanForCurrency("GBP"));

        var updatedAddress = new CompanyProfile.Address("Vasmű út 10/A", "Dunaújváros", "2400");
        String updatedEmail = "invoices@czudortextile.hu";
        ctx.getSettingsDao().saveInvoiceCompanyProfile(saved.name(), updatedAddress, saved.taxNumber(),
                updatedEmail, Map.of("EUR", ""));
        CompanyProfile afterSettings = ctx.getSettingsDao().getCompanyProfile();
        assertEquals(updatedAddress, afterSettings.addressParts());
        assertEquals("2400 Dunaújváros, Vasmű út 10/A", afterSettings.address());
        assertEquals(updatedEmail, afterSettings.email());
        assertEquals(saved.phone(), afterSettings.phone());
        assertEquals("", afterSettings.ibanForCurrency("EUR"));
        assertEquals(saved.ibanForCurrency("USD"), afterSettings.ibanForCurrency("USD"));

        // Verify live PDF export uses the supplier cleanly without errors
        Invoice inv = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "Alpine Buyer GmbH", "ATU68219034", "1010 Wien", "ap@alpine.at",
                "Export Bales", new BigDecimal("100"), "kg", new BigDecimal("5.00"),
                BigDecimal.ZERO, "EU-0", "EUR",
                LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.ISSUED, adminId);

        Path pdf = tempDir.resolve("custom-company-invoice.pdf");
        ctx.getInvoicePdfExporter().export(inv, ctx.getInvoiceService().findItems(inv.id()), pdf);
        assertTrue(Files.exists(pdf) && Files.size(pdf) > 2000);
    }

    @Test
    void automaticallyMarksPastDueInvoicesOverdueAndBuildsReminderMailto() {
        // Past-due ISSUED invoice
        Invoice pastDue = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "Late Payer GmbH", "ATU99999999", "1020 Wien", "billing@latepayer.at",
                "Cream Summer Mix", new BigDecimal("50"), "kg", new BigDecimal("8.00"),
                BigDecimal.ZERO, "EU-0", "EUR",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), InvoiceStatus.ISSUED, adminId);

        // Already PAID invoice with past due date must stay PAID
        Invoice alreadyPaid = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "Good Payer Kft.", "11111111-2-07", "2400 Dunaújváros", "pay@goodpayer.hu",
                "Grade A Mix", new BigDecimal("20"), "kg", new BigDecimal("2000"),
                new BigDecimal("27"), "HU-27", "HUF",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), InvoiceStatus.PAID, adminId);

        int flagged = ctx.getInvoiceService().syncOverdueStatuses(LocalDate.of(2026, 11, 22));
        assertTrue(flagged >= 1);
        assertEquals(InvoiceStatus.OVERDUE, ctx.getInvoiceDao().findById(pastDue.id()).orElseThrow().status());
        assertEquals(InvoiceStatus.PAID, ctx.getInvoiceDao().findById(alreadyPaid.id()).orElseThrow().status());

        // Verify payment reminder mailto URI embeds recipient, invoice number, currency, and matching EUR IBAN
        CompanyProfile company = ctx.getSettingsDao().getCompanyProfile();
        String mailtoEn = ctx.getInvoiceService().buildPaymentReminderMailtoUri(pastDue, company, ctx.getI18n());
        assertTrue(mailtoEn.startsWith("mailto:billing@latepayer.at?subject="));
        assertTrue(mailtoEn.contains(pastDue.invoiceNumber()));
        assertTrue(mailtoEn.contains("EUR"));
        assertTrue(mailtoEn.contains("HU88"));
        ctx.getSettingsDao().saveInvoiceCompanyProfile(company.name(), company.addressParts(), company.taxNumber(), Map.of("EUR", ""));
        var withoutEur = ctx.getSettingsDao().getCompanyProfile();
        assertEquals(company.email(), withoutEur.email());
        String reminderWithoutBank = ctx.getInvoiceService().buildPaymentReminderMailtoUri(pastDue, withoutEur, ctx.getI18n());
        assertFalse(reminderWithoutBank.contains("IBAN"));
        assertFalse(reminderWithoutBank.contains("HU42"));
    }

    @Test
    void updatesNegotiatedOrderItemPriceAtomicallyAndBlocksShippedEdits() {
        Customer buyer = ctx.getCustomerDao().findBuyers(true).get(0);
        InventoryPackage bale = ctx.getPackageDao().findByFilter(null).stream()
                .filter(p -> p.status() == PackageStatus.IN_STOCK)
                .findFirst().orElseThrow();

        Order ord = ctx.getOrderService().createOrder(buyer.id(), "EUR", null, adminId);
        long orderId = ord.id();
        assertThrows(IllegalArgumentException.class,
                () -> ctx.getInvoiceService().ensureInvoiceForOrder(orderId, adminId));
        ord = ctx.getOrderService().addPackageToOrder(ord.id(), bale.id(), new BigDecimal("5.00"), adminId);
        List<OrderItem> items = ctx.getOrderItemDao().findByOrderId(ord.id());
        assertEquals(1, items.size());
        Invoice issued = ctx.getInvoiceService().ensureInvoiceForOrder(orderId, adminId);

        // Negotiate unit price down to 4.25 EUR/kg
        Order updated = ctx.getOrderService().updateOrderItemPrice(ord.id(), items.get(0).id(), new BigDecimal("4.25"));
        BigDecimal expectedTotal = bale.weightKg().multiply(new BigDecimal("4.25")).setScale(2, java.math.RoundingMode.HALF_UP);
        assertEquals(0, expectedTotal.compareTo(updated.totalPrice()));
        Invoice invoicedAgain = ctx.getInvoiceService().ensureInvoiceForOrder(orderId, adminId);
        assertEquals(issued.id(), invoicedAgain.id());
        assertEquals(issued.grossAmount(), invoicedAgain.grossAmount());

        // Ship order -> further price edits must be rejected with IllegalStateException
        ctx.getOrderService().changeOrderStatus(ord.id(), OrderStatus.SHIPPED, adminId);
        long itemId = items.get(0).id();
        assertThrows(IllegalStateException.class,
                () -> ctx.getOrderService().updateOrderItemPrice(orderId, itemId, new BigDecimal("3.00")));
    }
}
