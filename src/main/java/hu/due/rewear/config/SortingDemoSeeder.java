package hu.due.rewear.config;

import hu.due.rewear.dao.RoleDao;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SortingDemoSeeder {
    private static final String SEED_KEY = "demo.sorting.seeded";
    private static final String CURRENT_VERSION = "4";
    private static final Person FULFILMENT_DRIVER = new Person("Kelemen", "Ádám");
    private static final List<Person> HELPERS = List.of(
            new Person("Szűcs", "Bálint"),
            new Person("Fülöp", "Anikó"),
            new Person("Gulyás", "Levente"),
            new Person("Vörös", "Klára"),
            new Person("Bálint", "Renáta"));
    private static final List<Person> SORTERS = List.of(
            new Person("Balogh", "Anna"),
            new Person("Farkas", "Réka"),
            new Person("Varga", "Júlia"),
            new Person("Kiss", "Zsófia"),
            new Person("Szalai", "Éva"),
            new Person("Horváth", "Eszter"),
            new Person("Németh", "Nóra"),
            new Person("Molnár", "Dóra"),
            new Person("Kovács", "Katalin"),
            new Person("Takács", "Petra"),
            new Person("Juhász", "Viktória"),
            new Person("Lakatos", "Andrea"),
            new Person("Mészáros", "Noémi"),
            new Person("Papp", "Ildikó"),
            new Person("Simon", "Ágnes"),
            new Person("Bognár", "Lilla"),
            new Person("Sipos", "Gabriella"),
            new Person("Fekete", "Emese"),
            new Person("Vass", "Orsolya"),
            new Person("László", "Judit"));

    private record Person(String familyName, String givenName) {
        String fullName() {
            return familyName + " " + givenName;
        }
    }

    private final DatabaseManager db;
    private final UserDao users;
    private final RoleDao roles;

    SortingDemoSeeder(DatabaseManager db) {
        this.db = db;
        this.users = new UserDao(db);
        this.roles = new RoleDao(db);
    }

    void seedForDemoWorkspace() {
        String version = seedVersion();
        if (version != null) {
            // Sequential steps, so a workspace seeded at any earlier version ends up current.
            if ("1".equals(version)) upgradeLegacyNames();
            upgradeRawLots();
            upgradeRota();
            return;
        }
        var stationManager = users.findByUsername("operator");
        var driver = users.findByUsername("forklift");
        var manager = users.findByUsername("manager");
        if (stationManager.isEmpty() || driver.isEmpty() || manager.isEmpty()) return;
        User operator = stationManager.get();
        if (operator.roleCode() != RoleCode.OPERATOR || driver.get().roleCode() != RoleCode.SUPPLY_DRIVER
                || !BCrypt.checkpw("operator123", operator.passwordHash())) return;
        long sorterRole = roles.findByCode(RoleCode.SORTER).orElseThrow().id();
        long helperRole = roles.findByCode(RoleCode.SORTING_HELPER).orElseThrow().id();
        long driverRole = driver.get().roleId();
        var now = BusinessClock.now();
        String sortHash = BCrypt.hashpw("sorter123", BCrypt.gensalt(10));
        String helperHash = BCrypt.hashpw("helper123", BCrypt.gensalt(10));
        String driverHash = BCrypt.hashpw("forklift2123", BCrypt.gensalt(10));
        db.runInTransaction(conn -> {
            try (var marker = conn.prepareStatement("INSERT OR IGNORE INTO app_settings VALUES (?, ?)")) {
                marker.setString(1, SEED_KEY);
                marker.setString(2, CURRENT_VERSION);
                if (marker.executeUpdate() != 1) return;
            }
            if (available(conn, "forklift2")) {
                // Only reached by an older demo workspace: the fulfilment driver serves customer orders.
                long deliveryRole = roles.findByCode(RoleCode.DELIVERY_DRIVER).map(r -> r.id()).orElse(driverRole);
                users.insert(conn, "forklift2", driverHash, FULFILMENT_DRIVER.fullName(), deliveryRole, true, now);
            }
            long station;
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT id FROM warehouse_locations WHERE active = 1 AND location_type = 'OPERATOR_STATION' ORDER BY code LIMIT 1")) {
                if (!rs.next()) return;
                station = rs.getLong(1);
            }
            String[] teams = {"Gyerek / Kids", "Pólók / Shirts", "Kabátok / Coats", "Lakástextil / Home textiles", "Vegyes / Mixed"};
            for (int i = 0; i < teams.length; i++) {
                String helperName = demoUsername("helper", i + 1);
                if (!available(conn, helperName)) continue;
                long helper = users.insert(conn, helperName, helperHash,
                        HELPERS.get(i).fullName(), helperRole, true, now);
                long team;
                try (var ps = conn.prepareStatement("INSERT INTO sorting_teams(name,driver_id,station_id) VALUES(?,?,?) RETURNING id")) {
                    ps.setString(1, teams[i]);
                    ps.setLong(2, driver.get().id());
                    ps.setLong(3, station);
                    try (var rs = ps.executeQuery()) { rs.next(); team = rs.getLong(1); }
                }
                try (var ps = conn.prepareStatement("INSERT INTO sorting_team_helpers(team_id,helper_id) VALUES (?,?)")) {
                    ps.setLong(1, team);
                    ps.setLong(2, helper);
                    ps.executeUpdate();
                }
                try (var ps = conn.prepareStatement("INSERT INTO sorting_team_members VALUES (?,?)")) {
                    for (int j = 1; j <= 4; j++) {
                        int number = 4 * i + j;
                        String username = demoUsername("sorter", number);
                        if (!available(conn, username)) continue;
                        ps.setLong(1, users.insert(conn, username, sortHash, SORTERS.get(number - 1).fullName(), sorterRole, true, now));
                        ps.setLong(2, team);
                        ps.executeUpdate();
                    }
                }
            }
            seedRawLots(conn, manager.get().id());
            seedRota(conn, manager.get().id());
        });
    }

    /**
     * The daily collections the transit drivers bring in from their own drop-off areas. Idempotent: a lot
     * reference that already exists is left alone, so this can also run when an older demo workspace is upgraded.
     */
    /**
     * One purchase lot behind each demo collection, assessed the way the law requires.
     *
     * <p>Every one of these is waste: they were separately collected, and separately collected textiles are
     * waste from the moment of collection. Only material a householder hands over in person, assessed there
     * and then, can be anything else — so the demo shows the ordinary case rather than the exception.
     *
     * @return the purchase lot id for each collection, in the same order, or 0 where one already existed
     */
    private static long[] seedPurchaseLots(java.sql.Connection conn, long receivedBy, Object[][] lots)
            throws SQLException {
        long[] ids = new long[lots.length];
        // Collected from the company's own drop-off boxes, except the last, which was bought in.
        String[] channels = {"OWN_COLLECTION", "OWN_COLLECTION", "OWN_COLLECTION",
                "OWN_COLLECTION", "HOUSEHOLD_COLLECTION", "COMMERCIAL_PURCHASE"};
        String[] prices = {"0", "0", "0", "0", "0.42", "0.53"};
        String insert = """
                INSERT OR IGNORE INTO purchase_lots(reference, origin_country, collection_channel,
                    customs_code, customs_description, purchase_price_per_kg, currency, gross_weight_kg,
                    net_weight_kg, legal_status, assessed_by, assessed_at, assessment_basis,
                    assessment_place, received_by, received_on, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,'WASTE',?,?,?,?,?,?,?,?)
                """;
        String stamp = hu.due.rewear.dao.DaoDateUtil.format(BusinessClock.now());
        for (int i = 0; i < lots.length; i++) {
            String reference = "PL-2026-" + String.format("%04d", i + 1);
            java.math.BigDecimal net = java.math.BigDecimal.valueOf((Long) lots[i][3], 3);
            java.math.BigDecimal gross = net.multiply(new java.math.BigDecimal("1.04"))
                    .setScale(3, java.math.RoundingMode.HALF_UP);
            try (var ps = conn.prepareStatement(insert)) {
                ps.setString(1, reference);
                ps.setString(2, "HU");
                ps.setString(3, channels[i]);
                ps.setString(4, "6309 00 00");
                ps.setString(5, "Textile waste from the population");
                ps.setString(6, prices[i]);
                ps.setString(7, "EUR");
                ps.setString(8, gross.toPlainString());
                ps.setString(9, net.toPlainString());
                ps.setLong(10, receivedBy);
                ps.setString(11, stamp);
                ps.setString(12, "Separately collected textiles, assessed on arrival");
                ps.setString(13, (String) lots[i][2]);
                ps.setLong(14, receivedBy);
                ps.setString(15, BusinessClock.today().minusDays(lots.length - i).toString());
                ps.setString(16, stamp);
                ps.setString(17, stamp);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("SELECT id FROM purchase_lots WHERE reference = ?")) {
                ps.setString(1, reference);
                try (var rs = ps.executeQuery()) {
                    ids[i] = rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }
        return ids;
    }

    private static void seedRawLots(java.sql.Connection conn, long receivedBy) throws SQLException {
        long[] storage = new long[3];
        int found = 0;
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT id FROM warehouse_locations WHERE active=1 AND location_type='STORAGE' ORDER BY code")) {
            while (rs.next() && found < storage.length) storage[found++] = rs.getLong(1);
        }
        Object[][] lots = {
                {"RAW-2026-0001", "Vegyes használt ruha / Mixed used clothing", "Budapest és Pest megye", 12_000_000L},
                {"RAW-2026-0002", "Vegyes használt ruha / Mixed used clothing", "Győr-Moson-Sopron", 8_400_000L},
                {"RAW-2026-0003", "Téli kabát és pulóver / Winter coats and knitwear", "Borsod-Abaúj-Zemplén", 6_250_000L},
                {"RAW-2026-0004", "Gyerekruha / Kids clothing", "Dunaújváros és környéke", 4_800_000L},
                {"RAW-2026-0005", "Lakástextil / Home textiles", "Szeged és Csongrád", 3_100_000L},
                {"RAW-2026-0006", "Cipő és táska / Shoes and bags", "Debrecen és Hajdú-Bihar", 2_400_000L}
        };
        // Each collection descends from a purchase lot, so the demo shows the provenance chain rather than
        // material that appears from nowhere.
        long[] purchaseLots = seedPurchaseLots(conn, receivedBy, lots);
        try (var ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO sorting_raw_lots(reference,material,source_area,location_id,
                received_grams,available_grams,received_by,received_at,purchase_lot_id)
                VALUES (?,?,?,?,?,?,?,?,?)
                """)) {
            for (int i = 0; i < lots.length; i++) {
                long weight = (Long) lots[i][3];
                // The two most recent collections are still waiting for a forklift driver to store them.
                boolean stored = found > 0 && i < lots.length - 2;
                ps.setString(1, (String) lots[i][0]);
                ps.setString(2, (String) lots[i][1]);
                ps.setString(3, (String) lots[i][2]);
                if (stored) ps.setLong(4, storage[i % found]); else ps.setNull(4, java.sql.Types.INTEGER);
                ps.setLong(5, weight);
                ps.setLong(6, weight);
                ps.setLong(7, receivedBy);
                ps.setString(8, Instant.now().minusSeconds(86_400L * (lots.length - i)).toString());
                if (purchaseLots[i] > 0) ps.setLong(9, purchaseLots[i]); else ps.setNull(9, java.sql.Types.INTEGER);
                ps.executeUpdate();
            }
        }
        // The single unlabelled lot of older demo data gets the same treatment as the new ones.
        try (var ps = conn.prepareStatement("""
                UPDATE sorting_raw_lots SET material=?, source_area=?
                WHERE reference='DEMO-RAW-001' AND material=''
                """)) {
            ps.setString(1, "Vegyes használt ruha / Mixed used clothing");
            ps.setString(2, "Vegyes gyűjtés / Mixed collection");
            ps.executeUpdate();
        }
        if (found > 0) {
            try (var ps = conn.prepareStatement("UPDATE sorting_raw_lots SET location_id=? WHERE reference='DEMO-RAW-001' AND location_id IS NULL")) {
                ps.setLong(1, storage[0]);
                ps.executeUpdate();
            }
        }
    }

    /**
     * A month of shifts around today for the people who actually work them, so the calendar has something to
     * show in a demo. Floor roles rotate through morning, afternoon and night by position in the roster; the
     * weekend is a rest day for most, with a small night crew kept on.
     */
    private static void seedRota(java.sql.Connection conn, long plannerId) throws SQLException {
        record Worker(long id, String role) { }
        var workers = new ArrayList<Worker>();
        try (var ps = conn.prepareStatement("""
                SELECT u.id, r.code FROM users u JOIN roles r ON r.id=u.role_id
                WHERE u.active=1 AND r.code IN ('SORTER','SORTING_HELPER','OPERATOR','SUPPLY_DRIVER',
                                                'DELIVERY_DRIVER','SHIFT_LEAD','PRODUCTION_LEAD')
                ORDER BY r.code, u.id
                """); var rows = ps.executeQuery()) {
            while (rows.next()) workers.add(new Worker(rows.getLong(1), rows.getString(2)));
        }
        if (workers.isEmpty()) return;

        var today = BusinessClock.today();
        var from = today.minusDays(10);
        String stamp = hu.due.rewear.dao.DaoDateUtil.format(BusinessClock.now());
        String[] shifts = {"MORNING", "AFTERNOON", "NIGHT"};
        int[][] hours = {{6 * 60, 14 * 60}, {14 * 60, 22 * 60}, {22 * 60, 6 * 60}};
        try (var ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO rota_shifts(user_id,work_day,shift_code,start_minute,end_minute,note,created_by,created_at)
                VALUES (?,?,?,?,?,NULL,?,?)
                """)) {
            for (int dayOffset = 0; dayOffset < 31; dayOffset++) {
                var day = from.plusDays(dayOffset);
                boolean weekend = day.getDayOfWeek().getValue() >= 6;
                for (int i = 0; i < workers.size(); i++) {
                    Worker worker = workers.get(i);
                    // Each person moves to the next shift every week, so the pattern looks like a real rotation.
                    int slot = (i + dayOffset / 7) % shifts.length;
                    boolean resting = weekend && (i + dayOffset) % 4 != 0;
                    ps.setLong(1, worker.id());
                    ps.setString(2, day.toString());
                    ps.setString(3, resting ? "OFF" : shifts[slot]);
                    ps.setInt(4, resting ? 0 : hours[slot][0]);
                    ps.setInt(5, resting ? 0 : hours[slot][1]);
                    ps.setLong(6, plannerId);
                    ps.setString(7, stamp);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private String seedVersion() {
        try (var conn = db.getConnection();
             var ps = conn.prepareStatement("SELECT setting_value FROM app_settings WHERE setting_key = ?")) {
            ps.setString(1, SEED_KEY);
            try (var rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException ex) {
            throw new hu.due.rewear.dao.DaoException("Could not check sorting demo data", ex);
        }
    }

    /** Gives an existing demo workspace a month of shifts, without touching anything else. */
    private void upgradeRota() {
        var manager = users.findByUsername("manager");
        if (manager.isEmpty()) return;
        db.runInTransaction(conn -> {
            try (var marker = conn.prepareStatement("""
                    UPDATE app_settings SET setting_value = ? WHERE setting_key = ? AND setting_value = '3'
                    """)) {
                marker.setString(1, CURRENT_VERSION);
                marker.setString(2, SEED_KEY);
                if (marker.executeUpdate() != 1) return;
            }
            seedRota(conn, manager.get().id());
        });
    }

    /** Gives an existing demo workspace the collection lines, without touching anything else. */
    private void upgradeRawLots() {
        var manager = users.findByUsername("manager");
        if (manager.isEmpty()) return;
        db.runInTransaction(conn -> {
            try (var marker = conn.prepareStatement("""
                    UPDATE app_settings SET setting_value = ? WHERE setting_key = ? AND setting_value = '2'
                    """)) {
                marker.setString(1, "3");
                marker.setString(2, SEED_KEY);
                if (marker.executeUpdate() != 1) return;
            }
            seedRawLots(conn, manager.get().id());
        });
    }

    private void upgradeLegacyNames() {
        db.runInTransaction(conn -> {
            try (var marker = conn.prepareStatement("""
                    UPDATE app_settings SET setting_value = ? WHERE setting_key = ? AND setting_value = '1'
                    """)) {
                marker.setString(1, "2");
                marker.setString(2, SEED_KEY);
                if (marker.executeUpdate() != 1) return;
            }
            try (var ps = conn.prepareStatement("""
                    UPDATE users SET full_name = ?, last_name = ?, first_name = ?
                    WHERE username = ? COLLATE NOCASE
                        AND role_id = (SELECT id FROM roles WHERE code = ?)
                        AND full_name = ? AND last_name = 'Minta' AND first_name = ?
                    """)) {
                queueLegacyRename(ps, "forklift2", RoleCode.DELIVERY_DRIVER, "Kiszállítási Targoncás", FULFILMENT_DRIVER);
                for (int i = 0; i < HELPERS.size(); i++) {
                    int number = i + 1;
                    queueLegacyRename(ps, demoUsername("helper", number), RoleCode.SORTING_HELPER,
                            "Válogatósegéd " + number, HELPERS.get(i));
                }
                for (int i = 0; i < SORTERS.size(); i++) {
                    int number = i + 1;
                    queueLegacyRename(ps, demoUsername("sorter", number), RoleCode.SORTER,
                            "Válogató " + number, SORTERS.get(i));
                }
                ps.executeBatch();
            }
        });
    }

    private static void queueLegacyRename(PreparedStatement ps, String username, RoleCode role,
                                          String oldGivenName, Person person) throws SQLException {
        ps.setString(1, person.fullName());
        ps.setString(2, person.familyName());
        ps.setString(3, person.givenName());
        ps.setString(4, username);
        ps.setString(5, role.name());
        ps.setString(6, "Minta " + oldGivenName);
        ps.setString(7, oldGivenName);
        ps.addBatch();
    }

    private static String demoUsername(String prefix, int number) {
        return String.format(Locale.ROOT, "%s%02d", prefix, number);
    }

    private static boolean available(Connection conn, String username) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT 1 FROM users WHERE lower(username) = ?")) {
            ps.setString(1, username);
            try (var rs = ps.executeQuery()) { return !rs.next(); }
        }
    }
}
