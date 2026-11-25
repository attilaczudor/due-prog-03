package hu.due.rewear.config;

import hu.due.rewear.dao.DaoException;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class SortingRosterMigration {
    private static final ZoneId WAREHOUSE_ZONE = ZoneId.of("Europe/Budapest");
    private final DatabaseManager db;

    SortingRosterMigration(DatabaseManager db) {
        this.db = db;
    }

    void upgrade(String schema) {
        try (var conn = db.getConnection(); var statement = conn.createStatement()) {
            if (!columns(conn, "sorting_teams").contains("helper_id")) return;
            // SQLite's table rebuild procedure defers foreign key validation until the replacement tables are in place.
            statement.execute("PRAGMA foreign_keys = OFF");
            conn.setAutoCommit(false);
            try {
                statement.executeUpdate("""
                        INSERT OR IGNORE INTO sorting_team_helpers(team_id,helper_id)
                        SELECT id,helper_id FROM sorting_teams
                        """);
                statement.executeUpdate("""
                        INSERT OR IGNORE INTO sorting_request_helpers(request_id,helper_id)
                        SELECT id,helper_id FROM sorting_requests
                        """);
                backfillPackers(conn);
                rebuild(conn, schema, "sorting_teams");
                rebuild(conn, schema, "sorting_requests");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_sorting_requests_sorter_day ON sorting_requests(sorter_id,work_day)");
                try (var problem = statement.executeQuery("PRAGMA foreign_key_check")) {
                    if (problem.next()) throw new SQLException("Invalid foreign key after sorting roster update: " + problem.getString(1));
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new DaoException("Could not upgrade sorting team assignments", ex);
        }
    }

    private static void backfillPackers(Connection conn) throws SQLException {
        try (var find = conn.prepareStatement("""
                SELECT b.id,b.packed_at,r.helper_id FROM sorting_bags b
                JOIN sorting_requests r ON r.id=b.request_id WHERE b.packed_at IS NOT NULL AND b.packed_by IS NULL
                """); var rows = find.executeQuery();
             var update = conn.prepareStatement("UPDATE sorting_bags SET packed_by=?, pack_day=? WHERE id=?")) {
            while (rows.next()) {
                update.setLong(1, rows.getLong("helper_id"));
                update.setString(2, Instant.parse(rows.getString("packed_at")).atZone(WAREHOUSE_ZONE).toLocalDate().toString());
                update.setLong(3, rows.getLong("id"));
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void rebuild(Connection conn, String schema, String table) throws SQLException {
        String prefix = "CREATE TABLE IF NOT EXISTS " + table + " (";
        String canonical = Arrays.stream(schema.split(";")).map(String::strip)
                .map(statement -> statement.indexOf(prefix) < 0 ? "" : statement.substring(statement.indexOf(prefix)))
                .filter(statement -> !statement.isEmpty()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing definition of " + table));
        String replacement = table + "_new";
        try (var statement = conn.createStatement()) {
            statement.execute("CREATE TABLE " + replacement + " (" + canonical.substring(prefix.length()));
            String columns = String.join(",", columns(conn, replacement));
            statement.executeUpdate("INSERT INTO " + replacement + " (" + columns + ") SELECT " + columns + " FROM " + table);
            statement.execute("DROP TABLE " + table);
            statement.execute("ALTER TABLE " + replacement + " RENAME TO " + table);
        }
    }

    private static List<String> columns(Connection conn, String table) throws SQLException {
        try (var statement = conn.createStatement(); var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            var names = new ArrayList<String>();
            while (rows.next()) names.add(rows.getString("name"));
            return names;
        }
    }
}
