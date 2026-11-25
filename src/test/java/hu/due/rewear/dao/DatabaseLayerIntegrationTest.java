package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.DemoDataSeeder;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.model.CategoryWeightStat;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.MovementType;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import hu.due.rewear.model.PackageFilter;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import hu.due.rewear.model.WarehouseLocation;
import hu.due.rewear.service.ProductCodeService;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mindrot.jbcrypt.BCrypt;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseLayerIntegrationTest {

    @TempDir
    Path tempDir;

    private DatabaseManager db;
    private UserDao userDao;
    private DictionaryDao dictionaryDao;
    private WarehouseLocationDao locationDao;
    private InventoryPackageDao packageDao;
    private StockMovementDao movementDao;
    private OrderDao orderDao;
    private OrderItemDao orderItemDao;

    @BeforeEach
    void setUp() {
        db = new DatabaseManager(tempDir.resolve("test-rewear.db"));
        new SchemaInitializer(db).initialize();
        new DemoDataSeeder(db).seedIfEmpty();

        userDao = new UserDao(db);
        dictionaryDao = new DictionaryDao(db);
        locationDao = new WarehouseLocationDao(db);
        packageDao = new InventoryPackageDao(db);
        movementDao = new StockMovementDao(db);
        orderDao = new OrderDao(db);
        orderItemDao = new OrderItemDao(db);
    }

    @Test
    void seederIsIdempotentAndHashesDemoPasswordsWithBcrypt() {
        int initialCount = packageDao.findByFilter(PackageFilter.empty()).size();
        assertTrue(initialCount >= 20);

        // Running seeder a second time must not duplicate records
        new DemoDataSeeder(db).seedIfEmpty();
        assertEquals(initialCount, packageDao.findByFilter(PackageFilter.empty()).size());

        User admin = userDao.findByUsername("admin").orElseThrow();
        assertEquals(RoleCode.ADMIN, admin.roleCode());
        assertTrue(BCrypt.checkpw("admin123", admin.passwordHash()));
        assertFalse(BCrypt.checkpw("wrongpass", admin.passwordHash()));
    }

    @Test
    void findsSeededShowcasePackageAndSupportsUserCreatedDictionaryElements() {
        Optional<InventoryPackage> showcase = packageDao.findByQrCode("2026-11-12-11-23-L-TSH-A-A-25-3F");
        assertTrue(showcase.isPresent());
        assertEquals("Ladies", showcase.get().targetGroupNameEn());
        assertEquals("Pólók", showcase.get().garmentTypeNameHu());

        // Create brand-new custom dictionary entries directly in DB and build a package from them
        long tgId = dictionaryDao.insertTargetGroup("SP", "Sportswear", "Sportruházat");
        long gtId = dictionaryDao.insertGarmentType("TRK", "Tracksuits", "Melegítők");
        long qgId = dictionaryDao.insertQualityGrade("EX", "Extra Vintage", "Extra Vintage", new BigDecimal("12.00"));
        long ptId = dictionaryDao.insertPackageType("S", "Sack", "Nagyméretű zsák", new BigDecimal("0.30"), new BigDecimal("40"));
        WarehouseLocation loc = locationDao.findAll(true).get(0);
        User operator = userDao.findByUsername("operator").orElseThrow();

        ProductCodeService codeService = new ProductCodeService();
        LocalDateTime ts = LocalDateTime.of(2026, 11, 20, 17, 15);
        String qr = codeService.buildCode(ts, "SP", "TRK", "EX", "S", new BigDecimal("22"), "E9");

        long newId = db.executeInTransaction(conn -> {
            long id = packageDao.insert(conn, qr, tgId, gtId, qgId, ptId, loc.id(),
                    new BigDecimal("22"), "E9", PackageStatus.IN_STOCK, "Custom code test", operator.id(), ts);
            movementDao.insert(conn, id, MovementType.INTAKE, null, loc.id(), operator.id(), "Custom intake", ts);
            return id;
        });

        InventoryPackage created = packageDao.findById(newId).orElseThrow();
        assertEquals("2026-11-20-17-15-SP-TRK-EX-S-22-E9", created.qrCode());
        assertEquals("Sportruházat", created.localizedTargetGroup("hu"));
        assertTrue(dictionaryDao.isTargetGroupReferenced(tgId));
        assertEquals(1, movementDao.findByPackageId(newId).size());
    }

    @Test
    void enforcesUniqueQrCodeAndForeignKeyConstraints() {
        WarehouseLocation loc = locationDao.findAll(true).get(0);
        User admin = userDao.findByUsername("admin").orElseThrow();
        long tgId = dictionaryDao.findTargetGroups(true).get(0).id();
        long gtId = dictionaryDao.findGarmentTypes(true).get(0).id();
        long qgId = dictionaryDao.findQualityGrades(true).get(0).id();
        long ptId = dictionaryDao.findPackageTypes(true).get(0).id();

        // Duplicate QR code fails
        assertThrows(DaoException.class, () -> db.runInTransaction(conn ->
                packageDao.insert(conn, "2026-11-12-11-23-L-TSH-A-A-25-3F",
                        tgId, gtId, qgId, ptId, loc.id(), BigDecimal.TEN, "3F",
                        PackageStatus.IN_STOCK, null, admin.id(), BusinessClock.now())
        ));

        // Invalid FK (non-existent location ID 99999) fails because PRAGMA foreign_keys = ON
        assertThrows(DaoException.class, () -> db.runInTransaction(conn ->
                packageDao.insert(conn, "2026-11-20-17-20-L-TSH-A-A-10-AA",
                        tgId, gtId, qgId, ptId, 99999L, BigDecimal.TEN, "AA",
                        PackageStatus.IN_STOCK, null, admin.id(), BusinessClock.now())
        ));
    }

    @Test
    void computesLocationOccupancyOrderItemsAndAnalyticsStats() {
        List<WarehouseLocation> locations = locationDao.findAll(true);
        assertTrue(locations.stream().anyMatch(l -> l.usedWeightKg().compareTo(BigDecimal.ZERO) > 0));

        List<Order> openOrders = orderDao.findOpenOrders();
        assertEquals(2, openOrders.size());
        assertEquals(2, orderDao.countOpenOrders());

        Order packingOrder = openOrders.stream()
                .filter(o -> "ORD-2026-0002".equals(o.orderNumber()))
                .findFirst()
                .orElseThrow();
        List<OrderItem> items = orderItemDao.findByOrderId(packingOrder.id());
        assertEquals(3, items.size());

        List<CategoryWeightStat> byQuality = packageDao.sumInStockWeightByQualityGrade();
        assertFalse(byQuality.isEmpty());
        List<CategoryWeightStat> byGarment = packageDao.sumInStockWeightByGarmentType();
        assertFalse(byGarment.isEmpty());
    }
}
