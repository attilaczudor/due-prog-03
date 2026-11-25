package hu.due.rewear.config;

import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.PackageStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quarantine widened two {@code CHECK} constraints, which SQLite can only do by rebuilding the table. These
 * are the inventory and its movement log, so the rebuild has to keep every row, every id and every index.
 */
class QuarantineMigrationTest {

    @TempDir
    Path tempDir;

    private AppContext openApp(String name) {
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        return new AppContext(new DatabaseManager(tempDir.resolve(name)),
                new AppPreferences(tempDir.resolve(name + ".properties")), i18n);
    }

    private static List<String> indexesOf(Connection conn, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (var ps = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private static long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Puts the two tables back the way they looked before quarantine existed.
     * <p>
     * The parent table is never renamed: SQLite rewrites the foreign keys of child tables to follow a rename,
     * which would leave them pointing at a table this helper then deletes. Copying the rows aside, recreating
     * the table under its own name and copying them back leaves every child reference intact.
     */
    private static void revertToOldSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = OFF");

            st.execute("CREATE TABLE packages_backup AS SELECT * FROM inventory_packages");
            st.execute("DROP TABLE inventory_packages");
            st.execute("""
                    CREATE TABLE inventory_packages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        qr_code TEXT NOT NULL UNIQUE,
                        target_group_id INTEGER NOT NULL,
                        garment_type_id INTEGER NOT NULL,
                        quality_grade_id INTEGER NOT NULL,
                        package_type_id INTEGER NOT NULL,
                        location_id INTEGER NOT NULL,
                        weight_kg REAL NOT NULL CHECK (weight_kg > 0),
                        hex_suffix TEXT NOT NULL CHECK (length(hex_suffix) = 2),
                        status TEXT NOT NULL DEFAULT 'IN_STOCK'
                            CHECK (status IN ('IN_STOCK', 'RESERVED', 'SHIPPED', 'DISCARDED')),
                        notes TEXT,
                        created_by INTEGER NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY (target_group_id) REFERENCES target_groups(id),
                        FOREIGN KEY (garment_type_id) REFERENCES garment_types(id),
                        FOREIGN KEY (quality_grade_id) REFERENCES quality_grades(id),
                        FOREIGN KEY (package_type_id) REFERENCES package_types(id),
                        FOREIGN KEY (location_id) REFERENCES warehouse_locations(id),
                        FOREIGN KEY (created_by) REFERENCES users(id)
                    )
                    """);
            st.execute("INSERT INTO inventory_packages SELECT id, qr_code, target_group_id, garment_type_id,"
                    + " quality_grade_id, package_type_id, location_id, weight_kg, hex_suffix, status, notes,"
                    + " created_by, created_at, updated_at FROM packages_backup");
            st.execute("DROP TABLE packages_backup");
            st.execute("CREATE INDEX IF NOT EXISTS idx_packages_qr_code ON inventory_packages(qr_code)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_packages_status ON inventory_packages(status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_packages_location ON inventory_packages(location_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_packages_created_at ON inventory_packages(created_at)");

            st.execute("CREATE TABLE movements_backup AS SELECT * FROM stock_movements");
            st.execute("DROP TABLE stock_movements");
            st.execute("""
                    CREATE TABLE stock_movements (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        package_id INTEGER NOT NULL,
                        movement_type TEXT NOT NULL
                            CHECK (movement_type IN ('INTAKE', 'RELOCATE', 'SCAN_VERIFY', 'RESERVE', 'RELEASE', 'SHIP', 'DISCARD')),
                        from_location_id INTEGER,
                        to_location_id INTEGER,
                        user_id INTEGER NOT NULL,
                        note TEXT,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY (package_id) REFERENCES inventory_packages(id),
                        FOREIGN KEY (user_id) REFERENCES users(id)
                    )
                    """);
            st.execute("INSERT INTO stock_movements SELECT id, package_id, movement_type, from_location_id,"
                    + " to_location_id, user_id, note, created_at FROM movements_backup");
            st.execute("DROP TABLE movements_backup");
            st.execute("CREATE INDEX IF NOT EXISTS idx_movements_package ON stock_movements(package_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_movements_created_at ON stock_movements(created_at)");
        }
    }

