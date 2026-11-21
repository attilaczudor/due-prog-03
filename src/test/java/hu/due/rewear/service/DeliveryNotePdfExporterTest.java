package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.DemoDataSeeder;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.dao.CustomerDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryNotePdfExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void exportsBilingualDeliveryNotePdfWithOrderQrAndLineItems() throws IOException {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("delivery-pdf.db"));
        new SchemaInitializer(db).initialize();
        new DemoDataSeeder(db).seedIfEmpty();

        OrderDao orderDao = new OrderDao(db);
        OrderItemDao itemDao = new OrderItemDao(db);
        CustomerDao customerDao = new CustomerDao(db);

        Order order = orderDao.findAll().stream()
                .filter(o -> "ORD-2026-0002".equals(o.orderNumber()))
                .findFirst()
                .orElseThrow();
        Customer customer = customerDao.findById(order.customerId()).orElseThrow();
        List<OrderItem> items = itemDao.findByOrderId(order.id());

        I18nManager i18n = new I18nManager();
        DeliveryNotePdfExporter exporter = new DeliveryNotePdfExporter(i18n, new QrCodeService());

        // English delivery note
        Path enPdf = tempDir.resolve("delivery-en.pdf");
        exporter.export(order, customer, items, enPdf);
        assertValidPdf(enPdf);

        // Hungarian delivery note (tests UTF-8 accented glyph rendering)
        i18n.setLanguage("hu");
        Path huPdf = tempDir.resolve("delivery-hu.pdf");
        exporter.export(order, customer, items, huPdf);
        assertValidPdf(huPdf);
    }

    private static void assertValidPdf(Path path) throws IOException {
        assertTrue(Files.exists(path));
        byte[] bytes = Files.readAllBytes(path);
        assertTrue(bytes.length > 4000, "Delivery note PDF should contain rendered A4 content");
        assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.US_ASCII));
    }
}
