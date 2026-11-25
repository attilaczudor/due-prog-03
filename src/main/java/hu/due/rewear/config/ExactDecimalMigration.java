package hu.due.rewear.config;

import hu.due.rewear.dao.DaoException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Moves money and mass from SQLite's {@code REAL} to an exact decimal string.
 * <p>
 * A binary floating point number cannot hold every decimal amount, so an amount written as {@code REAL} is
 * not always the amount that comes back, and adding many of them drifts. Each money column is rewritten as
 * text. SQLite already renders a stored float as a short decimal, so the conversion itself is clean; the
 * {@code printf} step is there to cap an amount that carries more precision than money ever should — a price
 * left behind by a division, say — at four decimals, which is more than any currency used here needs.
 * <p>
 * Column types in SQLite belong to the table definition, so each table is rebuilt once from the current
 * schema, its indexes recreated and its ids preserved.
 */
final class ExactDecimalMigration {

    /**
     * Every column that must be exact, by table: money, and mass in kilograms. Percentages and exchange
     * rates are left alone — the values in use are exact in binary and they are not charged per unit.
     */
    private static final Map<String, List<String>> EXACT_COLUMNS = Map.of(
            "garment_types", List.of("price_eur_per_kg", "price_huf_per_kg"),
            "quality_grades", List.of("default_price_per_kg", "default_price_huf_per_kg"),
            "package_types", List.of("tare_weight_kg", "max_weight_kg"),
            "warehouse_locations", List.of("max_capacity_kg"),
            "inventory_packages", List.of("weight_kg"),
            "orders", List.of("total_price", "total_weight_kg"),
            "order_items", List.of("price_per_kg", "line_total"),
            "invoices", List.of("net_amount", "vat_amount", "gross_amount"),
            "invoice_items", List.of("unit_price", "line_net", "quantity"));

    private final DatabaseManager db;

    ExactDecimalMigration(DatabaseManager db) {
        this.db = db;
    }

    void upgrade(String schema) {
        try (var conn = db.getConnection(); var statement = conn.createStatement()) {
            List<String> stale = EXACT_COLUMNS.keySet().stream().sorted()
                    .filter(table -> storesAnyAsReal(conn, table)).toList();
            if (stale.isEmpty()) {
                return;
            }
            statement.execute("PRAGMA foreign_keys = OFF");
            conn.setAutoCommit(false);
            try {
                for (String table : stale) {
                    // Normalise the numbers while the column is still REAL, then rebuild the table as text.
                    for (String column : EXACT_COLUMNS.get(table)) {
                        statement.executeUpdate("UPDATE " + table + " SET " + column
                                + " = rtrim(rtrim(printf('%.4f', " + column + "), '0'), '.')");
                    }
                    SortingRosterMigration.rebuild(conn, schema, table);
                    recreateIndexes(conn, schema, table);
                }
                try (ResultSet problem = statement.executeQuery("PRAGMA foreign_key_check")) {
                    if (problem.next()) {
                        throw new SQLException("Invalid foreign key after the exact-decimal update: "
                                + problem.getString(1) + " row " + problem.getString(2)
                                + " -> " + problem.getString(3));
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new DaoException("Could not store money and mass as exact decimals", ex);
        }
    }

    /** True while the table's own definition still declares any of its exact columns as {@code REAL}. */
    private static boolean storesAnyAsReal(Connection conn, String table) {
        try (var ps = conn.prepareStatement("PRAGMA table_info(" + table + ")");
             ResultSet rs = ps.executeQuery()) {
            List<String> exact = EXACT_COLUMNS.get(table);
            while (rs.next()) {
                if (exact.contains(rs.getString("name")) && "REAL".equalsIgnoreCase(rs.getString("type"))) {
                    return true;
                }
            }
            return false;
        } catch (SQLException ex) {
            throw new DaoException("Could not inspect " + table, ex);
        }
    }

    private static void recreateIndexes(Connection conn, String schema, String table) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            for (String raw : schema.split(";")) {
                String trimmed = raw.trim();
                String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
                if ((lower.startsWith("create index") || lower.startsWith("create unique index"))
                        && lower.contains(" on " + table.toLowerCase(java.util.Locale.ROOT) + "(")) {
                    statement.execute(trimmed);
                }
            }
        }
    }
}
