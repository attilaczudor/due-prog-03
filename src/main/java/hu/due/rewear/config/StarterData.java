package hu.due.rewear.config;

import hu.due.rewear.dao.DictionaryDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.model.LocationType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * The reference data a second-hand clothing warehouse starts from: target groups, garment types, quality
 * grades, package types, and a starter shelf layout. It contains no people, partners, stock or documents.
 * <p>
 * The demo workspace and a new company both draw on this one definition. All of it can be edited later on
 * the Dictionaries and Locations screens; it only spares a new installation from entering the usual
 * catalogue by hand before the first bale can be taken in.
 */
public final class StarterData {

    /** Identifiers of the inserted catalogue rows, keyed by code, plus the per-kg reference prices. */
    public record Catalogs(Map<String, Long> targets, Map<String, Long> garments, Map<String, Long> qualities,
                           Map<String, Long> packages, Map<String, BigDecimal> garmentEur,
                           Map<String, BigDecimal> garmentHuf) { }

    private StarterData() { }

    /** True when none of the four catalogues has an entry yet. */
    public static boolean catalogsAreEmpty(DictionaryDao dictionaryDao) {
        return dictionaryDao.findTargetGroups(false).isEmpty()
                && dictionaryDao.findGarmentTypes(false).isEmpty()
                && dictionaryDao.findQualityGrades(false).isEmpty()
                && dictionaryDao.findPackageTypes(false).isEmpty();
    }

    public static Catalogs insertCatalogs(Connection conn, DictionaryDao dictionaryDao) throws SQLException {
        // Target groups (WHATFOR)
        Map<String, Long> targets = new HashMap<>();
        targets.put("L", dictionaryDao.insertTargetGroup(conn, "L", "Ladies", "Női"));
        targets.put("M", dictionaryDao.insertTargetGroup(conn, "M", "Men", "Férfi"));
        targets.put("K", dictionaryDao.insertTargetGroup(conn, "K", "Kids", "Gyerek"));
        targets.put("B", dictionaryDao.insertTargetGroup(conn, "B", "Baby", "Baba"));
        targets.put("U", dictionaryDao.insertTargetGroup(conn, "U", "Unisex", "Uniszex"));
        targets.put("H", dictionaryDao.insertTargetGroup(conn, "H", "Home Textiles", "Lakástextil"));

        // 4. Garment / Product Types (TYPE) with dual EUR/kg & HUF/kg pricing
        Map<String, Long> garments = new HashMap<>();
        Map<String, BigDecimal> garmentEur = new HashMap<>();
        Map<String, BigDecimal> garmentHuf = new HashMap<>();
        garments.put("TSH", dictionaryDao.insertGarmentType(conn, "TSH", "T-Shirts", "Pólók", new BigDecimal("4.50"), new BigDecimal("1800")));
        garmentEur.put("TSH", new BigDecimal("4.50")); garmentHuf.put("TSH", new BigDecimal("1800"));
        garments.put("JNS", dictionaryDao.insertGarmentType(conn, "JNS", "Jeans", "Farmerek", new BigDecimal("5.50"), new BigDecimal("2200")));
        garmentEur.put("JNS", new BigDecimal("5.50")); garmentHuf.put("JNS", new BigDecimal("2200"));
        garments.put("JKT", dictionaryDao.insertGarmentType(conn, "JKT", "Jackets", "Kabátok", new BigDecimal("7.50"), new BigDecimal("3000")));
        garmentEur.put("JKT", new BigDecimal("7.50")); garmentHuf.put("JKT", new BigDecimal("3000"));
        garments.put("DRS", dictionaryDao.insertGarmentType(conn, "DRS", "Dresses", "Ruhák", new BigDecimal("6.00"), new BigDecimal("2400")));
        garmentEur.put("DRS", new BigDecimal("6.00")); garmentHuf.put("DRS", new BigDecimal("2400"));
        garments.put("SWT", dictionaryDao.insertGarmentType(conn, "SWT", "Sweaters", "Pulóverek", new BigDecimal("5.00"), new BigDecimal("2000")));
        garmentEur.put("SWT", new BigDecimal("5.00")); garmentHuf.put("SWT", new BigDecimal("2000"));
        garments.put("SHO", dictionaryDao.insertGarmentType(conn, "SHO", "Shoes", "Cipők", new BigDecimal("8.00"), new BigDecimal("3200")));
        garmentEur.put("SHO", new BigDecimal("8.00")); garmentHuf.put("SHO", new BigDecimal("3200"));
        garments.put("MIX", dictionaryDao.insertGarmentType(conn, "MIX", "Mixed Garments", "Vegyes ruházat", new BigDecimal("3.50"), new BigDecimal("1400")));
        garmentEur.put("MIX", new BigDecimal("3.50")); garmentHuf.put("MIX", new BigDecimal("1400"));

        // 5. Quality Grades (QUALITY) with dual EUR/kg & HUF/kg fallback prices
        Map<String, Long> qualities = new HashMap<>();
        qualities.put("CR", dictionaryDao.insertQualityGrade(conn, "CR", "Cream / Extra", "Krém / Extra", new BigDecimal("9.50"), new BigDecimal("3800")));
        qualities.put("A", dictionaryDao.insertQualityGrade(conn, "A", "Grade A", "A osztály", new BigDecimal("5.20"), new BigDecimal("2080")));
        qualities.put("B", dictionaryDao.insertQualityGrade(conn, "B", "Grade B", "B osztály", new BigDecimal("2.80"), new BigDecimal("1120")));
        qualities.put("C", dictionaryDao.insertQualityGrade(conn, "C", "Grade C / Rags", "C osztály / Géprongy", new BigDecimal("0.90"), new BigDecimal("360")));

        // 6. Package Types (PACKAGETYPE)
        Map<String, Long> packages = new HashMap<>();
        packages.put("A", dictionaryDao.insertPackageType(conn, "A", "Compressed Bale", "Préselt bála", new BigDecimal("0.50"), new BigDecimal("55")));
        packages.put("B", dictionaryDao.insertPackageType(conn, "B", "Clear Bag", "Átlátszó zsák", new BigDecimal("0.20"), new BigDecimal("30")));
        packages.put("C", dictionaryDao.insertPackageType(conn, "C", "Cardboard Box", "Kartondoboz", new BigDecimal("1.00"), new BigDecimal("35")));
        packages.put("P", dictionaryDao.insertPackageType(conn, "P", "Pallet", "Raklap", new BigDecimal("15.00"), new BigDecimal("500")));


        return new Catalogs(targets, garments, qualities, packages, garmentEur, garmentHuf);
    }

