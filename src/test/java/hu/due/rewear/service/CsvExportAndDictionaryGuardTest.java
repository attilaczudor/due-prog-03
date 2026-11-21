package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.DemoDataSeeder;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.dao.DictionaryDao;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.PackageFilter;
import hu.due.rewear.model.StockMovement;
import hu.due.rewear.model.TargetGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvExportAndDictionaryGuardTest {

    @TempDir
    Path tempDir;

    private DictionaryDao dictionaryDao;
    private InventoryPackageDao packageDao;
    private StockMovementDao movementDao;

    @BeforeEach
    void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("phase6-test.db"));
        new SchemaInitializer(db).initialize();
        new DemoDataSeeder(db).seedIfEmpty();
        dictionaryDao = new DictionaryDao(db);
        packageDao = new InventoryPackageDao(db);
        movementDao = new StockMovementDao(db);
    }

    @Test
    void exportsInventoryAndAuditLogToUtf8CsvWithBom() throws IOException {
        I18nManager i18n = new I18nManager();
        i18n.setLanguage("hu");
        CsvExportService exporter = new CsvExportService(i18n);

        List<InventoryPackage> packages = packageDao.findByFilter(PackageFilter.empty());
        Path invCsv = tempDir.resolve("inventory-hu.csv");
        exporter.exportPackages(packages, invCsv);

        String invContent = Files.readString(invCsv, StandardCharsets.UTF_8);
        assertTrue(invContent.startsWith("\uFEFFQR Code,"));
        assertTrue(invContent.contains("2026-11-12-11-23-L-TSH-A-A-25-3F"));
        assertTrue(invContent.contains("Pólók"));

        List<StockMovement> movements = movementDao.findAll();
        Path auditCsv = tempDir.resolve("audit-hu.csv");
        exporter.exportStockMovements(movements, auditCsv);
        String auditContent = Files.readString(auditCsv, StandardCharsets.UTF_8);
        assertTrue(auditContent.startsWith("\uFEFFID,Timestamp,"));
        assertTrue(auditContent.contains("Bevételezés"));
    }

    @Test
    void detectsReferencedDictionaryEntriesAndAllowsDeletingUnusedOnes() {
        TargetGroup ladies = dictionaryDao.findTargetGroups(false).stream()
                .filter(g -> "L".equals(g.code()))
                .findFirst()
                .orElseThrow();
        assertTrue(dictionaryDao.isTargetGroupReferenced(ladies.id()));

        long tempId = dictionaryDao.insertTargetGroup("TMP", "Temporary", "Ideiglenes");
        assertFalse(dictionaryDao.isTargetGroupReferenced(tempId));
        dictionaryDao.deleteTargetGroup(tempId);
        assertTrue(dictionaryDao.findTargetGroups(false).stream().noneMatch(g -> g.id() == tempId));
    }
}
