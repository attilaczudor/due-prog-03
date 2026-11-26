package hu.due.rewear;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.PackageFilter;
import hu.due.rewear.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures how the application behaves with a realistic amount of stock, rather than the handful of demo
 * rows. Skipped unless a size is asked for, so the normal build stays quick:
 * <pre>
 *   ./mvnw test -Dtest=LoadMeasurementTest -Dload.rows=50000
 * </pre>
 * It prints what each operation costs and fails only if something is far outside what a person would accept
 * from a desktop application, so it reports rather than guesses.
 */
class LoadMeasurementTest {

    /** Above this a screen feels slow to the person using it. */
    private static final long SLOW_MILLIS = 1000;

    @TempDir
    Path tempDir;

    private record Timing(String what, long millis, String detail) { }

    private final List<Timing> timings = new ArrayList<>();

    private <T> T time(String what, Supplier<T> work) {
        long start = System.nanoTime();
        T result = work.get();
        long millis = (System.nanoTime() - start) / 1_000_000;
        String detail = result instanceof java.util.Collection<?> c ? c.size() + " rows" : String.valueOf(result);
        timings.add(new Timing(what, millis, detail));
        return result;
    }

    /**
     * Adds {@code rows} packages spread over many shelves, in one transaction. Written straight to SQL: the
     * point is to measure reading a large warehouse, not to re-measure the intake service.
     */
    private void seedPackages(DatabaseManager db, int rows) throws SQLException {
        seedPackages(db, rows, 0);
    }

