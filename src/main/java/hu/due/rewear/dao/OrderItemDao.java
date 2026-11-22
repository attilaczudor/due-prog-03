package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.OrderItem;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for physical packages allocated to customer orders ({@code order_items} join table).
 * Tracks per-package unit pricing (in the order's currency) and enforces single-order allocation per bale.
 */
public final class OrderItemDao {

    private final DatabaseManager db;

    public OrderItemDao(DatabaseManager db) {
        this.db = db;
    }

    public List<OrderItem> findByOrderId(long orderId) {
        String sql = """
                SELECT oi.id, oi.order_id, oi.package_id, ip.qr_code,
                       (tg.name_en || ' | ' || gt.name_en || ' | ' || qg.name_en || ' | ' || pt.name_en) AS summary_en,
                       (tg.name_hu || ' | ' || gt.name_hu || ' | ' || qg.name_hu || ' | ' || pt.name_hu) AS summary_hu,
                       ip.weight_kg, oi.price_per_kg, oi.line_total
                FROM order_items oi
                JOIN inventory_packages ip ON ip.id = oi.package_id
                JOIN target_groups tg ON tg.id = ip.target_group_id
                JOIN garment_types gt ON gt.id = ip.garment_type_id
                JOIN quality_grades qg ON qg.id = ip.quality_grade_id
                JOIN package_types pt ON pt.id = ip.package_type_id
                WHERE oi.order_id = ?
                ORDER BY oi.id
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                List<OrderItem> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new OrderItem(
                            rs.getLong("id"),
                            rs.getLong("order_id"),
                            rs.getLong("package_id"),
                            rs.getString("qr_code"),
                            rs.getString("summary_en"),
                            rs.getString("summary_hu"),
                            BigDecimal.valueOf(rs.getDouble("weight_kg")),
                            BigDecimal.valueOf(rs.getDouble("price_per_kg")),
                            BigDecimal.valueOf(rs.getDouble("line_total"))
                    ));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to list items for order: " + orderId, e);
        }
    }

    public long insert(Connection conn, long orderId, long packageId,
                       BigDecimal pricePerKg, BigDecimal lineTotal) throws SQLException {
        String sql = "INSERT INTO order_items (order_id, package_id, price_per_kg, line_total) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, orderId);
            ps.setLong(2, packageId);
            ps.setDouble(3, pricePerKg.doubleValue());
            ps.setDouble(4, lineTotal.doubleValue());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for order_items insert");
            }
        }
    }

    public void updateItemPricing(Connection conn, long orderItemId,
                                  BigDecimal pricePerKg, BigDecimal lineTotal) throws SQLException {
        String sql = "UPDATE order_items SET price_per_kg = ?, line_total = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, pricePerKg.doubleValue());
            ps.setDouble(2, lineTotal.doubleValue());
            ps.setLong(3, orderItemId);
            ps.executeUpdate();
        }
    }

    public void deleteById(Connection conn, long orderItemId) throws SQLException {
        String sql = "DELETE FROM order_items WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderItemId);
            ps.executeUpdate();
        }
    }
}
