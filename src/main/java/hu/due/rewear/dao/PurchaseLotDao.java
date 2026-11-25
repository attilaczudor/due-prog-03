package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.CollectionChannel;
import hu.due.rewear.model.LotLegalStatus;
import hu.due.rewear.model.PurchaseLot;
import hu.due.rewear.model.PurchaseLotEvent;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads and writes purchase lots and their history. */
public final class PurchaseLotDao {

    private static final String SELECT = """
            SELECT p.id, p.reference, p.supplier_id, c.name AS supplier_name, p.origin_country,
                   p.collection_channel, p.customs_code, p.customs_description, p.purchase_price_per_kg,
                   p.currency, p.gross_weight_kg, p.net_weight_kg, p.legal_status, p.assessed_by,
                   a.full_name AS assessed_by_name, p.assessed_at, p.assessment_basis, p.assessment_place,
                   p.invoice_id, i.invoice_number, p.notes, p.received_by, r.full_name AS received_by_name,
                   p.received_on, p.created_at, p.updated_at
            FROM purchase_lots p
            LEFT JOIN customers c ON c.id = p.supplier_id
            LEFT JOIN users a ON a.id = p.assessed_by
            LEFT JOIN users r ON r.id = p.received_by
            LEFT JOIN invoices i ON i.id = p.invoice_id
            """;

    private final DatabaseManager db;

    public PurchaseLotDao(DatabaseManager db) {
        this.db = db;
    }

