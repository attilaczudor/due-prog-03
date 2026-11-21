package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.DemoDataSeeder;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.dao.CustomerDao;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.model.PackageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScannerAndOrderServiceTest {

    @TempDir
    Path tempDir;

    private InventoryPackageDao packageDao;
    private OrderItemDao orderItemDao;
    private CustomerDao customerDao;
    private UserDao userDao;
    private QrCodeService qrService;
    private ScannerService scannerService;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("scanner-order-test.db"));
        new SchemaInitializer(db).initialize();
        new DemoDataSeeder(db).seedIfEmpty();

        packageDao = new InventoryPackageDao(db);
        StockMovementDao movementDao = new StockMovementDao(db);
        OrderDao orderDao = new OrderDao(db);
        orderItemDao = new OrderItemDao(db);
        customerDao = new CustomerDao(db);
        userDao = new UserDao(db);
        qrService = new QrCodeService();
        scannerService = new ScannerService(new ProductCodeService(), qrService, packageDao, movementDao);
        orderService = new OrderService(db, orderDao, orderItemDao, packageDao, movementDao);
    }

    @Test
    void scansFromTextAndQrImageFilesHandlingAllOutcomes() throws IOException {
        String showcaseCode = "2026-11-12-11-23-L-TSH-A-A-25-3F";

        // 1. Found in DB via text input
        ScannerService.ScanResult found = scannerService.scanCode(showcaseCode.toLowerCase());
        assertEquals(ScannerService.ScanOutcome.FOUND_IN_DB, found.outcome());
        assertNotNull(found.inventoryPackage());
        assertFalse(found.history().isEmpty());

        // 2. Found in DB via PNG image decoding
        Path png = tempDir.resolve("showcase-qr.png");
        qrService.saveQrPng(showcaseCode, 320, png);
        ScannerService.ScanResult fromImg = scannerService.scanImageFile(png);
        assertEquals(ScannerService.ScanOutcome.FOUND_IN_DB, fromImg.outcome());
        assertEquals(showcaseCode, fromImg.inventoryPackage().qrCode());

        // 3. Valid format but not in local DB
        ScannerService.ScanResult unregistered = scannerService.scanCode("2026-11-21-10-00-M-JNS-B-A-40-FF");
        assertEquals(ScannerService.ScanOutcome.VALID_FORMAT_NOT_IN_DB, unregistered.outcome());
        assertNotNull(unregistered.parsedCode());
        assertNull(unregistered.inventoryPackage());

        // 4. Invalid code string
        ScannerService.ScanResult invalid = scannerService.scanCode("GARBAGE-123");
        assertEquals(ScannerService.ScanOutcome.INVALID_FORMAT, invalid.outcome());

        // 5. Image containing no QR code
        Path blankImg = tempDir.resolve("blank.png");
        BufferedImage white = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
        var g = white.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 120, 120);
        g.dispose();
        ImageIO.write(white, "PNG", blankImg.toFile());
        ScannerService.ScanResult noQr = scannerService.scanImageFile(blankImg);
        assertEquals(ScannerService.ScanOutcome.IMAGE_NO_QR_FOUND, noQr.outcome());
    }

    @Test
    void executesFullOrderLifecycleAndCancellationRelease() {
        long managerId = userDao.findByUsername("manager").orElseThrow().id();
        long customerId = customerDao.findAll(true).get(0).id();
        InventoryPackage bale = packageDao.findByQrCode("2026-11-12-11-23-L-TSH-A-A-25-3F").orElseThrow();

        // Create DRAFT order, attach bale, remove it, re-attach, pack, and ship
        Order order = orderService.createOrder(customerId, "Scanner dispatch test", managerId);
        assertEquals(OrderStatus.DRAFT, order.status());

        order = orderService.addPackageToOrder(order.id(), bale.id(), null, managerId);
        assertEquals(PackageStatus.RESERVED, packageDao.findById(bale.id()).orElseThrow().status());
        assertTrue(order.totalWeightKg().compareTo(new BigDecimal("25")) == 0);

        List<OrderItem> items = orderItemDao.findByOrderId(order.id());
        assertEquals(1, items.size());

        // Remove item -> package returns to IN_STOCK
        order = orderService.removeItemFromOrder(order.id(), items.get(0).id(), managerId);
        assertEquals(PackageStatus.IN_STOCK, packageDao.findById(bale.id()).orElseThrow().status());
        assertEquals(0, order.totalWeightKg().compareTo(BigDecimal.ZERO));

        // Re-add -> cancel -> package returns to IN_STOCK
        orderService.addPackageToOrder(order.id(), bale.id(), new BigDecimal("6.00"), managerId);
        Order cancelled = orderService.cancelOrder(order.id(), managerId);
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(PackageStatus.IN_STOCK, packageDao.findById(bale.id()).orElseThrow().status());

        // New order -> PACKING -> SHIPPED
        Order shipOrder = orderService.createOrder(customerId, "Final shipment", managerId);
        orderService.addPackageToOrder(shipOrder.id(), bale.id(), new BigDecimal("5.20"), managerId);
        shipOrder = orderService.startPacking(shipOrder.id());
        assertEquals(OrderStatus.PACKING, shipOrder.status());

        shipOrder = orderService.shipOrder(shipOrder.id(), managerId);
        assertEquals(OrderStatus.SHIPPED, shipOrder.status());
        assertNotNull(shipOrder.shippedAt());
        assertEquals(PackageStatus.SHIPPED, packageDao.findById(bale.id()).orElseThrow().status());
    }
}
