package hu.due.rewear.config;

import hu.due.rewear.dao.DaoDateUtil;
import hu.due.rewear.dao.DaoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What shape this database is in, written down inside it.
 *
 * <p>Until this existed there was no way to ask. The schema was brought up to date by a run of idempotent
 * steps — add this column if it is missing, rename that role, rebuild this table — and the only way to tell
 * what a given file had been through was to inspect it for the columns a particular step adds. That is
 * tolerable while one program opens one file on one computer, and it stops being tolerable the moment a
 * second thing speaks to the same data: a handheld that syncs has to know whether the other end understands
 * what it is about to send, and "look for a column" is not an answer it can give.
 *
 * <p>So the number is recorded, and so is each time the database reached one. A database that existed before
 * versioning gets its first row now, with {@code applied_at} left null: it was brought up to date at some
 * point nobody wrote down, and inventing a date for it would be worse than admitting that.
 */
public final class SchemaVersion {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaVersion.class);

    /**
     * The shape the bundled schema describes.
     *
     * <p>Bump this whenever a change to {@code schema.sql} or to the upgrade steps changes what another
     * program would have to understand, and say what changed in {@link #NOTE}.
     */
    public static final int CURRENT = 1;

    /** What this version is, in one line, for somebody reading the table rather than the code. */
    public static final String NOTE =
            "56 tables; moments stored as UTC instants, business dates as local dates";

    /** One row of the table: a version this database reached, and when it did. */
    public record Reached(int version, String note, LocalDateTime appliedAt, LocalDateTime recordedAt) {

        /** True when the version was already in place before this database started keeping the record. */
        public boolean predatesTheRecord() {
            return appliedAt == null;
        }
    }

    private SchemaVersion() {
    }

    /**
     * Records the current version, if this database has not reached it yet.
     *
     * <p>The table itself is declared in {@code schema.sql} with every other table, because it is one.
     *
     * @param schemaExisted whether tables were already present before this run, which is the difference
     *                      between a version this run applied and one that was simply already true
     */
    public static void record(Connection conn, boolean schemaExisted) throws SQLException {
        if (current(conn).orElse(0) >= CURRENT) {
            return;
        }
        // A plain insert, not one that ignores a clash: whether this database has already reached this
        // version is asked above, and two answers to that question disagreeing is worth hearing about.
        try (var ps = conn.prepareStatement(
                "INSERT INTO schema_versions(version, note, applied_at, recorded_at) VALUES (?,?,?,?)")) {
            ps.setInt(1, CURRENT);
            ps.setString(2, schemaExisted
                    ? NOTE + " (recorded when versioning was introduced; the schema was already current)"
                    : NOTE);
            // A database that predates the record was brought up to date at a moment nobody wrote down.
            ps.setString(3, schemaExisted ? null : DaoDateUtil.now());
            ps.setString(4, DaoDateUtil.now());
            ps.executeUpdate();
        }
        LOG.info("Database schema recorded at version {}", CURRENT);
    }

    /** The highest version this database has reached, or empty if it keeps no record yet. */
    public static Optional<Integer> current(Connection conn) {
        if (!tableExists(conn)) {
            return Optional.empty();
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_versions")) {
            if (rs.next()) {
                int version = rs.getInt(1);
                return rs.wasNull() ? Optional.empty() : Optional.of(version);
            }
            return Optional.empty();
        } catch (SQLException ex) {
            throw new DaoException("Could not read the database schema version", ex);
        }
    }

    /** Every version this database has reached, oldest first. */
    public static List<Reached> history(Connection conn) {
        if (!tableExists(conn)) {
            return List.of();
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT version, note, applied_at, recorded_at FROM schema_versions ORDER BY version")) {
            List<Reached> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Reached(rs.getInt("version"), rs.getString("note"),
                        DaoDateUtil.parse(rs.getString("applied_at")),
                        DaoDateUtil.parse(rs.getString("recorded_at"))));
            }
            return List.copyOf(rows);
        } catch (SQLException ex) {
            throw new DaoException("Could not read the database schema history", ex);
        }
    }

    private static boolean tableExists(Connection conn) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_versions'")) {
            return rs.next();
        } catch (SQLException ex) {
            throw new DaoException("Could not look for the database schema version", ex);
        }
    }
}