    public long insert(Connection conn, PurchaseLot lot, LocalDateTime now) throws SQLException {
        String sql = """
                INSERT INTO purchase_lots (reference, supplier_id, origin_country, collection_channel,
                    customs_code, customs_description, purchase_price_per_kg, currency, gross_weight_kg,
                    net_weight_kg, legal_status, invoice_id, notes, received_by, received_on,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, lot.reference());
            setNullableLong(ps, 2, lot.supplierId());
            ps.setString(3, lot.originCountry());
            ps.setString(4, lot.collectionChannel().name());
            ps.setString(5, lot.customsCode());
            ps.setString(6, lot.customsDescription());
            ExactDecimal.set(ps, 7, lot.purchasePricePerKg());
            ps.setString(8, lot.currency());
            ExactDecimal.set(ps, 9, lot.grossWeightKg());
            ExactDecimal.set(ps, 10, lot.netWeightKg());
            ps.setString(11, lot.legalStatus().name());
            setNullableLong(ps, 12, lot.invoiceId());
            ps.setString(13, lot.notes());
            ps.setLong(14, lot.receivedBy());
            ps.setString(15, lot.receivedOn().toString());
            ps.setString(16, DaoDateUtil.format(now));
            ps.setString(17, DaoDateUtil.format(now));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for purchase_lots insert");
            }
        }
    }

    public void update(Connection conn, PurchaseLot lot, LocalDateTime now) throws SQLException {
        String sql = """
                UPDATE purchase_lots
                SET supplier_id = ?, origin_country = ?, collection_channel = ?, customs_code = ?,
                    customs_description = ?, purchase_price_per_kg = ?, currency = ?, gross_weight_kg = ?,
                    net_weight_kg = ?, invoice_id = ?, notes = ?, updated_at = ?
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            setNullableLong(ps, 1, lot.supplierId());
            ps.setString(2, lot.originCountry());
            ps.setString(3, lot.collectionChannel().name());
            ps.setString(4, lot.customsCode());
            ps.setString(5, lot.customsDescription());
            ExactDecimal.set(ps, 6, lot.purchasePricePerKg());
            ps.setString(7, lot.currency());
            ExactDecimal.set(ps, 8, lot.grossWeightKg());
            ExactDecimal.set(ps, 9, lot.netWeightKg());
            setNullableLong(ps, 10, lot.invoiceId());
            ps.setString(11, lot.notes());
            ps.setString(12, DaoDateUtil.format(now));
            ps.setLong(13, lot.id());
            ps.executeUpdate();
        }
    }

    public void recordAssessment(Connection conn, long lotId, LotLegalStatus status, long assessedBy,
                                 String basis, String place, LocalDateTime now) throws SQLException {
        String sql = """
                UPDATE purchase_lots
                SET legal_status = ?, assessed_by = ?, assessed_at = ?, assessment_basis = ?,
                    assessment_place = ?, updated_at = ?
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setLong(2, assessedBy);
            ps.setString(3, DaoDateUtil.format(now));
            ps.setString(4, basis);
            ps.setString(5, place);
            ps.setString(6, DaoDateUtil.format(now));
            ps.setLong(7, lotId);
            ps.executeUpdate();
        }
    }

    public void addEvent(Connection conn, long lotId, String eventType, String detail, Long userId,
                         LocalDateTime now) throws SQLException {
        String sql = """
                INSERT INTO purchase_lot_events (lot_id, event_type, detail, created_by, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lotId);
            ps.setString(2, eventType);
            ps.setString(3, detail);
            setNullableLong(ps, 4, userId);
            ps.setString(5, DaoDateUtil.format(now));
            ps.executeUpdate();
        }
    }

    public List<PurchaseLot> findAll() {
        return query(SELECT + " ORDER BY p.received_on DESC, p.id DESC", ps -> { });
    }

    public Optional<PurchaseLot> findById(long id) {
        List<PurchaseLot> found = query(SELECT + " WHERE p.id = ?", ps -> ps.setLong(1, id));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public Optional<PurchaseLot> findById(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT + " WHERE p.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<PurchaseLot> findByReference(Connection conn, String reference) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT + " WHERE p.reference = ? COLLATE NOCASE")) {
            ps.setString(1, reference);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<PurchaseLotEvent> findEvents(long lotId) {
        String sql = """
                SELECT e.id, e.lot_id, e.event_type, e.detail, e.created_by, u.full_name, e.created_at
                FROM purchase_lot_events e
                LEFT JOIN users u ON u.id = e.created_by
                WHERE e.lot_id = ?
                ORDER BY e.id
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lotId);
            try (ResultSet rs = ps.executeQuery()) {
                List<PurchaseLotEvent> events = new ArrayList<>();
                while (rs.next()) {
                    long createdBy = rs.getLong("created_by");
                    boolean systemAction = rs.wasNull();
                    events.add(new PurchaseLotEvent(
                            rs.getLong("id"),
                            rs.getLong("lot_id"),
                            rs.getString("event_type"),
                            rs.getString("detail"),
                            systemAction ? null : createdBy,
                            rs.getString("full_name"),
                            DaoDateUtil.parse(rs.getString("created_at"))));
                }
                return events;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read the purchase lot history", ex);
        }
    }

    /** How much of the lot has been received into the warehouse as raw stock, in grams. */
    public long receivedGrams(Connection conn, long lotId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(received_grams), 0) FROM sorting_raw_lots WHERE purchase_lot_id = ?")) {
            ps.setLong(1, lotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<PurchaseLot> query(String sql, Binder binder) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<PurchaseLot> lots = new ArrayList<>();
                while (rs.next()) {
                    lots.add(map(rs));
                }
                return lots;
            }
        } catch (SQLException ex) {
            throw new DaoException("Failed to read purchase lots", ex);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static PurchaseLot map(ResultSet rs) throws SQLException {
        long supplierId = rs.getLong("supplier_id");
        boolean noSupplier = rs.wasNull();
        long assessedBy = rs.getLong("assessed_by");
        boolean notAssessed = rs.wasNull();
        long invoiceId = rs.getLong("invoice_id");
        boolean noInvoice = rs.wasNull();
        String assessedAt = rs.getString("assessed_at");
        return new PurchaseLot(
                rs.getLong("id"),
                rs.getString("reference"),
                noSupplier ? null : supplierId,
                rs.getString("supplier_name"),
                rs.getString("origin_country"),
                CollectionChannel.valueOf(rs.getString("collection_channel")),
                rs.getString("customs_code"),
                rs.getString("customs_description"),
                ExactDecimal.get(rs, "purchase_price_per_kg"),
                rs.getString("currency"),
                ExactDecimal.get(rs, "gross_weight_kg"),
                ExactDecimal.get(rs, "net_weight_kg"),
                LotLegalStatus.valueOf(rs.getString("legal_status")),
                notAssessed ? null : assessedBy,
                rs.getString("assessed_by_name"),
                assessedAt == null || assessedAt.isBlank() ? null : DaoDateUtil.parse(assessedAt),
                rs.getString("assessment_basis"),
                rs.getString("assessment_place"),
                noInvoice ? null : invoiceId,
                rs.getString("invoice_number"),
                rs.getString("notes"),
                rs.getLong("received_by"),
                rs.getString("received_by_name"),
                LocalDate.parse(rs.getString("received_on")),
                DaoDateUtil.parse(rs.getString("created_at")),
                DaoDateUtil.parse(rs.getString("updated_at")));
    }

    /** A lot as it is first written, before the database gives it an id. */
    public static PurchaseLot draft(String reference, Long supplierId, String originCountry,
                                    CollectionChannel channel, String customsCode, String customsDescription,
                                    BigDecimal pricePerKg, String currency, BigDecimal grossKg,
                                    BigDecimal netKg, Long invoiceId, String notes, long receivedBy,
                                    LocalDate receivedOn) {
        return new PurchaseLot(0, reference, supplierId, null, originCountry, channel, customsCode,
                customsDescription, pricePerKg, currency, grossKg, netKg, LotLegalStatus.UNASSESSED,
                null, null, null, null, null, invoiceId, null, notes, receivedBy, null, receivedOn,
                null, null);
    }
}
