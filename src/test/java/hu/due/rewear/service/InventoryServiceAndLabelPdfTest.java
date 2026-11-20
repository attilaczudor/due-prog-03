package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.DemoDataSeeder;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.dao.DictionaryDao;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.GarmentType;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.MovementType;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.PackageType;
import hu.due.rewear.model.QualityGrade;
import hu.due.rewear.model.StockMovement;
import hu.due.rewear.model.TargetGroup;
import hu.due.rewear.model.WarehouseLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryServiceAndLabelPdfTest {

    @TempDir
    Path tempDir;

    private DictionaryDao dictionaryDao;
    private WarehouseLocationDao locationDao;
    private StockMovementDao movementDao;
    private UserDao userDao;
    private InventoryService inventoryService;
    private ProductCodeService codeService;

    @BeforeEach
    void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("inv-test.db"));
        new SchemaInitializer(db).initialize();
        new DemoDataSeeder(db).seedIfEmpty();

        dictionaryDao = new DictionaryDao(db);
        locationDao = new WarehouseLocationDao(db);
        InventoryPackageDao packageDao = new InventoryPackageDao(db);
        movementDao = new StockMovementDao(db);
        userDao = new UserDao(db);
        codeService = new ProductCodeService();
        inventoryService = new InventoryService(db, packageDao, movementDao, codeService);
    }

    @Test
    void createsBatchWithUniqueHexSuffixesAndLogsMovements() {
        TargetGroup tg = dictionaryDao.findTargetGroups(true).get(0);
        GarmentType gt = dictionaryDao.findGarmentTypes(true).get(0);
        QualityGrade qg = dictionaryDao.findQualityGrades(true).get(0);
        PackageType pt = dictionaryDao.findPackageTypes(true).get(0);
        List<WarehouseLocation> locs = locationDao.findAll(true);
        long userId = userDao.findByUsername("operator").orElseThrow().id();

        List<InventoryPackage> batch = inventoryService.createPackages(
                tg, gt, qg, pt, locs.get(0), new BigDecimal("25"), 5, "Batch intake", "A7", userId
        );

        assertEquals(5, batch.size());
        Set<String> uniqueCodes = batch.stream().map(InventoryPackage::qrCode).collect(Collectors.toSet());
        assertEquals(5, uniqueCodes.size());
        assertTrue(batch.get(0).qrCode().endsWith("-A7"));
        for (InventoryPackage pkg : batch) {
            assertTrue(codeService.isValid(pkg.qrCode()));
            assertEquals(PackageStatus.IN_STOCK, pkg.status());
            List<StockMovement> movements = movementDao.findByPackageId(pkg.id());
            assertEquals(1, movements.size());
            assertEquals(MovementType.INTAKE, movements.get(0).movementType());
        }

        // Relocate and verify stocktake
        InventoryPackage first = batch.get(0);
        InventoryPackage relocated = inventoryService.relocatePackage(first.id(), locs.get(1).id(), "Move shelf", userId);
        assertEquals(locs.get(1).id(), relocated.locationId());

        inventoryService.verifyStocktake(first.id(), "Checked OK", userId);
        List<StockMovement> history = movementDao.findByPackageId(first.id());
        assertEquals(3, history.size());
    }

    @Test
    void exportsThermalAndA4FourUpTicketPdfs() throws IOException {
        TargetGroup tg = dictionaryDao.findTargetGroups(true).get(0);
        GarmentType gt = dictionaryDao.findGarmentTypes(true).get(0);
        QualityGrade qg = dictionaryDao.findQualityGrades(true).get(0);
        PackageType pt = dictionaryDao.findPackageTypes(true).get(0);
        WarehouseLocation loc = locationDao.findAll(true).get(0);
        long userId = userDao.findByUsername("admin").orElseThrow().id();

        List<InventoryPackage> batch = inventoryService.createPackages(
                tg, gt, qg, pt, loc, new BigDecimal("30"), 5, "PDF export test", "C4", userId
        );

        I18nManager i18n = new I18nManager();
        i18n.setLanguage("hu"); // Verify Hungarian UTF-8 rendering in PDF too
        TicketLabelPainter painter = new TicketLabelPainter(new QrCodeService(), i18n);
        LabelPdfExporter exporter = new LabelPdfExporter(painter);

        Path thermalPdf = tempDir.resolve("thermal-100x150.pdf");
        exporter.exportToPdf(List.of(batch.get(0)), LabelPreset.THERMAL_100X150, thermalPdf);
        assertValidPdf(thermalPdf);

        Path a4Pdf = tempDir.resolve("a4-4up.pdf");
        exporter.exportToPdf(batch, LabelPreset.A4_4UP, a4Pdf);
        assertValidPdf(a4Pdf);
    }

    private static void assertValidPdf(Path pdfPath) throws IOException {
        assertTrue(Files.exists(pdfPath));
        byte[] bytes = Files.readAllBytes(pdfPath);
        assertTrue(bytes.length > 2000, "PDF should contain rendered ticket graphics");
        String header = new String(bytes, 0, 5, StandardCharsets.US_ASCII);
        assertEquals("%PDF-", header);
    }
}