    public static Map<String, Long> insertLocations(Connection conn, WarehouseLocationDao locationDao) throws SQLException {
        // Warehouse locations (Operator Stations + Storage Shelves + Dispatch/Connection Zone)
        Map<String, Long> locations = new HashMap<>();
        locations.put("ST-OP-01", locationDao.insert(conn, "ST-OP-01", "OP", "01", "01", LocationType.OPERATOR_STATION, new BigDecimal("400")));
        locations.put("ST-OP-02", locationDao.insert(conn, "ST-OP-02", "OP", "02", "01", LocationType.OPERATOR_STATION, new BigDecimal("400")));
        locations.put("ST-OP-03", locationDao.insert(conn, "ST-OP-03", "OP", "03", "01", LocationType.OPERATOR_STATION, new BigDecimal("400")));
        locations.put("A-01-01", locationDao.insert(conn, "A-01-01", "A", "01", "01", LocationType.STORAGE, new BigDecimal("600")));
        locations.put("A-01-02", locationDao.insert(conn, "A-01-02", "A", "01", "02", LocationType.STORAGE, new BigDecimal("600")));
        locations.put("A-02-01", locationDao.insert(conn, "A-02-01", "A", "02", "01", LocationType.STORAGE, new BigDecimal("500")));
        locations.put("B-01-01", locationDao.insert(conn, "B-01-01", "B", "01", "01", LocationType.STORAGE, new BigDecimal("800")));
        locations.put("B-01-02", locationDao.insert(conn, "B-01-02", "B", "01", "02", LocationType.STORAGE, new BigDecimal("800")));
        locations.put("C-01-01", locationDao.insert(conn, "C-01-01", "C", "01", "01", LocationType.STORAGE, new BigDecimal("1200")));
        locations.put("DOCK-01", locationDao.insert(conn, "DOCK-01", "DOCK", "01", "01", LocationType.DISPATCH_ZONE, new BigDecimal("2000")));


        return locations;
    }
}
