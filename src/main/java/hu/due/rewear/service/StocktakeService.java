package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.DaoDateUtil;
import hu.due.rewear.dao.DaoException;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.MovementType;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.Stocktake;
import hu.due.rewear.model.Stocktake.Count;
import hu.due.rewear.model.Stocktake.Missing;
import hu.due.rewear.model.Stocktake.Outcome;
import hu.due.rewear.model.Stocktake.Session;
import hu.due.rewear.model.Stocktake.Status;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Counting the shelves and correcting the record afterwards.
 * <p>
 * A session covers a shelf, a zone or the whole warehouse. Counting a package records what was really found:
 * the right shelf, a different shelf, a different weight, or damage. Nothing changes while the session is
 * open — the corrections are applied in one transaction when it is closed, each with a stock movement so the
 * change is traceable. A package that was in scope and never counted is reported as missing and is never
 * written off on its own: somebody has to decide what happened to it.
 */
public final class StocktakeService {

    private static final Logger LOG = LoggerFactory.getLogger(StocktakeService.class);

    /** Weighing is never exact, so a difference this small is treated as the same weight. */
    static final long WEIGHT_TOLERANCE_GRAMS = 100;

    private static final String SESSION_SELECT = """
            SELECT s.*, l.code AS scope_location_code, u.full_name AS started_by_name, c.full_name AS closed_by_name
            FROM stocktake_sessions s
            JOIN users u ON u.id = s.started_by
            LEFT JOIN users c ON c.id = s.closed_by
            LEFT JOIN warehouse_locations l ON l.id = s.scope_location_id
            """;

    private static final String COUNT_SELECT = """
            SELECT c.*, p.qr_code, e.code AS expected_code, f.code AS found_code, u.full_name AS counted_by_name
            FROM stocktake_counts c
            JOIN inventory_packages p ON p.id = c.package_id
            JOIN users u ON u.id = c.counted_by
            LEFT JOIN warehouse_locations e ON e.id = c.expected_location_id
            LEFT JOIN warehouse_locations f ON f.id = c.found_location_id
            """;

    /** What closing a session changed. */
    public record Applied(int relocated, int reweighed, int quarantined, int missing) { }

    private final DatabaseManager db;
    private final SessionContext session;
    private final InventoryPackageDao packages;
    private final StockMovementDao movements;

    public StocktakeService(DatabaseManager db, SessionContext session,
                            InventoryPackageDao packages, StockMovementDao movements) {
        this.db = db;
        this.session = session;
        this.packages = packages;
        this.movements = movements;
    }

    public boolean mayCount() {
        return session.getCurrentUser().map(user -> user.roleCode().runsProduction()
                || user.roleCode() == hu.due.rewear.model.RoleCode.OPERATOR).orElse(false);
    }

