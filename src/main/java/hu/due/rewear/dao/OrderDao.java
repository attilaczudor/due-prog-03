package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderStatus;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Locale;

/**
 * Data Access Object for B2B customer orders ({@code orders} table), supporting sequential yearly
 * order numbers ({@code ORD-YYYY-NNNN}), per-order currency selection, total recalculation,
 * and reversible lifecycle status transitions.
 */
public final class OrderDao {

    private static final String BASE_SELECT = """
            SELECT o.id, o.order_number, o.customer_id, c.name AS customer_name,
                   o.status, o.currency, o.total_weight_kg, o.total_price, o.notes,
                   o.created_by, u.full_name AS created_by_name, o.created_at, o.shipped_at,
                   o.assigned_forklift_id, fk.full_name AS assigned_forklift_name,
                   o.dispatch_location_id, dl.code AS dispatch_location_code,
                   o.customer_arrived_at, o.delivery_date
            FROM orders o
            JOIN customers c ON c.id = o.customer_id
            JOIN users u ON u.id = o.created_by
            LEFT JOIN users fk ON fk.id = o.assigned_forklift_id
            LEFT JOIN warehouse_locations dl ON dl.id = o.dispatch_location_id
            """;

    private final DatabaseManager db;

    public OrderDao(DatabaseManager db) {
        this.db = db;
    }

