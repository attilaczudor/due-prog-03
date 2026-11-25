package hu.due.rewear.config;

import hu.due.rewear.dao.DaoException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lets a package be held in quarantine and records the movements that put it there and take it back.
 * <p>
 * Both the package status and the movement type are SQLite {@code CHECK} constraints, which cannot be widened
 * in place, so each table is rebuilt once from the current definition. Rebuilding drops the table's indexes
 * with it, so they are recreated from the same schema afterwards. Ids are preserved, which keeps every
 * foreign key pointing where it did.
 */
final class QuarantineMigration {

    private final DatabaseManager db;

    QuarantineMigration(DatabaseManager db) {
        this.db = db;
    }

    void upgrade(String schema) {
        try (var conn = db.getConnection(); var statement = conn.createStatement()) {
            boolean packagesNeedIt = !storedDefinition(conn, "inventory_packages").contains("QUARANTINED");
            boolean movementsNeedIt = !storedDefinition(conn, "stock_movements").contains("QUARANTINE");
            if (!packagesNeedIt && !movementsNeedIt) {
                return;
            }
            // Other tables reference these by id, so foreign key checks wait until both are back in place.
            statement.execute("PRAGMA foreign_keys = OFF");
            conn.setAutoCommit(false);
            try {
                if (packagesNeedIt) {
                    SortingRosterMigration.rebuild(conn, schema, "inventory_packages");
                    recreateIndexes(conn, schema, "inventory_packages");
                }
                if (movementsNeedIt) {
                    SortingRosterMigration.rebuild(conn, schema, "stock_movements");
                    recreateIndexes(conn, schema, "stock_movements");
                }
                try (var problem = statement.executeQuery("PRAGMA foreign_key_check")) {
                    if (problem.next()) {
                        // table, rowid, parent table, which foreign key — all four, so a failure can be traced.
                        throw new SQLException("Invalid foreign key after the quarantine update: "
                                + problem.getString(1) + " row " + problem.getString(2)
                                + " -> " + problem.getString(3) + " (fk " + problem.getString(4) + ")");
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new DaoException("Could not add quarantine to the inventory", ex);
        }
    }

    private static String storedDefinition(Connection conn, String table) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (var rows = ps.executeQuery()) {
                return rows.next() && rows.getString(1) != null ? rows.getString(1) : "";
            }
        }
    }

    /** Re-runs the schema's own {@code CREATE INDEX} statements for a table that was just rebuilt. */
    private static void recreateIndexes(Connection conn, String schema, String table) throws SQLException {
        List<String> statements = new ArrayList<>();
        for (String raw : schema.split(";")) {
            String trimmed = raw.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("create index") || lower.startsWith("create unique index")) {
                int on = lower.indexOf(" on " + table.toLowerCase(Locale.ROOT) + "(");
                if (on > 0) {
                    statements.add(trimmed);
                }
            }
        }
        try (Statement statement = conn.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
