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
import java.util.List;
import java.util.Locale;

final class SortingDemoSeeder {
    private static final String SEED_KEY = "demo.sorting.seeded";
    private static final String CURRENT_VERSION = "2";
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
            if ("1".equals(version)) upgradeLegacyNames();
            return;
        }
        var stationManager = users.findByUsername("operator");
        var driver = users.findByUsername("forklift");
        var manager = users.findByUsername("manager");
        if (stationManager.isEmpty() || driver.isEmpty() || manager.isEmpty()) return;
        User operator = stationManager.get();
        if (operator.roleCode() != RoleCode.OPERATOR || driver.get().roleCode() != RoleCode.FORKLIFT
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
                users.insert(conn, "forklift2", driverHash, FULFILMENT_DRIVER.fullName(), driverRole, true, now);
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
            try (var ps = conn.prepareStatement("""
                    INSERT OR IGNORE INTO sorting_raw_lots(reference,received_grams,available_grams,received_by,received_at)
                    VALUES ('DEMO-RAW-001',12000000,12000000,?,?)
                    """)) {
                ps.setLong(1, manager.get().id());
                ps.setString(2, Instant.now().toString());
                ps.executeUpdate();
            }
        });
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

    private void upgradeLegacyNames() {
        db.runInTransaction(conn -> {
            try (var marker = conn.prepareStatement("""
                    UPDATE app_settings SET setting_value = ? WHERE setting_key = ? AND setting_value = '1'
                    """)) {
                marker.setString(1, CURRENT_VERSION);
                marker.setString(2, SEED_KEY);
                if (marker.executeUpdate() != 1) return;
            }
            try (var ps = conn.prepareStatement("""
                    UPDATE users SET full_name = ?, last_name = ?, first_name = ?
                    WHERE username = ? COLLATE NOCASE
                        AND role_id = (SELECT id FROM roles WHERE code = ?)
                        AND full_name = ? AND last_name = 'Minta' AND first_name = ?
                    """)) {
                queueLegacyRename(ps, "forklift2", RoleCode.FORKLIFT, "Kiszállítási Targoncás", FULFILMENT_DRIVER);
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
