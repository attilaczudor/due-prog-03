package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceEvent;
import hu.due.rewear.model.InvoiceItem;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.util.BusinessClock;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.Locale;

/**
 * Data Access Object for Outgoing (revenue) and Incoming (expense) invoices and their line items
 * ({@code invoices} and {@code invoice_items} tables), including sequential numbering, tax type codes,
 * native multi-currency amounts, and reversible payment status updates.
 */
public final class InvoiceDao {

    private static final String BASE_SELECT = """
            SELECT i.id, i.invoice_number, i.direction, i.category, i.order_id,
                   o.order_number, i.customer_id, i.partner_name, i.partner_tax_number,
                   i.partner_address, i.partner_email, i.status, i.issue_date, i.due_date,
                   i.paid_date, i.net_amount, i.vat_rate, i.tax_type_code, i.vat_amount, i.gross_amount,
                   i.currency, i.exchange_rate, i.attachment_path, i.created_by, u.full_name AS created_by_name, i.created_at
            FROM invoices i
            LEFT JOIN orders o ON o.id = i.order_id
            JOIN users u ON u.id = i.created_by
            """;

    private final DatabaseManager db;

    public InvoiceDao(DatabaseManager db) {
        this.db = db;
    }

    public List<Invoice> findAll() {
        String sql = BASE_SELECT + " ORDER BY i.issue_date DESC, i.id DESC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Invoice> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapInvoice(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list invoices", e);
        }
    }

    /**
     * Indexed lookup for all invoices belonging to a given partner (either linked by {@code customer_id}
     * or matching a manual/seeded {@code partner_name} case-insensitively).
     */
    public List<Invoice> findByPartner(long customerId, String partnerName) {
        String sql = BASE_SELECT
                + " WHERE i.customer_id = ? OR lower(i.partner_name) = lower(?) "
                + " ORDER BY i.issue_date DESC, i.id DESC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            ps.setString(2, partnerName != null ? partnerName.trim() : "");
            try (ResultSet rs = ps.executeQuery()) {
                List<Invoice> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapInvoice(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to list invoices for partner: " + customerId, e);
        }
    }

    public Optional<Invoice> findById(long id) {
        try (Connection conn = db.getConnection()) {
            return findById(conn, id);
        } catch (SQLException e) {
            throw new DaoException("Failed to find invoice: " + id, e);
        }
    }

    public Optional<Invoice> findById(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(BASE_SELECT + " WHERE i.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapInvoice(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Invoice> findByOrderId(long orderId) {
        try (Connection conn = db.getConnection()) {
            return findByOrderId(conn, orderId);
        } catch (SQLException e) {
            throw new DaoException("Failed to find invoice for order: " + orderId, e);
        }
    }

    public Optional<Invoice> findByOrderId(Connection conn, long orderId) throws SQLException {
        String sql = BASE_SELECT + """
                WHERE i.order_id = ? AND i.direction = 'OUTGOING' AND i.category <> 'CREDIT_NOTE'
                ORDER BY i.id DESC LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapInvoice(rs)) : Optional.empty();
            }
        }
    }

    /** The invoice this one amends, when it is a credit note; null otherwise. */
    public Long findCreditForInvoiceId(Connection conn, long invoiceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT credit_for_invoice_id FROM invoices WHERE id = ?")) {
            ps.setLong(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long value = rs.getLong(1);
                return rs.wasNull() ? null : value;
            }
        }
    }

    public List<InvoiceItem> findItemsByInvoiceId(long invoiceId) {
        try (Connection conn = db.getConnection()) {
            return findItemsByInvoiceId(conn, invoiceId);
        } catch (SQLException e) {
            throw new DaoException("Failed to list invoice items for invoice: " + invoiceId, e);
        }
    }

    public List<InvoiceItem> findItemsByInvoiceId(Connection conn, long invoiceId) throws SQLException {
        String sql = """
                SELECT id, invoice_id, description, quantity, unit, unit_price, line_net
                FROM invoice_items
                WHERE invoice_id = ?
                ORDER BY id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                List<InvoiceItem> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(new InvoiceItem(
                            rs.getLong("id"),
                            rs.getLong("invoice_id"),
                            rs.getString("description"),
                            ExactDecimal.get(rs, "quantity"),
                            rs.getString("unit"),
                            ExactDecimal.get(rs, "unit_price"),
                            ExactDecimal.get(rs, "line_net")
                    ));
                }
                return items;
            }
        }
    }

    /** Credit notes record the original invoice number at the start of each of their line descriptions. */
    public boolean hasCreditNote(Connection conn, String referencePrefix) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 1 FROM invoices i
                JOIN invoice_items line ON line.invoice_id = i.id
                WHERE i.category = 'CREDIT_NOTE' AND instr(line.description, ?) = 1
                LIMIT 1
                """)) {
            ps.setString(1, referencePrefix);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** True when a credit note already reverses this invoice, by structured link or by the legacy text prefix. */
    public boolean hasCreditNoteFor(Connection conn, long originalInvoiceId, String legacyReferencePrefix) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM invoices WHERE category = 'CREDIT_NOTE' AND credit_for_invoice_id = ? LIMIT 1")) {
            ps.setLong(1, originalInvoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return hasCreditNote(conn, legacyReferencePrefix);
    }

    public void setCreditFor(Connection conn, long creditNoteId, long originalInvoiceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE invoices SET credit_for_invoice_id = ? WHERE id = ?")) {
            ps.setLong(1, originalInvoiceId);
            ps.setLong(2, creditNoteId);
            ps.executeUpdate();
        }
    }

    public void updateIssueDates(Connection conn, long invoiceId, LocalDate issueDate, LocalDate dueDate) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE invoices SET issue_date = ?, due_date = ? WHERE id = ?")) {
            ps.setString(1, issueDate.toString());
            ps.setString(2, dueDate.toString());
            ps.setLong(3, invoiceId);
            ps.executeUpdate();
        }
    }

    public void insertEvent(Connection conn, long invoiceId, String eventType, InvoiceStatus from, InvoiceStatus to,
                            String note, long userId, LocalDateTime at) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO invoice_events (invoice_id, event_type, from_status, to_status, note, user_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, invoiceId);
            ps.setString(2, eventType);
            ps.setString(3, from == null ? null : from.name());
            ps.setString(4, to == null ? null : to.name());
            ps.setString(5, note);
            ps.setLong(6, userId);
            ps.setString(7, DaoDateUtil.format(at));
            ps.executeUpdate();
        }
    }

    /** The invoice's lifecycle events, oldest first. */
    public List<InvoiceEvent> findEvents(long invoiceId) {
        String sql = """
                SELECT e.id, e.invoice_id, e.event_type, e.from_status, e.to_status, e.note, u.full_name, e.created_at
                FROM invoice_events e LEFT JOIN users u ON u.id = e.user_id
                WHERE e.invoice_id = ? ORDER BY e.id
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                List<InvoiceEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(new InvoiceEvent(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7),
                            LocalDateTime.parse(rs.getString(8).replace(' ', 'T'))));
                }
                return events;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to load invoice events: " + invoiceId, e);
        }
    }

    public long count() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM invoices");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new DaoException("Failed to count invoices", e);
        }
    }

    /** Counts invoices currently in {@code OVERDUE} status for Dashboard and finance alerts. */
    public int countOverdue() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM invoices WHERE status = 'OVERDUE'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new DaoException("Failed to count overdue invoices", e);
        }
    }

    /**
     * Indexed lookup of invoices currently in {@code OVERDUE} status, ordered like {@link #findAll()}.
     * <p>
     * Used by background to-do and badge computations so they never have to load the full invoice
     * ledger just to filter it down in Java.
     */
    public List<Invoice> findOverdue() {
        String sql = BASE_SELECT + " WHERE i.status = 'OVERDUE' ORDER BY i.issue_date DESC, i.id DESC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Invoice> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapInvoice(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list overdue invoices", e);
        }
    }

    public String generateNextInvoiceNumber(Connection conn, InvoiceDirection direction) throws SQLException {
        int year = BusinessClock.today().getYear();
        String dirTag = direction == InvoiceDirection.OUTGOING ? "OUT" : "IN";
        return nextSequentialNumber(conn, "INV-" + dirTag + "-" + year + "-");
    }

    /** Generates the next sequential yearly credit note number ({@code CN-YYYY-NNNN}). */
    public String generateNextCreditNoteNumber(Connection conn) throws SQLException {
        return nextSequentialNumber(conn, "CN-" + BusinessClock.today().getYear() + "-");
    }

    private static String nextSequentialNumber(Connection conn, String prefix) throws SQLException {
        String sql = "SELECT invoice_number FROM invoices WHERE invoice_number LIKE ? ORDER BY invoice_number DESC LIMIT 1";
        int nextSeq = 1;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String last = rs.getString(1);
                    String tail = last.substring(prefix.length());
                    try {
                        nextSeq = Integer.parseInt(tail) + 1;
                    } catch (NumberFormatException ignored) {
                        nextSeq = 1;
                    }
                }
            }
        }
        return String.format("%s%04d", prefix, nextSeq);
    }

    public long insert(Connection conn,
                       String invoiceNumber,
                       InvoiceDirection direction,
                       InvoiceCategory category,
                       Long orderId,
                       Long customerId,
                       String partnerName,
                       String partnerTaxNumber,
                       String partnerAddress,
                       String partnerEmail,
                       InvoiceStatus status,
                       LocalDate issueDate,
                       LocalDate dueDate,
                       LocalDate paidDate,
                       BigDecimal netAmount,
                       BigDecimal vatRate,
                       BigDecimal vatAmount,
                       BigDecimal grossAmount,
                       String currency,
                       long createdBy,
                       LocalDateTime createdAt) throws SQLException {
        return insert(conn, invoiceNumber, direction, category, orderId, customerId, partnerName,
                partnerTaxNumber, partnerAddress, partnerEmail, status, issueDate, dueDate, paidDate,
                netAmount, vatRate, vatAmount, grossAmount, currency,
                hu.due.rewear.model.CurrencyCode.DEFAULT_EUR_HUF_RATE, createdBy, createdAt);
    }

    public long insert(Connection conn,
                       String invoiceNumber,
                       InvoiceDirection direction,
                       InvoiceCategory category,
                       Long orderId,
                       Long customerId,
                       String partnerName,
                       String partnerTaxNumber,
                       String partnerAddress,
                       String partnerEmail,
                       InvoiceStatus status,
                       LocalDate issueDate,
                       LocalDate dueDate,
                       LocalDate paidDate,
                       BigDecimal netAmount,
                       BigDecimal vatRate,
                       BigDecimal vatAmount,
                       BigDecimal grossAmount,
                       String currency,
                       BigDecimal exchangeRate,
                       long createdBy,
                       LocalDateTime createdAt) throws SQLException {
        return insert(conn, invoiceNumber, direction, category, orderId, customerId, partnerName,
                partnerTaxNumber, partnerAddress, partnerEmail, status, issueDate, dueDate, paidDate,
                netAmount, vatRate, vatRate.compareTo(BigDecimal.ZERO) == 0 ? "EU-0" : "HU-27",
                vatAmount, grossAmount, currency, exchangeRate, createdBy, createdAt);
    }

    public long insert(Connection conn,
                       String invoiceNumber,
                       InvoiceDirection direction,
                       InvoiceCategory category,
                       Long orderId,
                       Long customerId,
                       String partnerName,
                       String partnerTaxNumber,
                       String partnerAddress,
                       String partnerEmail,
                       InvoiceStatus status,
                       LocalDate issueDate,
                       LocalDate dueDate,
                       LocalDate paidDate,
                       BigDecimal netAmount,
                       BigDecimal vatRate,
                       String taxTypeCode,
                       BigDecimal vatAmount,
                       BigDecimal grossAmount,
                       String currency,
                       BigDecimal exchangeRate,
                       long createdBy,
                       LocalDateTime createdAt) throws SQLException {
        String sql = """
                INSERT INTO invoices (
                    invoice_number, direction, category, order_id, customer_id,
                    partner_name, partner_tax_number, partner_address, partner_email,
                    status, issue_date, due_date, paid_date,
                    net_amount, vat_rate, tax_type_code, vat_amount, gross_amount, currency, exchange_rate,
                    created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        double rate = (exchangeRate != null && exchangeRate.compareTo(BigDecimal.ZERO) > 0)
                ? exchangeRate.doubleValue() : hu.due.rewear.model.CurrencyCode.DEFAULT_EUR_HUF_RATE.doubleValue();
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, invoiceNumber.trim());
            ps.setString(2, direction.name());
            ps.setString(3, category.name());
            if (orderId != null) ps.setLong(4, orderId); else ps.setNull(4, Types.INTEGER);
            if (customerId != null) ps.setLong(5, customerId); else ps.setNull(5, Types.INTEGER);
            ps.setString(6, partnerName.trim());
            ps.setString(7, emptyToNull(partnerTaxNumber));
            ps.setString(8, emptyToNull(partnerAddress));
            ps.setString(9, emptyToNull(partnerEmail));
            ps.setString(10, status.name());
            ps.setString(11, issueDate.toString());
            ps.setString(12, dueDate.toString());
            ps.setString(13, paidDate != null ? paidDate.toString() : null);
            ExactDecimal.set(ps, 14, netAmount);
            ps.setDouble(15, vatRate.doubleValue());
            ps.setString(16, emptyToNull(taxTypeCode));
            ExactDecimal.set(ps, 17, vatAmount);
            ExactDecimal.set(ps, 18, grossAmount);
            ps.setString(19, currency != null && !currency.isBlank() ? currency.trim().toUpperCase(Locale.ROOT) : "EUR");
            ps.setDouble(20, rate);
            ps.setLong(21, createdBy);
            ps.setString(22, DaoDateUtil.format(createdAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for invoices insert");
            }
        }
    }

    public void insertItem(Connection conn, long invoiceId, String description,
                           BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal lineNet) throws SQLException {
        String sql = """
                INSERT INTO invoice_items (invoice_id, description, quantity, unit, unit_price, line_net)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, invoiceId);
            ps.setString(2, description.trim());
            ExactDecimal.set(ps, 3, quantity);
            ps.setString(4, unit != null && !unit.isBlank() ? unit.trim() : "kg");
            ExactDecimal.set(ps, 5, unitPrice);
            ExactDecimal.set(ps, 6, lineNet);
            ps.executeUpdate();
        }
    }

    public void deleteItemsByInvoiceId(Connection conn, long invoiceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM invoice_items WHERE invoice_id = ?")) {
            ps.setLong(1, invoiceId);
            ps.executeUpdate();
        }
    }

    public void updateAmounts(Connection conn, long invoiceId, BigDecimal netAmount,
                              BigDecimal vatRate, String taxTypeCode, BigDecimal vatAmount, BigDecimal grossAmount,
                              String currency, BigDecimal exchangeRate) throws SQLException {
        String sql = "UPDATE invoices SET net_amount = ?, vat_rate = ?, tax_type_code = ?, vat_amount = ?, gross_amount = ?, currency = ?, exchange_rate = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ExactDecimal.set(ps, 1, netAmount);
            ps.setDouble(2, vatRate.doubleValue());
            ps.setString(3, emptyToNull(taxTypeCode));
            ExactDecimal.set(ps, 4, vatAmount);
            ExactDecimal.set(ps, 5, grossAmount);
            ps.setString(6, currency != null && !currency.isBlank() ? currency.trim().toUpperCase(Locale.ROOT) : "EUR");
            ps.setDouble(7, (exchangeRate != null && exchangeRate.compareTo(BigDecimal.ZERO) > 0)
                    ? exchangeRate.doubleValue() : hu.due.rewear.model.CurrencyCode.DEFAULT_EUR_HUF_RATE.doubleValue());
            ps.setLong(8, invoiceId);
            ps.executeUpdate();
        }
    }

    public void updateStatus(long invoiceId, InvoiceStatus status, LocalDate paidDate) {
        try (Connection conn = db.getConnection()) {
            updateStatus(conn, invoiceId, status, paidDate);
        } catch (SQLException e) {
            throw new DaoException("Failed to update invoice status: " + invoiceId, e);
        }
    }

    /** Transaction-aware overload reusing the caller's active SQLite {@link Connection}. */
    public void updateStatus(Connection conn, long invoiceId, InvoiceStatus status, LocalDate paidDate) throws SQLException {
        String sql = "UPDATE invoices SET status = ?, paid_date = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, paidDate != null ? paidDate.toString() : null);
            ps.setLong(3, invoiceId);
            ps.executeUpdate();
        }
    }

    /**
     * Automatically transitions all {@code ISSUED} invoices whose ISO {@code due_date} is strictly
     * before {@code asOfDate} to {@code OVERDUE}, returning the number of updated rows.
     */
    public int markPastDueAsOverdue(LocalDate asOfDate) {
        LocalDate ref = asOfDate != null ? asOfDate : BusinessClock.today();
        String sql = "UPDATE invoices SET status = 'OVERDUE' WHERE status = 'ISSUED' AND due_date < ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ref.toString());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to mark past-due invoices as overdue", e);
        }
    }

    /** Replaces the receipt link only if it still matches the version read by the vault. */
    public boolean replaceAttachmentPath(long invoiceId, String previousPath, String relativePath) {
        try (Connection conn = db.getConnection()) {
            return replaceAttachmentPath(conn, invoiceId, previousPath, relativePath);
        } catch (SQLException e) {
            throw new DaoException("Failed to update invoice attachment path: " + invoiceId, e);
        }
    }

    public boolean replaceAttachmentPath(Connection conn, long invoiceId, String previousPath,
                                         String relativePath) throws SQLException {
        String sql = "UPDATE invoices SET attachment_path = ? WHERE id = ? AND attachment_path IS ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, emptyToNull(relativePath));
            ps.setLong(2, invoiceId);
            ps.setString(3, previousPath);
            return ps.executeUpdate() == 1;
        }
    }

    public String findArchivedPdfPath(Connection conn, long invoiceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT archived_pdf_path FROM invoices WHERE id = ?")) {
            ps.setLong(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void setArchivedPdfPath(Connection conn, long invoiceId, String path) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE invoices SET archived_pdf_path = ? WHERE id = ?")) {
            ps.setString(1, path);
            ps.setLong(2, invoiceId);
            ps.executeUpdate();
        }
    }

    public void deleteById(long invoiceId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM invoices WHERE id = ?")) {
            ps.setLong(1, invoiceId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to delete invoice: " + invoiceId, e);
        }
    }

    private static Invoice mapInvoice(ResultSet rs) throws SQLException {
        long orderIdRaw = rs.getLong("order_id");
        Long orderId = rs.wasNull() ? null : orderIdRaw;
        long custIdRaw = rs.getLong("customer_id");
        Long customerId = rs.wasNull() ? null : custIdRaw;
        String paidRaw = rs.getString("paid_date");
        return new Invoice(
                rs.getLong("id"),
                rs.getString("invoice_number"),
                InvoiceDirection.valueOf(rs.getString("direction")),
                InvoiceCategory.valueOf(rs.getString("category")),
                orderId,
                rs.getString("order_number"),
                customerId,
                rs.getString("partner_name"),
                rs.getString("partner_tax_number"),
                rs.getString("partner_address"),
                rs.getString("partner_email"),
                InvoiceStatus.valueOf(rs.getString("status")),
                LocalDate.parse(rs.getString("issue_date")),
                LocalDate.parse(rs.getString("due_date")),
                paidRaw != null && !paidRaw.isBlank() ? LocalDate.parse(paidRaw) : null,
                ExactDecimal.get(rs, "net_amount"),
                bd(rs.getDouble("vat_rate")),
                rs.getString("tax_type_code"),
                ExactDecimal.get(rs, "vat_amount"),
                ExactDecimal.get(rs, "gross_amount"),
                rs.getString("currency"),
                bd(rs.getDouble("exchange_rate")),
                rs.getString("attachment_path"),
                rs.getLong("created_by"),
                rs.getString("created_by_name"),
                DaoDateUtil.parse(rs.getString("created_at"))
        );
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
