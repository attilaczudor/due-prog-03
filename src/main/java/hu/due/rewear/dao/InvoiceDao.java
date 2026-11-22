package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceItem;
import hu.due.rewear.model.InvoiceStatus;

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
        String sql = BASE_SELECT + " WHERE i.id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapInvoice(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find invoice: " + id, e);
        }
    }

    public Optional<Invoice> findByOrderId(long orderId) {
        String sql = BASE_SELECT + " WHERE i.order_id = ? AND i.direction = 'OUTGOING' ORDER BY i.id DESC LIMIT 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapInvoice(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find invoice for order: " + orderId, e);
        }
    }

    public List<InvoiceItem> findItemsByInvoiceId(long invoiceId) {
        String sql = """
                SELECT id, invoice_id, description, quantity, unit, unit_price, line_net
                FROM invoice_items
                WHERE invoice_id = ?
                ORDER BY id
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                List<InvoiceItem> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(new InvoiceItem(
                            rs.getLong("id"),
                            rs.getLong("invoice_id"),
                            rs.getString("description"),
                            bd(rs.getDouble("quantity")),
                            rs.getString("unit"),
                            bd(rs.getDouble("unit_price")),
                            bd(rs.getDouble("line_net"))
                    ));
                }
                return items;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to list invoice items for invoice: " + invoiceId, e);
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

    public String generateNextInvoiceNumber(Connection conn, InvoiceDirection direction) throws SQLException {
        int year = LocalDate.now().getYear();
        String dirTag = direction == InvoiceDirection.OUTGOING ? "OUT" : "IN";
        return nextSequentialNumber(conn, "INV-" + dirTag + "-" + year + "-");
    }

    /** Generates the next sequential yearly credit note number ({@code CN-YYYY-NNNN}). */
    public String generateNextCreditNoteNumber(Connection conn) throws SQLException {
        return nextSequentialNumber(conn, "CN-" + LocalDate.now().getYear() + "-");
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
            ps.setDouble(14, netAmount.doubleValue());
            ps.setDouble(15, vatRate.doubleValue());
            ps.setString(16, emptyToNull(taxTypeCode));
            ps.setDouble(17, vatAmount.doubleValue());
            ps.setDouble(18, grossAmount.doubleValue());
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
            ps.setDouble(3, quantity.doubleValue());
            ps.setString(4, unit != null && !unit.isBlank() ? unit.trim() : "kg");
            ps.setDouble(5, unitPrice.doubleValue());
            ps.setDouble(6, lineNet.doubleValue());
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
            ps.setDouble(1, netAmount.doubleValue());
            ps.setDouble(2, vatRate.doubleValue());
            ps.setString(3, emptyToNull(taxTypeCode));
            ps.setDouble(4, vatAmount.doubleValue());
            ps.setDouble(5, grossAmount.doubleValue());
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
        LocalDate ref = asOfDate != null ? asOfDate : LocalDate.now();
        String sql = "UPDATE invoices SET status = 'OVERDUE' WHERE status = 'ISSUED' AND due_date < ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ref.toString());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to mark past-due invoices as overdue", e);
        }
    }

    /** Stores or clears the portable relative attachment path for an invoice's scanned receipt. */
    public void updateAttachmentPath(long invoiceId, String relativePath) {
        String sql = "UPDATE invoices SET attachment_path = ? WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, emptyToNull(relativePath));
            ps.setLong(2, invoiceId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update invoice attachment path: " + invoiceId, e);
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
                bd(rs.getDouble("net_amount")),
                bd(rs.getDouble("vat_rate")),
                rs.getString("tax_type_code"),
                bd(rs.getDouble("vat_amount")),
                bd(rs.getDouble("gross_amount")),
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