    /** Opens a session over one shelf, a zone, or the whole warehouse when both are left out. */
    public Session open(String zone, Long locationId, String note) {
        User actor = requireCounter();
        String scopeZone = zone == null || zone.isBlank() ? null : zone.strip().toUpperCase(Locale.ROOT);
        if (scopeZone != null && locationId != null) {
            throw new IllegalArgumentException("Count either one shelf or a zone, not both.");
        }
        long id = db.executeInTransaction(conn -> {
            if (openSessionId(conn) != null) {
                throw new IllegalStateException("Another stocktake is already open. Close it first.");
            }
            if (locationId != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM warehouse_locations WHERE id=? AND active=1")) {
                    ps.setLong(1, locationId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getInt(1) != 1) {
                            throw new IllegalArgumentException("Choose an active warehouse location.");
                        }
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO stocktake_sessions (scope_zone, scope_location_id, status, note, started_by, started_at)
                    VALUES (?,?, 'OPEN', ?,?,?) RETURNING id
                    """)) {
                ps.setString(1, scopeZone);
                if (locationId == null) {
                    ps.setNull(2, Types.INTEGER);
                } else {
                    ps.setLong(2, locationId);
                }
                ps.setString(3, note == null || note.isBlank() ? null : note.strip());
                ps.setLong(4, actor.id());
                ps.setString(5, DaoDateUtil.format(BusinessClock.now()));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        LOG.info("Stocktake {} opened by {}", id, actor.username());
        return findSession(id).orElseThrow();
    }

    public Optional<Session> openSession() {
        return db.executeInTransaction(conn -> {
            Long id = openSessionId(conn);
            return id == null ? Optional.<Session>empty() : findSession(conn, id);
        });
    }

    public Optional<Session> findSession(long id) {
        return db.executeInTransaction(conn -> findSession(conn, id));
    }

    public List<Session> recentSessions(int limit) {
        requireViewer();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SESSION_SELECT + " ORDER BY s.id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<Session> sessions = new ArrayList<>();
                while (rs.next()) {
                    sessions.add(mapSession(rs));
                }
                return sessions;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to list stocktakes", e);
        }
    }

    public List<Count> counts(long sessionId) {
        requireViewer();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_SELECT + " WHERE c.session_id=? ORDER BY c.id")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Count> counted = new ArrayList<>();
                while (rs.next()) {
                    counted.add(mapCount(rs));
                }
                return counted;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to load stocktake counts", e);
        }
    }

    /** Packages the session expected to find that nobody has counted yet. */
    public List<Missing> missing(long sessionId) {
        requireViewer();
        try (Connection conn = db.getConnection()) {
            return missing(conn, sessionId);
        } catch (SQLException e) {
            throw new DaoException("Failed to work out what is missing", e);
        }
    }

    /**
     * Records one counted package. The outcome follows from what was found: a different shelf is a
     * misplacement, a weight outside the tolerance is a reweigh, and a package outside the session's scope is
     * unexpected. Counting the same package again replaces the earlier line.
     */
    public Count count(long sessionId, String rawQrCode, BigDecimal countedKg, boolean damaged, String note) {
        User actor = requireCounter();
        String qr = rawQrCode == null ? "" : rawQrCode.strip().toUpperCase(Locale.ROOT);
        if (qr.isEmpty()) {
            throw new IllegalArgumentException("Scan or enter a package QR code.");
        }
        long id = db.executeInTransaction(conn -> {
            Session stocktake = findSession(conn, sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Stocktake not found."));
            if (!stocktake.open()) {
                throw new IllegalStateException("This stocktake is already closed.");
            }
            InventoryPackage found = packages.findByQrCode(qr)
                    .orElseThrow(() -> new IllegalArgumentException("No package with code " + qr + "."));
            if (found.status() == PackageStatus.SHIPPED || found.status() == PackageStatus.DISCARDED) {
                throw new IllegalStateException("Package " + qr + " has already left the warehouse.");
            }
            long expectedGrams = found.weightKg().movePointRight(3).longValueExact();
            Long counted = countedKg == null ? null : countedKg.movePointRight(3).longValueExact();
            if (counted != null && counted <= 0) {
                throw new IllegalArgumentException("A counted weight must be greater than zero.");
            }

            // The record says the package is at expectedLocation. When a single shelf is being counted,
            // scanning it there means it is really on that shelf and the record is what is wrong.
            long expectedLocation = found.locationId();
            long foundLocation = stocktake.scopeLocationId() != null ? stocktake.scopeLocationId() : found.locationId();
            boolean elsewhere = foundLocation != expectedLocation;

            Outcome outcome;
            if (damaged) {
                outcome = Outcome.DAMAGED;
            } else if (elsewhere) {
                outcome = Outcome.MISPLACED;
            } else if (!inScope(conn, stocktake, found)) {
                outcome = Outcome.UNEXPECTED;
            } else if (counted != null && Math.abs(counted - expectedGrams) > WEIGHT_TOLERANCE_GRAMS) {
                outcome = Outcome.REWEIGHED;
            } else {
                outcome = Outcome.FOUND;
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO stocktake_counts (session_id, package_id, outcome, expected_location_id,
                        found_location_id, expected_grams, counted_grams, note, counted_by, counted_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(session_id, package_id) DO UPDATE SET
                        outcome=excluded.outcome, expected_location_id=excluded.expected_location_id,
                        found_location_id=excluded.found_location_id, counted_grams=excluded.counted_grams,
                        note=excluded.note, counted_by=excluded.counted_by, counted_at=excluded.counted_at
                    """)) {
                ps.setLong(1, sessionId);
                ps.setLong(2, found.id());
                ps.setString(3, outcome.name());
                ps.setLong(4, expectedLocation);
                ps.setLong(5, foundLocation);
                ps.setLong(6, expectedGrams);
                if (counted == null) {
                    ps.setNull(7, Types.INTEGER);
                } else {
                    ps.setLong(7, counted);
                }
                ps.setString(8, note == null || note.isBlank() ? null : note.strip());
                ps.setLong(9, actor.id());
                ps.setString(10, DaoDateUtil.format(BusinessClock.now()));
                ps.executeUpdate();
            }
            return found.id();
        });
        return counts(sessionId).stream().filter(c -> c.packageId() == id).findFirst().orElseThrow();
    }

    /**
     * Closes the session and corrects the record in one transaction: misplaced packages move to where they
     * really are, reweighed ones take the counted weight, damaged ones are written off. Each change writes a
     * stock movement. Missing packages are only counted, never written off.
     */
    public Applied close(long sessionId) {
        User actor = requireCounter();
        LocalDateTime now = BusinessClock.now();
        Applied applied = db.executeInTransaction(conn -> {
            Session stocktake = findSession(conn, sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Stocktake not found."));
            if (!stocktake.open()) {
                throw new IllegalStateException("This stocktake is already closed.");
            }
            int relocated = 0;
            int reweighed = 0;
            int quarantined = 0;
            for (Count line : countsIn(conn, sessionId)) {
                InventoryPackage item = packages.findById(conn, line.packageId()).orElse(null);
                if (item == null) {
                    continue;
                }
                switch (line.outcome()) {
                    case MISPLACED -> {
                        packages.updateLocation(conn, item.id(), line.foundLocationId(), now);
                        movements.insert(conn, item.id(), MovementType.RELOCATE, line.expectedLocationId(),
                                line.foundLocationId(), actor.id(), "Stocktake #" + sessionId + ": found at "
                                        + line.foundLocationCode() + " instead of " + line.expectedLocationCode(), now);
                        relocated++;
                    }
                    case REWEIGHED -> {
                        packages.updateWeight(conn, item.id(), BigDecimal.valueOf(line.countedGrams(), 3), now);
                        movements.insert(conn, item.id(), MovementType.SCAN_VERIFY, item.locationId(),
                                item.locationId(), actor.id(), "Stocktake #" + sessionId + ": reweighed "
                                        + line.expectedKg() + " kg -> " + line.countedKg() + " kg", now);
                        reweighed++;
                    }
                    case DAMAGED -> {
                        // Held aside, not written off: somebody still has to decide what happens to it.
                        packages.updateStatus(conn, item.id(), PackageStatus.QUARANTINED, now);
                        movements.insert(conn, item.id(), MovementType.QUARANTINE, item.locationId(),
                                item.locationId(), actor.id(), "Stocktake #" + sessionId + ": damaged"
                                        + (line.note() == null ? "" : " — " + line.note()), now);
                        quarantined++;
                    }
                    case FOUND, UNEXPECTED -> movements.insert(conn, item.id(), MovementType.SCAN_VERIFY,
                            item.locationId(), item.locationId(), actor.id(),
                            "Stocktake #" + sessionId + ": counted", now);
                }
            }
            int missing = missing(conn, sessionId).size();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE stocktake_sessions SET status='CLOSED', closed_by=?, closed_at=? WHERE id=? AND status='OPEN'")) {
                ps.setLong(1, actor.id());
                ps.setString(2, DaoDateUtil.format(now));
                ps.setLong(3, sessionId);
                if (ps.executeUpdate() != 1) {
                    throw new IllegalStateException("This stocktake is already closed.");
                }
            }
            return new Applied(relocated, reweighed, quarantined, missing);
        });
        LOG.info("Stocktake {} closed: {} relocated, {} reweighed, {} quarantined, {} missing",
                sessionId, applied.relocated(), applied.reweighed(), applied.quarantined(), applied.missing());
        return applied;
    }

    /** Abandons a session without changing any stock. */
    public void cancel(long sessionId) {
        requireCounter();
        db.runInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE stocktake_sessions SET status='CANCELLED' WHERE id=? AND status='OPEN'")) {
                ps.setLong(1, sessionId);
                if (ps.executeUpdate() != 1) {
                    throw new IllegalStateException("Only an open stocktake can be abandoned.");
                }
            }
        });
    }

    /** Packages held aside after damage was found, waiting for somebody to decide. */
    public List<InventoryPackage> quarantined() {
        requireViewer();
        return packages.findByFilter(new hu.due.rewear.model.PackageFilter(
                null, PackageStatus.QUARANTINED, null, null, null, null, null));
    }

    /** Writes a quarantined package off for good. */
    public void writeOffQuarantined(long packageId, String reason) {
        decide(packageId, PackageStatus.DISCARDED, MovementType.DISCARD, "Written off from quarantine", reason);
    }

    /** Returns a quarantined package to stock, on the shelf it is already standing on. */
    public void returnToStock(long packageId, String reason) {
        decide(packageId, PackageStatus.IN_STOCK, MovementType.QUARANTINE_RELEASE, "Returned to stock", reason);
    }

    private void decide(long packageId, PackageStatus target, MovementType movement, String what, String reason) {
        User actor = requireCounter();
        LocalDateTime now = BusinessClock.now();
        db.runInTransaction(conn -> {
            InventoryPackage item = packages.findById(conn, packageId)
                    .orElseThrow(() -> new IllegalArgumentException("Package not found."));
            if (item.status() != PackageStatus.QUARANTINED) {
                throw new IllegalStateException("Only a quarantined package can be released or written off.");
            }
            packages.updateStatus(conn, packageId, target, now);
            movements.insert(conn, packageId, movement, item.locationId(),
                    target == PackageStatus.DISCARDED ? null : item.locationId(), actor.id(),
                    what + (reason == null || reason.isBlank() ? "" : " — " + reason.strip()), now);
        });
        LOG.info("Package {} {} by {}", packageId, what.toLowerCase(Locale.ROOT), actor.username());
    }

    // ---- internals --------------------------------------------------------------------------

    /** Whether a package belongs to what this session set out to count. */
    private static boolean inScope(Connection conn, Session stocktake, InventoryPackage item) throws SQLException {
        if (stocktake.scopeLocationId() != null || stocktake.scopeZone() == null) {
            return true;    // one shelf, or the whole warehouse
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT zone FROM warehouse_locations WHERE id=?")) {
            ps.setLong(1, item.locationId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && stocktake.scopeZone().equalsIgnoreCase(rs.getString(1));
            }
        }
    }

    private Long openSessionId(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM stocktake_sessions WHERE status='OPEN' ORDER BY id DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }

    private Optional<Session> findSession(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SESSION_SELECT + " WHERE s.id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapSession(rs)) : Optional.empty();
            }
        }
    }

    private List<Count> countsIn(Connection conn, long sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COUNT_SELECT + " WHERE c.session_id=? ORDER BY c.id")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Count> counted = new ArrayList<>();
                while (rs.next()) {
                    counted.add(mapCount(rs));
                }
                return counted;
            }
        }
    }

    private List<Missing> missing(Connection conn, long sessionId) throws SQLException {
        Session stocktake = findSession(conn, sessionId).orElseThrow();
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.qr_code, l.code, p.weight_kg FROM inventory_packages p
                JOIN warehouse_locations l ON l.id = p.location_id
                WHERE p.status IN ('IN_STOCK','RESERVED')
                  AND p.id NOT IN (SELECT package_id FROM stocktake_counts WHERE session_id = ?)
                """);
        if (stocktake.scopeLocationId() != null) {
            sql.append(" AND p.location_id = ").append(stocktake.scopeLocationId());
        } else if (stocktake.scopeZone() != null) {
            sql.append(" AND upper(l.zone) = '").append(stocktake.scopeZone().replace("'", "''")).append("'");
        }
        sql.append(" ORDER BY l.code, p.qr_code");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Missing> gone = new ArrayList<>();
                while (rs.next()) {
                    gone.add(new Missing(rs.getLong(1), rs.getString(2), rs.getString(3),
                            new BigDecimal(rs.getString(4)).movePointRight(3).longValueExact()));
                }
                return gone;
            }
        }
    }

    private User requireViewer() {
        if (!session.canAccessModule(AppModule.INVENTORY)) {
            throw new IllegalStateException("Inventory access is required.");
        }
        return session.requireCurrentUser();
    }

    private User requireCounter() {
        User actor = requireViewer();
        if (!mayCount()) {
            throw new IllegalStateException("Only the station manager or a production leader may run a stocktake.");
        }
        return actor;
    }

    private static Session mapSession(ResultSet rs) throws SQLException {
        // wasNull() reports on the column just read, so it has to be checked straight after that read.
        long scopeLocation = rs.getLong("scope_location_id");
        Long scopeLocationId = rs.wasNull() ? null : scopeLocation;
        return new Session(rs.getLong("id"), rs.getString("scope_zone"),
                scopeLocationId, rs.getString("scope_location_code"),
                Status.valueOf(rs.getString("status")), rs.getString("note"),
                rs.getString("started_by_name"), DaoDateUtil.parse(rs.getString("started_at")),
                rs.getString("closed_by_name"),
                rs.getString("closed_at") == null ? null : DaoDateUtil.parse(rs.getString("closed_at")));
    }

    private static Count mapCount(ResultSet rs) throws SQLException {
        long expectedLocation = rs.getLong("expected_location_id");
        Long expectedId = rs.wasNull() ? null : expectedLocation;
        long foundLocation = rs.getLong("found_location_id");
        Long foundId = rs.wasNull() ? null : foundLocation;
        long countedGrams = rs.getLong("counted_grams");
        Long countedValue = rs.wasNull() ? null : countedGrams;
        return new Count(rs.getLong("id"), rs.getLong("session_id"), rs.getLong("package_id"),
                rs.getString("qr_code"), Outcome.valueOf(rs.getString("outcome")),
                expectedId, rs.getString("expected_code"), foundId, rs.getString("found_code"),
                rs.getLong("expected_grams"), countedValue, rs.getString("note"),
                rs.getString("counted_by_name"), DaoDateUtil.parse(rs.getString("counted_at")));
    }
}