    @Test
    void anOlderDatabaseGainsQuarantineKeepingEveryRowIdAndIndex() throws SQLException {
        AppContext ctx = openApp("legacy.db");
        DatabaseManager db = ctx.getDatabaseManager();
        long packagesBefore;
        long movementsBefore;
        String sampleQr;
        long sampleId;
        List<String> packageIndexes;
        List<String> movementIndexes;

        try (Connection conn = db.getConnection()) {
            packagesBefore = count(conn, "inventory_packages");
            movementsBefore = count(conn, "stock_movements");
            packageIndexes = indexesOf(conn, "inventory_packages");
            movementIndexes = indexesOf(conn, "stock_movements");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, qr_code FROM inventory_packages ORDER BY id LIMIT 1")) {
                rs.next();
                sampleId = rs.getLong(1);
                sampleQr = rs.getString(2);
            }
            revertToOldSchema(conn);
            try (Statement st = conn.createStatement()) {
                assertThrows(SQLException.class, () -> st.execute(
                        "UPDATE inventory_packages SET status='QUARANTINED' WHERE id=" + sampleId),
                        "The old constraint should refuse the new status");
            }
        }
        assertTrue(packagesBefore > 0 && movementsBefore > 0, "The demo should have stock and movements");
        assertFalse(packageIndexes.isEmpty());

        new SchemaInitializer(db).initialize();

        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            assertEquals(packagesBefore, count(conn, "inventory_packages"), "No package may be lost");
            assertEquals(movementsBefore, count(conn, "stock_movements"), "No movement may be lost");
            assertEquals(packageIndexes, indexesOf(conn, "inventory_packages"), "Indexes must come back");
            assertEquals(movementIndexes, indexesOf(conn, "stock_movements"));
            try (ResultSet rs = st.executeQuery("SELECT qr_code FROM inventory_packages WHERE id=" + sampleId)) {
                assertTrue(rs.next());
                assertEquals(sampleQr, rs.getString(1), "Ids keep pointing at the same package");
            }
            st.execute("UPDATE inventory_packages SET status='QUARANTINED' WHERE id=" + sampleId);
            st.execute("INSERT INTO stock_movements (package_id, movement_type, user_id, created_at) VALUES ("
                    + sampleId + ", 'QUARANTINE', 1, '2026-11-25 10:00:00')");
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(rs.next(), "No foreign key may be left dangling");
            }
        }
        assertEquals(PackageStatus.QUARANTINED, ctx.getPackageDao().findById(sampleId).orElseThrow().status());
    }

    @Test
    void aDatabaseThatAlreadyHasQuarantineIsLeftAlone() throws SQLException {
        AppContext ctx = openApp("current.db");
        DatabaseManager db = ctx.getDatabaseManager();
        // A rebuild writes the table afresh, which gives it a new root page. Comparing the schema text would
        // not notice, because the table would be recreated from the very same definition.
        long packagesRoot;
        long movementsRoot;
        try (Connection conn = db.getConnection()) {
            assertTrue(storedSql(conn, "inventory_packages").contains("QUARANTINED"));
            packagesRoot = rootPage(conn, "inventory_packages");
            movementsRoot = rootPage(conn, "stock_movements");
        }

        new SchemaInitializer(db).initialize();
        new SchemaInitializer(db).initialize();

        try (Connection conn = db.getConnection()) {
            assertEquals(packagesRoot, rootPage(conn, "inventory_packages"),
                    "A table that already allows quarantine must not be rebuilt");
            assertEquals(movementsRoot, rootPage(conn, "stock_movements"));
        }
    }

    private static long rootPage(Connection conn, String table) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT rootpage FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String storedSql(Connection conn, String table) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
