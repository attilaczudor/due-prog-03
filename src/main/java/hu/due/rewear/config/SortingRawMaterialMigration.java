package hu.due.rewear.config;

import hu.due.rewear.dao.DaoException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lets a raw supply request name the raw lot it calls for without stating a weight.
 * <p>
 * Older databases declare {@code sorting_requests.requested_grams NOT NULL}, and SQLite cannot drop that
 * constraint in place, so the table is rebuilt once from the current definition, keeping every row and every
 * column the two versions share. The weight of a request made before this change is unchanged; a new request
 * stores no weight, because the sorter cannot know in advance how much the driver will bring.
 */
final class SortingRawMaterialMigration {
    private final DatabaseManager db;

    SortingRawMaterialMigration(DatabaseManager db) {
        this.db = db;
    }

    void upgrade(String schema) {
        try (var conn = db.getConnection(); var statement = conn.createStatement()) {
            if (!requestWeightIsRequired(conn)) {
                return;
            }
            // Child tables reference sorting_requests by name, so foreign key checks wait until the replacement is in place.
            statement.execute("PRAGMA foreign_keys = OFF");
            conn.setAutoCommit(false);
            try {
                SortingRosterMigration.rebuild(conn, schema, "sorting_requests");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_sorting_requests_sorter_day ON sorting_requests(sorter_id,work_day)");
                try (var problem = statement.executeQuery("PRAGMA foreign_key_check")) {
                    if (problem.next()) {
                        throw new SQLException("Invalid foreign key after raw material update: " + problem.getString(1));
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new DaoException("Could not upgrade raw supply requests", ex);
        }
    }

    private static boolean requestWeightIsRequired(Connection conn) throws SQLException {
        List<String> required = new ArrayList<>();
        try (var statement = conn.createStatement(); var rows = statement.executeQuery("PRAGMA table_info(sorting_requests)")) {
            while (rows.next()) {
                if (rows.getInt("notnull") == 1) {
                    required.add(rows.getString("name"));
                }
            }
        }
        return required.contains("requested_grams");
    }
}
