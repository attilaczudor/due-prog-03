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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SortingDemoSeeder {
    private static final String SEED_KEY = "demo.sorting.seeded";
    private static final String CURRENT_VERSION = "7";
    /** How many work days of sorting the demo workspace opens with, the last of them today. */
    private static final int HISTORY_DAYS = 6;
    /** The order a sorter fills the bags in: the premium grade first, the rags last. */
    private static final String[] HISTORY_GRADES = {"CR", "A", "B", "C"};
    /** The rate the demo teams earn on a day they reach the target. */
    private static final int BONUS_BASIS_POINTS = 500;
    /**
     * A day's work for one person, by team. The schema's placeholder target is a tonne a head, which no hand
     * sorter has ever met and which makes every line of the daily board read as a failure. These are the
     * weights this kind of material really moves in a shift, and they differ by team because a winter coat
     * weighs what half a dozen t-shirts weigh.
     */
    private static final Map<String, Long> TEAM_TARGET_GRAMS = Map.of(
            "Gyerek / Kids", 110_000L,
            "Pólók / Shirts", 120_000L,
            "Kabátok / Coats", 150_000L,
            "Lakástextil / Home textiles", 180_000L,
            "Vegyes / Mixed", 130_000L);
    /** Which collection each team works, by reference. */
    private static final Map<String, String> TEAM_LOT = Map.of(
            "Gyerek / Kids", "RAW-2026-0004",
            "Pólók / Shirts", "RAW-2026-0001",
            "Kabátok / Coats", "RAW-2026-0003",
            "Lakástextil / Home textiles", "RAW-2026-0002",
            "Vegyes / Mixed", "RAW-2026-0002");
    /** What each team produces: the target group and product each grade's bag is reported into. */
    private static final Map<String, String[][]> TEAM_OUTPUT = Map.of(
            "Gyerek / Kids", new String[][]{{"K", "MIX"}, {"K", "TSH"}, {"B", "MIX"}, {"K", "JNS"}},
            "Pólók / Shirts", new String[][]{{"L", "TSH"}, {"M", "TSH"}, {"U", "TSH"}, {"L", "DRS"}},
            "Kabátok / Coats", new String[][]{{"L", "JKT"}, {"M", "JKT"}, {"L", "SWT"}, {"M", "SWT"}},
            "Lakástextil / Home textiles", new String[][]{{"H", "MIX"}, {"H", "MIX"}, {"H", "MIX"}, {"H", "MIX"}},
            "Vegyes / Mixed", new String[][]{{"U", "MIX"}, {"L", "DRS"}, {"M", "JNS"}, {"U", "SHO"}});
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
            upgradeSortingHistory();
            upgradeStoppages();
            upgradeHandovers();
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
            seedSortingHistory(conn);
            seedStoppages(conn, new hu.due.rewear.dao.DowntimeDao(db), manager.get().id());
            seedLeaders(conn);
            seedHandovers(conn, new hu.due.rewear.dao.ShiftHandoverDao(db));
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

    /**
     * A worked week behind the demo workspace: finished sorting runs, the graded bags they produced, and
     * today's work still in progress.
     *
     * <p>Without it every sorting screen opens blank, and a blank screen teaches nobody anything. Grade yield
     * has no mix to report, the daily board lists the whole roster at nothing against a target no hand sorter
     * could meet, and there is not one graded bag to label. What is seeded here is the ordinary week this
     * facility would have had: two people per team per day, a grade cascade with a residue in it, and a
     * backlog of bags waiting on the station manager.
     *
     * <p>It stops deliberately short of registering bags into stock. Registering creates inventory packages
     * at a labelling station that has a capacity of its own, and putting those away is a further step again;
     * a seeder that went that far would be simulating the warehouse rather than furnishing a demo, and it
     * would move the stock figures the rest of the demo data states. The last step is left as the station
     * manager's work, which is also what a real Monday morning looks like.
     *
     * <p>Nothing is seeded if the workspace already holds a single sorting request. This runs on upgrade as
     * well as on a fresh install, and a workspace with real work in it is never touched.
     */
    private static void seedSortingHistory(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1 FROM sorting_requests LIMIT 1")) {
            if (rs.next()) return;
        }
        try (var ps = conn.prepareStatement("""
                UPDATE sorting_teams SET target_grams = ?, bonus_basis_points = COALESCE(bonus_basis_points, ?)
                WHERE name = ? AND target_grams = 1000000
                """)) {
            for (var entry : TEAM_TARGET_GRAMS.entrySet()) {
                ps.setLong(1, entry.getValue());
                ps.setInt(2, BONUS_BASIS_POINTS);
                ps.setString(3, entry.getKey());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        List<HistoryTeam> teams = historyTeams(conn);
        Map<String, Long> lots = storedLots(conn);
        Map<String, Long> targets = dictionary(conn, "target_groups");
        Map<String, Long> products = dictionary(conn, "garment_types");
        Map<String, Long> grades = dictionary(conn, "quality_grades");
        Map<String, Long> packaging = dictionary(conn, "package_types");
        boolean ready = !teams.isEmpty() && !lots.isEmpty()
                && grades.keySet().containsAll(List.of(HISTORY_GRADES))
                && packaging.containsKey("A") && packaging.containsKey("C");
        if (!ready) return;

        List<LocalDate> days = historyDays();
        LocalDateTime now = BusinessClock.now();
        for (int di = 0; di < days.size(); di++) {
            LocalDate day = days.get(di);
            boolean today = di == days.size() - 1;
            for (int ti = 0; ti < teams.size(); ti++) {
                HistoryTeam team = teams.get(ti);
                Long lot = lotFor(team, lots);
                if (lot == null) continue;
                String[][] output = TEAM_OUTPUT.get(team.name());
                if (output == null) continue;
                for (int shift = 0; shift < 2; shift++) {
                    long sorter = team.sorterIds().get((2 * di + shift + ti) % team.sorterIds().size());
                    // Earlier days keep to the rota: the morning starts at 06:40 and hands over at 14:20.
                    // Today is measured back from the clock instead, so that whenever the demo is opened,
                    // nothing on the screen has a timestamp in the future.
                    LocalDateTime requested = today
                            ? now.minusHours(shift == 0 ? 5 : 2)
                            : day.atTime(shift == 0 ? 6 : 14, shift == 0 ? 40 : 20);
                    LocalDateTime delivered = requested.plusMinutes(12);
                    LocalDateTime finished = today
                            ? now.minusMinutes(30)
                            : day.atTime(shift == 0 ? 13 : 21, 50);
                    long weight = deliveredGrams(team.targetGrams(), di, ti, shift);
                    if (today && shift == 1) {
                        // Where the afternoon stands right now: three teams are part-way through a load, and
                        // the other two have called for material that no driver has brought yet.
                        if (ti >= 3) {
                            // Called for by name, which is how a sorter asks: the weight is the driver's to
                            // decide when the material is actually loaded.
                            insertRequest(conn, team, sorter, lot, null, null,
                                    now.minusMinutes(40), null, null, null, null);
                            continue;
                        }
                        long open = insertRequest(conn, team, sorter, lot, weight, null,
                                requested, delivered, null, null, null);
                        if (open == 0) continue;
                        for (int g = 0; g < 2; g++) {
                            insertBag(conn, open, targets.get(output[g][0]), products.get(output[g][1]),
                                    grades.get(HISTORY_GRADES[g]),
                                    bagGrams(weight, shareBasisPoints(g, di, ti)),
                                    between(delivered, now, g, 2), null, null, null);
                        }
                        continue;
                    }
                    long processed = processedGrams(weight, di, ti, shift);
                    long request = insertRequest(conn, team, sorter, lot, weight, processed,
                            requested, delivered, finished, day, null);
                    if (request == 0) continue;
                    // The bags of the last two days are still loose: the helper has them to pack, and the
                    // station manager has everything older than that to register.
                    boolean packed = di < days.size() - 2;
                    for (int g = 0; g < HISTORY_GRADES.length; g++) {
                        String grade = HISTORY_GRADES[g];
                        insertBag(conn, request, targets.get(output[g][0]), products.get(output[g][1]),
                                grades.get(grade), bagGrams(processed, shareBasisPoints(g, di, ti)),
                                between(delivered, finished, g, HISTORY_GRADES.length),
                                packed ? packaging.get("CR".equals(grade) ? "C" : "A") : null,
                                packed ? team.helperId() : null,
                                packed ? day.atTime(shift == 0 ? 14 : 22, 10) : null);
                    }
                }
                if (di == 0 && ti == teams.size() - 1) {
                    // One call that came to nothing, because that happens: the material was wanted at
                    // another station before the driver got to it. It is seeded in its own day's turn so
                    // that request numbers still run in the order the work was done.
                    LocalDateTime called = day.atTime(9, 15);
                    insertRequest(conn, team, team.sorterIds().get(0), lot, null, null,
                            called, null, null, null, called.plusMinutes(50));
                }
            }
        }
    }

    /**
     * The week's stoppages: a couple a day, one of them still running.
     *
     * <p>The seeded week has people working through material all day without ever stopping, which no line
     * does. These are the ordinary interruptions — a scale that failed, a forklift that was somewhere else, a
     * meeting, a power cut that stopped the whole floor — so that the stoppage screen opens with something to
     * read and the availability figure is a figure rather than a hundred per cent.
     *
     * <p>One of them is left running. Closing it, and having to say why, is the first thing a reader of this
     * demo does with the record.
     *
     * <p>Nothing is seeded if any stoppage is already logged, or if the week itself is not there: a stoppage
     * attributed to a shift nobody worked would be an invention rather than an example.
     */
    private static void seedStoppages(Connection conn, hu.due.rewear.dao.DowntimeDao downtime, long supervisor)
            throws SQLException {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1 FROM downtime_events LIMIT 1")) {
            if (rs.next()) return;
        }
        List<HistoryTeam> teams = historyTeams(conn);
        if (teams.isEmpty()) return;
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1 FROM sorting_requests WHERE processed_grams IS NOT NULL LIMIT 1")) {
            if (!rs.next()) return;
        }
        Map<String, Long> reasons = new java.util.LinkedHashMap<>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT code, id FROM downtime_reasons WHERE active = 1")) {
            while (rs.next()) reasons.put(rs.getString(1), rs.getLong(2));
        }
        if (reasons.isEmpty()) return;

        List<LocalDate> days = historyDays();
        LocalDateTime now = BusinessClock.now();
        for (Stoppage stoppage : STOPPAGES) {
            if (stoppage.dayIndex() >= days.size()) continue;
            Long reason = reasons.get(stoppage.reasonCode());
            if (reason == null) continue;
            LocalDate day = days.get(stoppage.dayIndex());
            HistoryTeam team = stoppage.teamIndex() < 0 ? null : teams.get(stoppage.teamIndex() % teams.size());
            LocalDateTime started = day.atTime(stoppage.hour(), stoppage.minute());
            LocalDateTime ended = started.plusMinutes(stoppage.minutes());
            Long teamId = team == null ? null : team.id();
            Long stationId = team == null ? null : team.stationId();
            int people = downtime.peopleAffected(conn, teamId, null, day);
            downtime.insertEvent(conn, day, teamId, null, stationId, started, ended, stoppage.minutes(),
                    people, reason, stoppage.note(), supervisor, ended, supervisor, ended);
        }
        // Still stopped: the scale at one station went down half an hour ago and nobody has said why yet.
        HistoryTeam waiting = teams.get(0);
        int people = downtime.peopleAffected(conn, waiting.id(), null, now.toLocalDate());
        downtime.insertEvent(conn, now.toLocalDate(), waiting.id(), null, waiting.stationId(),
                now.minusMinutes(28), null, null, people, null, null, supervisor, now.minusMinutes(28),
                null, null);
    }

    /**
     * One seeded stoppage. A negative team index is the whole floor, which is how a power cut is recorded.
     */
    private record Stoppage(int dayIndex, int teamIndex, String reasonCode, int hour, int minute, int minutes,
                            String note) { }

    private static final List<Stoppage> STOPPAGES = List.of(
            new Stoppage(0, 1, "SUP-WAIT", 9, 20, 35, "Kifogyott a bála, a targonca a másik oldalon volt"),
            new Stoppage(0, 3, "EQP-SCALE", 11, 5, 50, "Mérleg újraindítás, szerviz hívva"),
            new Stoppage(1, -1, "ADM-MEET", 6, 45, 25, "Heti műszakértekezlet"),
            new Stoppage(1, 2, "QUA-DIRTY", 13, 10, 40, "Nedves tétel, külön rakva"),
            new Stoppage(2, 0, "STA-TRAIN", 7, 30, 60, "Új válogató betanítása"),
            new Stoppage(2, 4, "SUP-LATE", 15, 40, 20, null),
            new Stoppage(3, -1, "FAC-POWER", 10, 15, 45, "Áramkimaradás az egész csarnokban"),
            new Stoppage(3, 1, "CHG-LINE", 14, 50, 15, "Átállás gyerekruháról pólóra"),
            new Stoppage(4, 2, "EQP-LABEL", 8, 35, 30, "Címkenyomtató papírelakadás"),
            new Stoppage(4, 3, "STA-BREAK", 12, 20, 12, "A szünet túlnyúlt"),
            new Stoppage(5, 0, "SUP-WAIT", 7, 55, 22, null),
            new Stoppage(5, 4, "EXT-DOCK", 9, 45, 35, "A kapu foglalt volt a kiszállítás miatt"));

    private static final Person PRODUCTION_LEAD = new Person("Tóth", "Gergely");
    private static final Person SHIFT_LEADER = new Person("Orsós", "Marianna");

    /**
     * The two leaders the demo never had.
     *
     * <p>The role hierarchy pass created a production lead and a shift leader and gave neither of them an
     * account, which left the one feature that is squarely the shift leader's — handing a shift over —
     * impossible to show to anybody.
     */
    private void seedLeaders(Connection conn) throws SQLException {
        var now = BusinessClock.now();
        record Leader(String username, String password, RoleCode role, Person person) { }
        for (Leader leader : List.of(
                new Leader("prodlead", "prodlead123", RoleCode.PRODUCTION_LEAD, PRODUCTION_LEAD),
                new Leader("shiftlead", "shiftlead123", RoleCode.SHIFT_LEAD, SHIFT_LEADER))) {
            if (!available(conn, leader.username())) continue;
            var role = roles.findByCode(leader.role());
            if (role.isEmpty()) continue;
            users.insert(conn, leader.username(), BCrypt.hashpw(leader.password(), BCrypt.gensalt(10)),
                    leader.person().fullName(), role.get().id(), true, now);
        }
    }

    /**
     * A week of shift handovers, written by the shift leader.
     *
     * <p>They are seeded with their figures frozen the way a real one is, so the sheet reads as a document of
     * a shift rather than as a live query. One is left unacknowledged — today's morning, the one the person
     * opening the demo is taking over from — and three issues are left open, one of them old enough that the
     * application escalates it the moment it starts.
     */
    private static void seedHandovers(Connection conn, hu.due.rewear.dao.ShiftHandoverDao handovers)
            throws SQLException {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1 FROM shift_handovers LIMIT 1")) {
            if (rs.next()) return;
        }
        Long author = userId(conn, "shiftlead");
        Long station = userId(conn, "operator");
        Long supply = userId(conn, "forklift");
        Long manager = userId(conn, "manager");
        if (author == null || manager == null) return;

        List<LocalDate> days = historyDays();
        LocalDateTime now = BusinessClock.now();
        for (Sheet sheet : SHEETS) {
            if (sheet.dayIndex() >= days.size()) continue;
            LocalDate day = days.get(sheet.dayIndex());
            var shift = hu.due.rewear.model.RotaShift.ShiftCode.valueOf(sheet.shift());
            var window = hu.due.rewear.service.ShiftHandoverService.window(
                    new hu.due.rewear.service.ShiftHandoverService.ShiftSlot(day, shift));
            var figures = handovers.figuresBetween(conn, window[0], window[1]);
            LocalDateTime submitted = window[1].isAfter(now) ? now : window[1];
            long id = handovers.insert(conn, day, shift, author, sheet.equipment(), sheet.staffing(),
                    sheet.safety(), sheet.mustKnow(), figures, submitted);
            // Everything except the last one has been picked up; the newest is what the reader takes over.
            if (sheet.dayIndex() < days.size() - 1) {
                handovers.acknowledge(conn, id, manager, submitted.plusMinutes(12));
            }
            if (sheet.issue() == null) continue;
            Long owner = switch (sheet.owner()) {
                case "operator" -> station;
                case "forklift" -> supply;
                default -> manager;
            };
            if (owner == null) owner = manager;
            long issue = handovers.insertIssue(conn, id, sheet.issue(), owner,
                    day.plusDays(sheet.dueInDays()), submitted);
            if (sheet.resolved()) {
                handovers.resolveIssue(conn, issue, manager, "Megrendelve, másnap megérkezett",
                        submitted.plusHours(20));
            }
        }
    }

    private static Long userId(Connection conn, String username) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT id FROM users WHERE lower(username) = ?")) {
            ps.setString(1, username);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** One seeded handover sheet. The wording is a shift leader's, not a technical writer's. */
    private record Sheet(int dayIndex, String shift, String equipment, String staffing, String safety,
                         String mustKnow, String issue, String owner, int dueInDays, boolean resolved) { }

    private static final List<Sheet> SHEETS = List.of(
            new Sheet(1, "MORNING",
                    "A 2-es mérleg kalibrálva, a bálázó rendben.",
                    "Teljes létszám, Kiss Zsófia délután jött be.",
                    "Nem volt esemény.",
                    "A 3-as állomásra papír kell a címkenyomtatóba, mielőtt elindul a szalag.",
                    "Címkenyomtató papír utánrendelése", "operator", 2, true),
            new Sheet(2, "AFTERNOON",
                    "Minden eszköz működik, a bálázót átzsíroztuk.",
                    "Két ember hiányzott, a vegyes csapat vette át a munkát.",
                    "Nedves padló a kapunál, feltörölve, tábla kint.",
                    "A nedves tétel a 4-es állomáson külön van rakva, ne menjen bele a bálába.",
                    "Nedves tétel szétválogatása és átmérlegelése", "operator", 3, false),
            new Sheet(3, "MORNING",
                    "A kézi szkenner akkumulátora gyorsan merül, töltőn hagytuk.",
                    "Új válogató betanítása folyik, Takács Petra mellett.",
                    "Nem volt esemény.",
                    "A szkennert reggel ellenőrizni kell, délután megállt vele a címkézés.",
                    "Tartalék szkenner akkumulátor beszerzése", "manager", 4, false),
            new Sheet(4, "AFTERNOON",
                    "A targonca bal oldali villája ferde, bejelentve.",
                    "Teljes létszám.",
                    "A targonca ferde villájával nem szabad bálát emelni.",
                    "A targoncát a szerviz érkezéséig csak üres raklaphoz használjátok.",
                    "Targoncaszerviz kihívása", "forklift", 1, false),
            new Sheet(5, "MORNING",
                    "Minden rendben, a mérlegek reggel ellenőrizve.",
                    "Teljes létszám, a délutános műszakból egy ember átjött segíteni.",
                    "Nem volt esemény.",
                    "A 2-es állomáson áll egy lezáratlan leállás, zárjátok le az okával együtt.",
                    null, "manager", 1, false));

    /**
     * The work days the history covers, oldest first. Today is in it whatever day of the week it is, so the
     * demo always opens with work in progress; the days behind it are weekdays, because the line rests.
     */
    private static List<LocalDate> historyDays() {
        var days = new ArrayList<LocalDate>();
        LocalDate day = BusinessClock.today();
        days.add(day);
        while (days.size() < HISTORY_DAYS) {
            day = day.minusDays(1);
            if (day.getDayOfWeek().getValue() <= 5) days.add(day);
        }
        java.util.Collections.reverse(days);
        return days;
    }

    /**
     * What the driver brought, a day's target give or take fifteen kilos. The spread is what makes the board
     * worth looking at: a target every single person meets exactly is not a target anybody is managing.
     */
    private static long deliveredGrams(long targetGrams, int day, int team, int shift) {
        return targetGrams + ((7 * day + 13 * team + 5 * shift) % 7 - 3) * 5_000L;
    }

    /** Most loads are worked through entirely; now and then a few kilos go back to the shelf unsorted. */
    private static long processedGrams(long delivered, int day, int team, int shift) {
        return (day + team + shift) % 5 == 0 ? delivered - 8_000 : delivered;
    }

    /**
     * The share of processed input that came out as one grade, in basis points. The cascade shifts by team and
     * drifts by day, because material differs and people get better at reading it; the four grades never add
     * up to the whole, and what is left over is the residue the yield report shows as a line of its own.
     */
    private static int shareBasisPoints(int grade, int day, int team) {
        return switch (grade) {
            case 0 -> 400 + 20 * team;
            case 1 -> 2600 + 40 * day;
            case 2 -> 3500 - 20 * day;
            default -> 2300 + 25 * team;
        };
    }

    /** A bag weight to the ten grams, because that is the precision the QR label carries. */
    private static long bagGrams(long processed, int basisPoints) {
        return processed * basisPoints / 10_000 / 10 * 10;
    }

    /**
     * Which collection a team works. The teams and the collections are both seeded here, so the pairing is
     * stated outright rather than guessed from the names; a team whose own line is not on a shelf yet takes
     * whatever is stored.
     */
    private static Long lotFor(HistoryTeam team, Map<String, Long> storedLots) {
        Long preferred = storedLots.get(TEAM_LOT.getOrDefault(team.name(), ""));
        if (preferred != null) return preferred;
        return storedLots.values().stream().findFirst().orElse(null);
    }

    /**
     * When the {@code index}th of {@code count} bags came off the bench: spread evenly between the delivery
     * and the end of the run, so a bag is never reported before its material arrived or after the run closed.
     */
    private static LocalDateTime between(LocalDateTime from, LocalDateTime to, int index, int count) {
        var step = java.time.Duration.between(from, to).dividedBy(count + 1L);
        return from.plus(step.multipliedBy(index + 1L));
    }

    /**
     * A sorting timestamp, written the way the sorting service writes one: an instant, not a local time. The
     * module stores the moment and the business day separately, and a local time stored in the instant column
     * is read back an hour or two wrong — or not at all.
     */
    private static String stamp(LocalDateTime localTime) {
        return localTime == null ? null : localTime.atZone(BusinessClock.ZONE).toInstant().toString();
    }

    private record HistoryTeam(long id, String name, long driverId, long stationId, long helperId,
                               long targetGrams, Integer bonusBasisPoints, List<Long> sorterIds) { }

    /** Teams that could actually have worked: they have a helper and at least one sorter. */
    private static List<HistoryTeam> historyTeams(Connection conn) throws SQLException {
        var teams = new ArrayList<HistoryTeam>();
        record Row(long id, String name, long driver, long station, long target, Integer bonus) { }
        var rows = new ArrayList<Row>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT id,name,driver_id,station_id,target_grams,bonus_basis_points"
                     + " FROM sorting_teams ORDER BY id")) {
            while (rs.next()) {
                int bonus = rs.getInt("bonus_basis_points");
                rows.add(new Row(rs.getLong("id"), rs.getString("name"), rs.getLong("driver_id"),
                        rs.getLong("station_id"), rs.getLong("target_grams"), rs.wasNull() ? null : bonus));
            }
        }
        for (Row row : rows) {
            Long helper = null;
            try (var ps = conn.prepareStatement(
                    "SELECT helper_id FROM sorting_team_helpers WHERE team_id = ? ORDER BY helper_id LIMIT 1")) {
                ps.setLong(1, row.id());
                try (var rs = ps.executeQuery()) { if (rs.next()) helper = rs.getLong(1); }
            }
            var sorters = new ArrayList<Long>();
            try (var ps = conn.prepareStatement(
                    "SELECT sorter_id FROM sorting_team_members WHERE team_id = ? ORDER BY sorter_id")) {
                ps.setLong(1, row.id());
                try (var rs = ps.executeQuery()) { while (rs.next()) sorters.add(rs.getLong(1)); }
            }
            if (helper == null || sorters.isEmpty()) continue;
            teams.add(new HistoryTeam(row.id(), row.name(), row.driver(), row.station(), helper,
                    row.target(), row.bonus(), List.copyOf(sorters)));
        }
        return teams;
    }

    private static Map<String, Long> storedLots(Connection conn) throws SQLException {
        var byReference = new java.util.LinkedHashMap<String, Long>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT reference, id FROM sorting_raw_lots"
                     + " WHERE location_id IS NOT NULL AND available_grams > 0 ORDER BY reference")) {
            while (rs.next()) byReference.put(rs.getString(1), rs.getLong(2));
        }
        return byReference;
    }

    /** Code-to-id for one of the four catalogues. The table name is a literal from this class, never input. */
    private static Map<String, Long> dictionary(Connection conn, String table) throws SQLException {
        var byCode = new java.util.LinkedHashMap<String, Long>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT code, id FROM " + table + " WHERE active = 1")) {
            while (rs.next()) byCode.put(rs.getString(1), rs.getLong(2));
        }
        return byCode;
    }

    /**
     * One request at whatever stage it reached, with the raw lot debited for it.
     *
     * <p>A finished request is debited its processed weight and an open one its delivered weight, which is
     * the same arithmetic the service performs in two steps: delivery takes the whole load off the shelf and
     * finishing credits back whatever was not worked through. The lot is read before it is written, so the
     * demo can never leave a collection owing more material than it received.
     *
     * @return the new request id, or 0 if the lot could not cover the delivery
     */
    private static long insertRequest(Connection conn, HistoryTeam team, long sorter, Long lotId, Long delivered,
                                      Long processed, LocalDateTime requestedAt, LocalDateTime deliveredAt,
                                      LocalDateTime finishedAt, LocalDate workDay, LocalDateTime cancelledAt)
            throws SQLException {
        if (lotId != null && delivered != null) {
            long debit = processed == null ? delivered : processed;
            try (var ps = conn.prepareStatement(
                    "UPDATE sorting_raw_lots SET available_grams = available_grams - ?"
                            + " WHERE id = ? AND available_grams >= ?")) {
                ps.setLong(1, debit);
                ps.setLong(2, lotId);
                ps.setLong(3, debit);
                if (ps.executeUpdate() != 1) return 0;
            }
        }
        long id;
        try (var ps = conn.prepareStatement("""
                INSERT INTO sorting_requests(sorter_id,team_id,driver_id,station_id,target_grams,
                    bonus_basis_points,requested_at,requested_lot_id,lot_id,delivered_grams,delivered_at,
                    processed_grams,finished_at,work_day,cancelled_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """)) {
            ps.setLong(1, sorter);
            ps.setLong(2, team.id());
            ps.setLong(3, team.driverId());
            ps.setLong(4, team.stationId());
            ps.setLong(5, team.targetGrams());
            if (team.bonusBasisPoints() == null) ps.setNull(6, java.sql.Types.INTEGER);
            else ps.setInt(6, team.bonusBasisPoints());
            ps.setString(7, stamp(requestedAt));
            if (lotId == null) ps.setNull(8, java.sql.Types.INTEGER); else ps.setLong(8, lotId);
            if (delivered == null) {
                ps.setNull(9, java.sql.Types.INTEGER);
                ps.setNull(10, java.sql.Types.INTEGER);
                ps.setNull(11, java.sql.Types.VARCHAR);
            } else {
                ps.setLong(9, lotId);
                ps.setLong(10, delivered);
                ps.setString(11, stamp(deliveredAt));
            }
            if (processed == null) {
                ps.setNull(12, java.sql.Types.INTEGER);
                ps.setNull(13, java.sql.Types.VARCHAR);
                ps.setNull(14, java.sql.Types.VARCHAR);
            } else {
                ps.setLong(12, processed);
                ps.setString(13, stamp(finishedAt));
                ps.setString(14, workDay.toString());
            }
            ps.setString(15, stamp(cancelledAt));
            try (var rs = ps.executeQuery()) { rs.next(); id = rs.getLong(1); }
        }
        try (var ps = conn.prepareStatement(
                "INSERT INTO sorting_request_helpers(request_id,helper_id) VALUES (?,?)")) {
            ps.setLong(1, id);
            ps.setLong(2, team.helperId());
            ps.executeUpdate();
        }
        return id;
    }

    private static void insertBag(Connection conn, long requestId, Long target, Long product, Long grade,
                                  long grams, LocalDateTime reportedAt, Long packageType, Long packedBy,
                                  LocalDateTime packedAt) throws SQLException {
        if (target == null || product == null || grade == null || grams <= 0) return;
        try (var ps = conn.prepareStatement("""
                INSERT INTO sorting_bags(request_id,target_group_id,garment_type_id,quality_grade_id,
                    weight_grams,reported_at,package_type_id,packed_at,packed_by,pack_day)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setLong(1, requestId);
            ps.setLong(2, target);
            ps.setLong(3, product);
            ps.setLong(4, grade);
            ps.setLong(5, grams);
            ps.setString(6, stamp(reportedAt));
            if (packageType == null) {
                ps.setNull(7, java.sql.Types.INTEGER);
                ps.setNull(8, java.sql.Types.VARCHAR);
                ps.setNull(9, java.sql.Types.INTEGER);
                ps.setNull(10, java.sql.Types.VARCHAR);
            } else {
                ps.setLong(7, packageType);
                ps.setString(8, stamp(packedAt));
                ps.setLong(9, packedBy);
                ps.setString(10, packedAt.toLocalDate().toString());
            }
            ps.executeUpdate();
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

    /**
     * Gives an existing demo workspace the two leaders it never had and the week's handovers.
     *
     * <p>The role hierarchy created a production lead and a shift leader; nobody held either, which made the
     * one feature that is squarely the shift leader's impossible to show. They have accounts now.
     */
    private void upgradeHandovers() {
        db.runInTransaction(conn -> {
            try (var marker = conn.prepareStatement("""
                    UPDATE app_settings SET setting_value = ? WHERE setting_key = ? AND setting_value = '6'
                    """)) {
                marker.setString(1, CURRENT_VERSION);
                marker.setString(2, SEED_KEY);
                if (marker.executeUpdate() != 1) return;
            }
            seedLeaders(conn);
            seedHandovers(conn, new hu.due.rewear.dao.ShiftHandoverDao(db));
        });
    }

    /** Gives an existing demo workspace the week's stoppages, without touching anything else. */
    private void upgradeStoppages() {
        var manager = users.findByUsername("manager");
        if (manager.isEmpty()) return;
        db.runInTransaction(conn -> {
            try (var marker = conn.prepareStatement("""
                    UPDATE app_settings SET setting_value = ? WHERE setting_key = ? AND setting_value = '5'
                    """)) {
                marker.setString(1, "6");
                marker.setString(2, SEED_KEY);
                if (marker.executeUpdate() != 1) return;
            }
            seedStoppages(conn, new hu.due.rewear.dao.DowntimeDao(db), manager.get().id());
        });
    }

    /** Gives an existing demo workspace the worked week, without touching anything else. */
    private void upgradeSortingHistory() {
        db.runInTransaction(conn -> {
            try (var marker = conn.prepareStatement("""
                    UPDATE app_settings SET setting_value = ? WHERE setting_key = ? AND setting_value = '4'
                    """)) {
                marker.setString(1, "5");
                marker.setString(2, SEED_KEY);
                if (marker.executeUpdate() != 1) return;
            }
            seedSortingHistory(conn);
        });
    }

    /** Gives an existing demo workspace a month of shifts, without touching anything else. */
    private void upgradeRota() {
        var manager = users.findByUsername("manager");
        if (manager.isEmpty()) return;
        db.runInTransaction(conn -> {
            try (var marker = conn.prepareStatement("""
                    UPDATE app_settings SET setting_value = ? WHERE setting_key = ? AND setting_value = '3'
                    """)) {
                marker.setString(1, "4");
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
