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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class OrderDao {

    private static final String BASE_SELECT = """
            SELECT o.id, o.order_number, o.customer_id, c.name AS customer_name,
                   o.status, o.currency, o.total_weight_kg, o.total_price, o.notes,
                   o.created_by, u.full_name AS created_by_name, o.created_at, o.shipped_at
            FROM orders o
            JOIN customers c ON c.id = o.customer_id
            JOIN users u ON u.id = o.created_by
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

    public Optional<Order> findById(long id) {
        String sql = BASE_SELECT + " WHERE o.id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find order: " + id, e);
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
        String sql = "SELECT COUNT(*) FROM orders WHERE order_number LIKE ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                int next = (rs.next() ? rs.getInt(1) : 0) + 1;
                return String.format("%s%04d", prefix, next);
            }
        }
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
            ps.setString(4, (currencyCode != null && !currencyCode.isBlank()) ? currencyCode.trim().toUpperCase() : "EUR");
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

    public void updateCurrency(Connection conn, long orderId, String currencyCode) throws SQLException {
        String sql = "UPDATE orders SET currency = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, (currencyCode != null && !currencyCode.isBlank()) ? currencyCode.trim().toUpperCase() : "EUR");
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
        String sql = "UPDATE orders SET status = ?, shipped_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, DaoDateUtil.format(shippedAt));
            ps.setLong(3, orderId);
            ps.executeUpdate();
        }
    }

    private static Order mapRow(ResultSet rs) throws SQLException {
        return new Order(
                rs.getLong("id"),
                rs.getString("order_number"),
                rs.getLong("customer_id"),
                rs.getString("customer_name"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getString("currency") != null ? rs.getString("currency").trim().toUpperCase() : "EUR",
                BigDecimal.valueOf(rs.getDouble("total_weight_kg")),
                BigDecimal.valueOf(rs.getDouble("total_price")),
                rs.getString("notes"),
                rs.getLong("created_by"),
                rs.getString("created_by_name"),
                DaoDateUtil.parse(rs.getString("created_at")),
                DaoDateUtil.parse(rs.getString("shipped_at"))
        );
    }
}
