package hu.due.rewear.config;

import hu.due.rewear.dao.DaoDateUtil;
import hu.due.rewear.dao.DaoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Brings every moment already in the database into the one format moments are written in.
 *
 * <p>Two conventions were in use: the sorting module wrote UTC instants and everything else wrote local
 * date-times. A local date-time is not a moment — it is a moment plus a time zone somebody has to remember —
 * and the hour that repeats every autumn means two different moments can be written the same way. Ordering
 * records by one is therefore guesswork for one hour a year, which is one hour more than a handheld syncing
 * from a dead spot can afford.
 *
 * <p>Which columns hold moments is read from the database rather than listed here, by the naming rule the
 * convention states: a column whose name ends in {@code _at} holds a moment. A list written out by hand
 * would be one more thing to forget the next time a table is added.
 *
 * <p>The conversion is done in Java, one row at a time, because SQLite has no time zone database: it can add
 * a fixed offset but it does not know that Budapest was an hour ahead of UTC in November and two in July.
 * Only rows that are not already in the right format are touched, so this costs nothing on a database that
 * is already converted and can be interrupted and resumed.
 */
final class TimestampMigration {

    private static final Logger LOG = LoggerFactory.getLogger(TimestampMigration.class);

    private final DatabaseManager db;

    TimestampMigration(DatabaseManager db) {
        this.db = db;
    }

    /** One column that holds moments. */
    private record MomentColumn(String table, String column) { }

    /**
     * Rewrites every stored moment that is not already a canonical instant.
     *
     * @return how many values were rewritten, which is zero on a database that has already been converted
     */
    int upgrade() {
        try (Connection conn = db.getConnection()) {
            List<MomentColumn> columns = momentColumns(conn);
            conn.setAutoCommit(false);
            int rewritten = 0;
            try {
                for (MomentColumn column : columns) {
                    rewritten += convert(conn, column);
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
            if (rewritten > 0) {
                LOG.info("Rewrote {} stored moments into UTC instants", rewritten);
            }
            return rewritten;
        } catch (SQLException ex) {
            throw new DaoException("Could not bring stored moments into one format", ex);
        }
    }

    /** Every {@code _at} column of every ordinary table, as the database itself reports them. */
    private static List<MomentColumn> momentColumns(Connection conn) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT name FROM sqlite_master WHERE type = 'table'
                       AND name NOT LIKE 'sqlite_%' ORDER BY name""")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        List<MomentColumn> columns = new ArrayList<>();
        for (String table : tables) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
                while (rs.next()) {
                    String column = rs.getString("name");
                    if (column.endsWith("_at")) {
                        columns.add(new MomentColumn(table, column));
                    }
                }
            }
        }
        return columns;
    }

    private static int convert(Connection conn, MomentColumn column) throws SQLException {
        String table = quote(column.table());
        String field = quote(column.column());
        List<String> values = new ArrayList<>();
        List<Long> rowIds = new ArrayList<>();
        // A row is identified by its rowid, because these tables have every shape of primary key between
        // them and some have none of their own.
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT rowid, " + field + " FROM " + table
                     + " WHERE " + field + " IS NOT NULL AND " + field + " <> ''")) {
            while (rs.next()) {
                String raw = rs.getString(2);
                if (DaoDateUtil.isCanonicalMoment(raw)) {
                    continue;
                }
                rowIds.add(rs.getLong(1));
                values.add(raw);
            }
        }
        if (rowIds.isEmpty()) {
            return 0;
        }
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE " + table + " SET " + field + " = ? WHERE rowid = ?")) {
            for (int i = 0; i < rowIds.size(); i++) {
                update.setString(1, DaoDateUtil.format(DaoDateUtil.parseInstant(values.get(i))));
                update.setLong(2, rowIds.get(i));
                update.addBatch();
            }
            update.executeBatch();
        }
        return rowIds.size();
    }

    /** SQLite identifier quoting, for the one table whose name could ever need it. */
    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