    /** @param offset keeps the generated codes unique across several batches in one run */
    private void seedPackages(DatabaseManager db, int rows, int offset) throws SQLException {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            List<Long> shelves = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id FROM warehouse_locations WHERE location_type='STORAGE' AND active=1")) {
                while (rs.next()) {
                    shelves.add(rs.getLong(1));
                }
            }
            long target;
            long garment;
            long grade;
            long packaging;
            long user;
            try (Statement st = conn.createStatement()) {
                target = first(st, "SELECT id FROM target_groups LIMIT 1");
                garment = first(st, "SELECT id FROM garment_types LIMIT 1");
                grade = first(st, "SELECT id FROM quality_grades LIMIT 1");
                packaging = first(st, "SELECT id FROM package_types LIMIT 1");
                user = first(st, "SELECT id FROM users LIMIT 1");
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO inventory_packages (qr_code, target_group_id, garment_type_id, quality_grade_id,
                        package_type_id, location_id, weight_kg, hex_suffix, status, notes, created_by,
                        created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,NULL,?,?,?)
                    """)) {
                for (int i = 0; i < rows; i++) {
                    ps.setString(1, String.format("2026-01-01-08-00-L-TSH-A-A-25-%06X", i + offset));
                    ps.setLong(2, target);
                    ps.setLong(3, garment);
                    ps.setLong(4, grade);
                    ps.setLong(5, packaging);
                    ps.setLong(6, shelves.get(i % shelves.size()));
                    ps.setDouble(7, 20 + (i % 30));
                    ps.setString(8, String.format("%02X", i % 256));
                    ps.setString(9, i % 25 == 0 ? "RESERVED" : "IN_STOCK");
                    ps.setLong(10, user);
                    ps.setString(11, "2026-01-01T07:00:00Z");
                    ps.setString(12, "2026-01-01T07:00:00Z");
                    ps.addBatch();
                    if (i % 5000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    private int seeded;

    private long timeSeeding(AppContext ctx, int rows) throws SQLException {
        long start = System.nanoTime();
        seeded += 1_000_000;
        seedPackages(ctx.getDatabaseManager(), rows, seeded);
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static String perThousand(long millis, int rows) {
        return rows == 0 ? "" : String.format(java.util.Locale.ROOT, "%.1f ms per 1000 rows",
                millis * 1000.0 / rows);
    }

    private static long first(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void measuresTheApplicationAgainstAWarehouseFullOfStock() throws SQLException {
        String requested = System.getProperty("load.rows");
        assumeTrue(requested != null, "Set -Dload.rows to run this measurement");
        int rows = Integer.parseInt(requested);

        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        AppContext ctx = new AppContext(new DatabaseManager(tempDir.resolve("load.db")),
                new AppPreferences(tempDir.resolve("app.properties")), i18n);
        User manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        ctx.getSession().login(manager);

        long seedStart = System.nanoTime();
        seedPackages(ctx.getDatabaseManager(), rows);
        long seedMillis = (System.nanoTime() - seedStart) / 1_000_000;

        long total = time("count all packages", () -> ctx.getPackageDao().findByFilter(PackageFilter.empty()).size());
        assertTrue(total >= rows, "The warehouse should hold what was seeded");

        // What the sidebar does every ten seconds, on a background thread.
        long badgeStart = System.nanoTime();
        Map<String, Long> badge = new LinkedHashMap<>();
        badge.put("putaway queue", (long) ctx.getPackageDao().countPutawayQueue());
        badge.put("pending staging", (long) ctx.getTransportService().countPendingStaging(null));
        badge.put("open orders", (long) ctx.getOrderDao().countOpenOrders());
        badge.put("overdue invoices", (long) ctx.getInvoiceDao().countOverdue());
        badge.put("system to-dos", (long) ctx.getTodoService().buildSystemTodosFor(manager, i18n).size());
        badge.put("unread notifications", (long) ctx.getNotificationService().countUnread(manager.id()));
        long badgeMillis = (System.nanoTime() - badgeStart) / 1_000_000;
        timings.add(new Timing("one full badge refresh", badgeMillis, badge.toString()));

        time("inventory list, every row", () -> ctx.getPackageDao().findByFilter(PackageFilter.empty()));
        time("inventory screen (bounded slice)", () -> ctx.getPackageDao().findByFilter(PackageFilter.empty(), 2000));
        time("inventory totals, counted in the database", () -> ctx.getPackageDao().summarise(PackageFilter.empty()).packages());
        time("stock valuation", () -> ctx.getPackageDao().sumInStockValuation().totalWeightKg());
        time("weight by quality grade", () -> ctx.getPackageDao().sumInStockWeightByQualityGrade());
        time("pick list for every open order", () -> ctx.getPickListService().forOrders(null).total());
        time("open a stocktake and list what is missing", () -> {
            var session = ctx.getStocktakeService().open(null, null, "load measurement");
            int missing = ctx.getStocktakeService().missing(session.id()).size();
            ctx.getStocktakeService().cancel(session.id());
            return missing;
        });
        time("invoice ledger", () -> ctx.getInvoiceDao().findAll());
        time("rota month", () -> ctx.getRotaService().month(java.time.YearMonth.from(ctx.getRotaService().today())).size());

        timings.add(new Timing("write " + rows + " packages (change log on)", seedMillis,
                perThousand(seedMillis, rows)));

        // What the change log costs on a bulk write: the same work twice, once with the triggers on and
        // once with them off. It is measured last so the reading figures above are of the warehouse that
        // was asked for and not of a bigger one.
        long withTriggers = timeSeeding(ctx, 5000);
        long withoutTriggers;
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement()) {
            for (String name : hu.due.rewear.config.ChangeLog.triggerNames()) {
                stmt.execute("DROP TRIGGER IF EXISTS " + name);
            }
            withoutTriggers = timeSeeding(ctx, 5000);
        }
        timings.add(new Timing("write 5000 packages with the change log", withTriggers,
                perThousand(withTriggers, 5000)));
        timings.add(new Timing("write 5000 packages without it", withoutTriggers,
                perThousand(withoutTriggers, 5000)));

        // How much of a query is spent opening a connection, which this application does per call.
        long connectStart = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            try (Connection conn = ctx.getDatabaseManager().getConnection()) {
                conn.getAutoCommit();
            }
        }
        long connectMicros = (System.nanoTime() - connectStart) / 1_000 / 50;

        StringBuilder report = new StringBuilder("\n=== Load measurement: " + rows + " packages seeded in "
                + seedMillis + " ms ===\n");
        for (Timing t : timings) {
            report.append(String.format("%-45s %6d ms   %s%n", t.what(), t.millis(), t.detail()));
        }
        report.append(String.format("%-45s %6d us%n", "opening one database connection", connectMicros));
        System.out.println(report);

        // The unbounded listing is measured for comparison; no screen loads it, so it is not held to the budget.
        // Writing tens of thousands of rows at once is a measurement, not a screen: nobody waits for it in
        // the application, and the seeder that does it has the change log switched off.
        List<String> slow = timings.stream()
                .filter(t -> !t.what().startsWith("write "))
                .filter(t -> !t.what().equals("inventory list, every row") && !t.what().equals("count all packages"))
                .filter(t -> t.millis() > SLOW_MILLIS)
                .map(t -> t.what() + " took " + t.millis() + " ms").toList();
        assertTrue(slow.isEmpty(), "Slower than a person would accept:\n" + String.join("\n", slow) + report);
    }
}