    public List<Order> findAll() {
        String sql = BASE_SELECT + " ORDER BY o.created_at DESC, o.id DESC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Order> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list orders", e);
        }
    }

    public List<Order> findOpenOrders() {
        String sql = BASE_SELECT + " WHERE o.status IN ('DRAFT', 'PACKING') ORDER BY o.created_at DESC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Order> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list open orders", e);
        }
    }

    public List<Order> findByCustomerId(long customerId) {
        String sql = BASE_SELECT + " WHERE o.customer_id = ? ORDER BY o.created_at DESC, o.id DESC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Order> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to list orders for customer: " + customerId, e);
        }
    }

    public Optional<Order> findById(long id) {
        try (Connection conn = db.getConnection()) {
            return findById(conn, id);
        } catch (SQLException e) {
            throw new DaoException("Failed to find order: " + id, e);
        }
    }

    public Optional<Order> findById(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(BASE_SELECT + " WHERE o.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public int countOpenOrders() {
        String sql = "SELECT COUNT(*) FROM orders WHERE status IN ('DRAFT', 'PACKING')";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new DaoException("Failed to count open orders", e);
        }
    }

    public String generateNextOrderNumber(Connection conn) throws SQLException {
        int year = LocalDate.now().getYear();
        String prefix = "ORD-" + year + "-";
        String sql = "SELECT order_number FROM orders WHERE order_number LIKE ? ORDER BY order_number DESC LIMIT 1";
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

    public long insert(Connection conn, String orderNumber, long customerId, OrderStatus status,
                       BigDecimal totalWeightKg, BigDecimal totalPrice, String notes,
                       long createdBy, LocalDateTime createdAt, LocalDateTime shippedAt) throws SQLException {
        return insert(conn, orderNumber, customerId, status, "EUR",
                totalWeightKg, totalPrice, notes, createdBy, createdAt, shippedAt);
    }

    public long insert(Connection conn, String orderNumber, long customerId, OrderStatus status,
                       CurrencyCode currency, BigDecimal totalWeightKg, BigDecimal totalPrice, String notes,
                       long createdBy, LocalDateTime createdAt, LocalDateTime shippedAt) throws SQLException {
        return insert(conn, orderNumber, customerId, status, currency != null ? currency.name() : "EUR",
                totalWeightKg, totalPrice, notes, createdBy, createdAt, shippedAt);
    }

    public long insert(Connection conn, String orderNumber, long customerId, OrderStatus status,
                       String currencyCode, BigDecimal totalWeightKg, BigDecimal totalPrice, String notes,
                       long createdBy, LocalDateTime createdAt, LocalDateTime shippedAt) throws SQLException {
        String sql = """
                INSERT INTO orders (
                    order_number, customer_id, status, currency, total_weight_kg, total_price, notes,
                    created_by, created_at, shipped_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, orderNumber);
            ps.setLong(2, customerId);
            ps.setString(3, status.name());
            ps.setString(4, (currencyCode != null && !currencyCode.isBlank()) ? currencyCode.trim().toUpperCase(Locale.ROOT) : "EUR");
            ps.setDouble(5, totalWeightKg.doubleValue());
            ps.setDouble(6, totalPrice.doubleValue());
            ps.setString(7, notes);
            ps.setLong(8, createdBy);
            ps.setString(9, DaoDateUtil.format(createdAt));
            ps.setString(10, DaoDateUtil.format(shippedAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for orders insert");
            }
        }
    }

    public void updateDeliveryDate(Connection conn, long orderId, LocalDate date) throws SQLException {
        try (var ps = conn.prepareStatement("UPDATE orders SET delivery_date = ? WHERE id = ?")) {
            ps.setString(1, date.toString());
            ps.setLong(2, orderId);
            ps.executeUpdate();
        }
    }

    public void updateCurrency(Connection conn, long orderId, String currencyCode) throws SQLException {
        String sql = "UPDATE orders SET currency = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, (currencyCode != null && !currencyCode.isBlank()) ? currencyCode.trim().toUpperCase(Locale.ROOT) : "EUR");
            ps.setLong(2, orderId);
            ps.executeUpdate();
        }
    }

    public void updateTotals(Connection conn, long orderId, BigDecimal totalWeightKg, BigDecimal totalPrice) throws SQLException {
        String sql = "UPDATE orders SET total_weight_kg = ?, total_price = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, totalWeightKg.doubleValue());
            ps.setDouble(2, totalPrice.doubleValue());
            ps.setLong(3, orderId);
            ps.executeUpdate();
        }
    }

    public void updateStatus(Connection conn, long orderId, OrderStatus status, LocalDateTime shippedAt) throws SQLException {
        String sql = """
                UPDATE orders SET status = ?, shipped_at = ?,
                    customer_arrived_at = CASE
                        WHEN status IN ('SHIPPED', 'CANCELLED') OR ? = 'CANCELLED' THEN NULL
                        ELSE customer_arrived_at
                    END
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, DaoDateUtil.format(shippedAt));
            ps.setString(3, status.name());
            ps.setLong(4, orderId);
            ps.executeUpdate();
        }
    }

    public void updateAssignment(long orderId, Long forkliftUserId, Long dispatchLocationId) {
        db.runInTransaction(conn -> updateAssignment(conn, orderId, forkliftUserId, dispatchLocationId));
    }

    public void updateAssignment(Connection conn, long orderId, Long forkliftUserId, Long dispatchLocationId) throws SQLException {
        String sql = "UPDATE orders SET assigned_forklift_id = ?, dispatch_location_id = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (forkliftUserId != null && forkliftUserId > 0) ps.setLong(1, forkliftUserId);
            else ps.setNull(1, java.sql.Types.INTEGER);
            if (dispatchLocationId != null && dispatchLocationId > 0) ps.setLong(2, dispatchLocationId);
            else ps.setNull(2, java.sql.Types.INTEGER);
            ps.setLong(3, orderId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update order forklift/dock assignment: " + orderId, e);
        }
    }

    public boolean recordCustomerArrival(long orderId, LocalDateTime arrivedAt) {
        String sql = """
                UPDATE orders SET customer_arrived_at = ?
                WHERE id = ? AND status IN ('DRAFT', 'PACKING') AND customer_arrived_at IS NULL
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DaoDateUtil.format(arrivedAt));
            ps.setLong(2, orderId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DaoException("Failed to mark customer arrived for order: " + orderId, e);
        }
    }

    private static Order mapRow(ResultSet rs) throws SQLException {
        long fkRaw = rs.getLong("assigned_forklift_id");
        Long fkId = rs.wasNull() ? null : fkRaw;
        long dlRaw = rs.getLong("dispatch_location_id");
        Long dlId = rs.wasNull() ? null : dlRaw;
        return new Order(
                rs.getLong("id"),
                rs.getString("order_number"),
                rs.getLong("customer_id"),
                rs.getString("customer_name"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getString("currency") != null ? rs.getString("currency").trim().toUpperCase(Locale.ROOT) : "EUR",
                BigDecimal.valueOf(rs.getDouble("total_weight_kg")),
                BigDecimal.valueOf(rs.getDouble("total_price")),
                rs.getString("notes"),
                rs.getLong("created_by"),
                rs.getString("created_by_name"),
                DaoDateUtil.parse(rs.getString("created_at")),
                DaoDateUtil.parse(rs.getString("shipped_at")),
                fkId,
                rs.getString("assigned_forklift_name"),
                dlId,
                rs.getString("dispatch_location_code"),
                DaoDateUtil.parse(rs.getString("customer_arrived_at")),
                rs.getString("delivery_date") == null ? null : LocalDate.parse(rs.getString("delivery_date"))
        );
    }
}
