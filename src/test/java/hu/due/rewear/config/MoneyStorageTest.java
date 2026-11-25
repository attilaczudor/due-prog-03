package hu.due.rewear.config;

import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Money is kept as an exact decimal, not as a binary floating point number, so an amount comes back exactly
 * as it was written and a column of amounts adds up exactly.
 */
class MoneyStorageTest {

    /** Every money column in the schema, by table. */
    private static final Map<String, List<String>> MONEY = Map.of(
            "garment_types", List.of("price_eur_per_kg", "price_huf_per_kg"),
            "quality_grades", List.of("default_price_per_kg", "default_price_huf_per_kg"),
            "orders", List.of("total_price"),
            "order_items", List.of("price_per_kg", "line_total"),
            "invoices", List.of("net_amount", "vat_amount", "gross_amount"),
            "invoice_items", List.of("unit_price", "line_net"));

    @TempDir
    Path tempDir;

    private AppContext openApp(String name) {
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        return new AppContext(new DatabaseManager(tempDir.resolve(name)),
                new AppPreferences(tempDir.resolve(name + ".properties")), i18n);
    }

    /** Columns whose stored values are not text, as "table.column(type)". */
    private static List<String> notStoredAsText(Connection conn) throws SQLException {
        List<String> wrong = new ArrayList<>();
        for (var entry : MONEY.entrySet()) {
            for (String column : entry.getValue()) {
                String sql = "SELECT DISTINCT typeof(" + column + ") FROM " + entry.getKey();
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        if (!"text".equals(rs.getString(1))) {
                            wrong.add(entry.getKey() + "." + column + "(" + rs.getString(1) + ")");
                        }
                    }
                }
            }
        }
        return wrong;
    }

    @Test
    void everyAmountTheApplicationWritesIsStoredAsText() throws SQLException {
        AppContext ctx = openApp("money.db");
        long userId = ctx.getUserDao().findByUsername("manager").orElseThrow().id();

        // An amount with digits a double cannot hold exactly.
        var invoice = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "Pontos Kft.", "11111111-2-07", "2400 Dunaújváros", "buyer@test.hu",
                "Odd amount", new BigDecimal("3"), "kg", new BigDecimal("0.07"),
                new BigDecimal("27"), "HU-27", "EUR", BusinessClock.today(),
                BusinessClock.today().plusDays(14), InvoiceStatus.ISSUED, userId);

        assertEquals(0, new BigDecimal("0.21").compareTo(invoice.netAmount()));
        var reloaded = ctx.getInvoiceDao().findById(invoice.id()).orElseThrow();
        assertEquals(invoice.netAmount(), reloaded.netAmount(), "An amount must survive the round trip exactly");
        assertEquals(0, new BigDecimal("0.06").compareTo(reloaded.vatAmount()));
        assertEquals(0, new BigDecimal("0.27").compareTo(reloaded.grossAmount()));

        try (Connection conn = ctx.getDatabaseManager().getConnection()) {
            assertEquals(List.of(), notStoredAsText(conn), "These money columns are not text");
            // The exact digits are what is on disk, not a float that happens to print that way.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT net_amount FROM invoices WHERE id=" + invoice.id())) {
                rs.next();
                assertEquals("0.21", rs.getString(1));
            }
        }
    }

    @Test
    void manySmallAmountsAddUpExactly() throws SQLException {
        AppContext ctx = openApp("sums.db");
        long userId = ctx.getUserDao().findByUsername("manager").orElseThrow().id();
        List<InvoiceService_Line> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            lines.add(new InvoiceService_Line("Line " + i, new BigDecimal("1"), "db", new BigDecimal("0.10")));
        }
        var invoice = ctx.getInvoiceService().createMultiLineManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "Tized Kft.", "11111111-2-07", "2400 Dunaújváros", "buyer@test.hu",
                lines.stream().map(l -> new hu.due.rewear.service.InvoiceService.InvoiceLineInput(
                        l.description(), l.quantity(), l.unit(), l.unitPrice())).toList(),
                BigDecimal.ZERO, "EU-0", "EUR", BusinessClock.today(), BusinessClock.today().plusDays(14),
                InvoiceStatus.ISSUED, userId);

        // Ten times 0.10 is exactly 1.00; in binary floating point it is 0.9999999999999999.
        assertEquals(0, new BigDecimal("1.00").compareTo(invoice.netAmount()));
        BigDecimal summed = ctx.getInvoiceService().findItems(invoice.id()).stream()
                .map(item -> item.lineNet()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("1.00").compareTo(summed));
    }

    @Test
    void anOlderDatabaseThatStoredMoneyAsFloatsIsConvertedWithoutLoss() throws SQLException {
        AppContext ctx = openApp("legacy-money.db");
        var db = ctx.getDatabaseManager();
        Map<Long, BigDecimal> grossBefore = new java.util.LinkedHashMap<>();
        for (var invoice : ctx.getInvoiceDao().findAll()) {
            grossBefore.put(invoice.id(), invoice.grossAmount());
        }
        assertTrue(grossBefore.size() > 1, "The demo should have invoices");

        // Put the invoice amounts back into a REAL column, the way an older database held them.
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = OFF");
            st.execute("CREATE TABLE invoices_backup AS SELECT * FROM invoices");
            st.execute("DROP TABLE invoices");
            st.execute("""
                    CREATE TABLE invoices (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        invoice_number TEXT NOT NULL UNIQUE,
                        direction TEXT NOT NULL,
                        category TEXT NOT NULL,
                        order_id INTEGER,
                        customer_id INTEGER,
                        partner_name TEXT NOT NULL,
                        partner_tax_number TEXT,
                        partner_address TEXT,
                        partner_email TEXT,
                        status TEXT NOT NULL DEFAULT 'ISSUED',
                        issue_date TEXT NOT NULL,
                        due_date TEXT NOT NULL,
                        paid_date TEXT,
                        net_amount REAL NOT NULL DEFAULT 0,
                        vat_rate REAL NOT NULL DEFAULT 27,
                        tax_type_code TEXT,
                        vat_amount REAL NOT NULL DEFAULT 0,
                        gross_amount REAL NOT NULL DEFAULT 0,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        exchange_rate REAL NOT NULL DEFAULT 400.0,
                        attachment_path TEXT,
                        archived_pdf_path TEXT,
                        credit_for_invoice_id INTEGER,
                        created_by INTEGER NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            st.execute("INSERT INTO invoices SELECT id, invoice_number, direction, category, order_id,"
                    + " customer_id, partner_name, partner_tax_number, partner_address, partner_email, status,"
                    + " issue_date, due_date, paid_date, net_amount, vat_rate, tax_type_code, vat_amount,"
                    + " gross_amount, currency, exchange_rate, attachment_path, archived_pdf_path,"
                    + " credit_for_invoice_id, created_by, created_at FROM invoices_backup");
            st.execute("DROP TABLE invoices_backup");
            try (ResultSet rs = st.executeQuery("SELECT DISTINCT typeof(gross_amount) FROM invoices")) {
                rs.next();
                assertEquals("real", rs.getString(1), "Precondition: the amounts are floats again");
            }
        }

        // One row carries more precision than money ever should, as a division would leave behind.
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            st.execute("UPDATE invoices SET net_amount = 1.0/3 WHERE id = (SELECT MIN(id) FROM invoices)");
        }

        new SchemaInitializer(db).initialize();

        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            assertEquals(List.of(), notStoredAsText(conn), "Everything should be text after the upgrade");
            try (ResultSet rs = st.executeQuery(
                    "SELECT net_amount FROM invoices WHERE id = (SELECT MIN(id) FROM invoices)")) {
                rs.next();
                assertEquals("0.3333", rs.getString(1), "An amount is capped at four decimals, not left at fifteen");
            }
        }
        for (var invoice : ctx.getInvoiceDao().findAll()) {
            assertEquals(0, grossBefore.get(invoice.id()).compareTo(invoice.grossAmount()),
                    "Invoice " + invoice.invoiceNumber() + " changed value");
        }
        assertTrue(grossBefore.size() == ctx.getInvoiceDao().findAll().size(), "No invoice may be lost");
    }

    /** Local shape so the test reads clearly; mapped onto the service's own input type. */
    private record InvoiceService_Line(String description, BigDecimal quantity, String unit, BigDecimal unitPrice) { }
}
