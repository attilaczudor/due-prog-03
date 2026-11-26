package hu.due.rewear.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Every change to the floor's own data, in the order it happened.
 *
 * <p>An outbox, which the application did not have. The per-domain histories it does have —
 * {@code invoice_events}, {@code nav_report_events}, {@code purchase_lot_events},
 * {@code downtime_events} — are records of business steps, written where somebody thought to write them.
 * None of them answers "what has changed since number 412", which is the only question a device that has
 * been out of contact can usefully ask.
 *
 * <p>The log is written by triggers rather than by the application. That is the whole point: a write that
 * forgets to log itself is exactly the defect this exists to prevent, and the way to make something
 * unforgettable is to take it out of the hands of whoever would forget it. It also means an update made by
 * a repair script, a migration or a future screen is logged without anybody remembering to.
 *
 * <p>What is logged, and what is not. The floor's work is logged: stock, the sorting line, stoppages,
 * handovers, stocktakes, orders and the lots material came in on. The office's is not — users, roles,
 * permissions, settings, invoices, payroll, mail and chat are not changed on a handheld in a dead spot, and
 * an outbox is only owed for what a device can change while it is out of contact. Reference data moves the
 * other way, to the device, and needs no outbox at all.
 */
public final class ChangeLog {

    /**
     * The tables a device could change while out of contact.
     *
     * <p>Each one is logged on insert, update and delete. The list is explicit rather than "every table",
     * because a log of everything would carry the office's work across a sync that has no use for it, and
     * because a guard test can then hold the list to what it says.
     */
    public static final List<String> LOGGED_TABLES = List.of(
            "inventory_packages",
            "stock_movements",
            "orders",
            "order_items",
            "purchase_lots",
            "sorting_raw_lots",
            "sorting_requests",
            "sorting_request_helpers",
            "sorting_bags",
            "downtime_events",
            "shift_handovers",
            "shift_handover_issues",
            "stocktake_sessions",
            "stocktake_counts");

    /** Where the trigger finds the device it is running on, without the application telling it. */
    private static final String DEVICE =
            "(SELECT CAST(setting_value AS INTEGER) FROM app_settings WHERE setting_key = 'device.id')";

    /** While this setting is there, the triggers write nothing. */
    static final String OFF_SETTING = "change_log.off";

    /**
     * Bumped whenever the trigger bodies change, so an installation that has the old ones gets the new.
     * Without it the triggers would have to be dropped and written again on every start, which is
     * eighty-four statements of nothing for a database that already has them.
     */
    private static final int TRIGGER_VERSION = 1;

    private static final String VERSION_SETTING = "change_log.triggers";

    private ChangeLog() {
    }

    /**
     * Runs something with the log switched off, and switches it back on afterwards.
     *
     * <p>For work that is not work: loading the sample warehouse, rewriting a column into a new format,
     * rebuilding a table. None of that is a change anybody made on the floor, and a device syncing
     * afterwards should not be handed every row in the database as though the night shift had touched them
     * all. The triggers go back on even if the work fails, because the alternative is an installation that
     * quietly stops recording.
     */
    public static void withoutLogging(DatabaseManager db, Runnable action) {
        db.runInTransaction(conn -> setLogging(conn, false));
        try {
            action.run();
        } finally {
            db.runInTransaction(conn -> setLogging(conn, true));
        }
    }

    public static String triggerName(String table, String operation) {
        return "log_" + table + "_" + operation.toLowerCase(java.util.Locale.ROOT);
    }

    /** Every trigger this log is made of, in the order they are created. */
    public static List<String> triggerNames() {
        List<String> names = new ArrayList<>();
        for (String table : LOGGED_TABLES) {
            for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
                names.add(triggerName(table, operation));
            }
        }
        return List.copyOf(names);
    }

    /**
     * Writes the triggers.
     *
     * <p>A deleted row is logged by the id it had, because that is all there is left of it and all the
     * other end needs: the row with this id, in this table, is gone.
     */
    static void install(Connection conn) throws SQLException {
        // Writing them again on every start costs eighty-four statements and buys nothing, so the version
        // they were written at is kept beside them and only a change to the bodies rewrites them.
        if (installedVersion(conn) == TRIGGER_VERSION && triggerCount(conn) == triggerNames().size()) {
            return;
        }
        drop(conn);
        try (Statement stmt = conn.createStatement()) {
            for (String table : LOGGED_TABLES) {
                stmt.execute(trigger(table, "INSERT", "NEW"));
                stmt.execute(trigger(table, "UPDATE", "NEW"));
                stmt.execute(trigger(table, "DELETE", "OLD"));
            }
        }
        setSetting(conn, VERSION_SETTING, Integer.toString(TRIGGER_VERSION));
    }

    /**
     * Switches the log off, or on again.
     *
     * <p>A setting rather than taking the triggers away: dropping and writing eighty-four triggers around
     * every piece of housekeeping made opening a workspace measurably slower, and a flag the triggers read
     * costs one indexed lookup on a write that is already doing several.
     */
    static void setLogging(Connection conn, boolean on) throws SQLException {
        if (on) {
            try (var ps = conn.prepareStatement("DELETE FROM app_settings WHERE setting_key = ?")) {
                ps.setString(1, OFF_SETTING);
                ps.executeUpdate();
            }
        } else {
            setSetting(conn, OFF_SETTING, "1");
        }
    }

    /** True while changes are being recorded. */
    static boolean isLogging(Connection conn) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT 1 FROM app_settings WHERE setting_key = ?")) {
            ps.setString(1, OFF_SETTING);
            try (var rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    private static void setSetting(Connection conn, String key, String value) throws SQLException {
        try (var ps = conn.prepareStatement("INSERT INTO app_settings(setting_key, setting_value)"
                + " VALUES (?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private static int installedVersion(Connection conn) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT setting_value FROM app_settings WHERE setting_key = ?")) {
            ps.setString(1, VERSION_SETTING);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Integer.parseInt(rs.getString(1).trim()) : 0;
            }
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int triggerCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'log\\_%' ESCAPE '\\'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Takes the triggers away.
     *
     * <p>Called before the schema's own upgrade steps run. Rewriting a column into a new format, adding a
     * column or rebuilding a table is not a change anybody made to the work — it is the application tidying
     * up after itself — and a device syncing afterwards should not be handed every row in the database as
     * though the night shift had touched them all.
     */
    static void drop(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String name : triggerNames()) {
                stmt.execute("DROP TRIGGER IF EXISTS " + name);
            }
        }
    }

    private static String trigger(String table, String operation, String row) {
        String rowId = "sorting_request_helpers".equals(table)
                // The one logged table with no id of its own: a request and a helper, and the pair is the
                // row. The request is what the other end needs to go and look at.
                ? row + ".request_id"
                : row + ".id";
        return """
                CREATE TRIGGER IF NOT EXISTS %1$s AFTER %2$s ON %3$s
                WHEN (SELECT 1 FROM app_settings WHERE setting_key = '%6$s') IS NULL
                BEGIN
                    INSERT INTO change_log(table_name, row_id, operation, device_id)
                    VALUES ('%3$s', %4$s, '%2$s', %5$s);
                END""".formatted(triggerName(table, operation), operation, table, rowId, DEVICE, OFF_SETTING);
    }
}
