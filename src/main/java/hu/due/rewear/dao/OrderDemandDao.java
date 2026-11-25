package hu.due.rewear.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

public final class OrderDemandDao {
    public record Product(long target, long garment, long grade, long packaging) { }
    public record Line(long id, Product product, String description, int quantity) { }

    public List<Line> lines(Connection conn, long orderId) throws SQLException {
        try (var ps = conn.prepareStatement("""
                SELECT d.*, t.code || ' / ' || g.code || ' / ' || q.code || ' / ' || p.code AS description
                FROM order_requested_products d JOIN target_groups t ON t.id=d.target_group_id
                JOIN garment_types g ON g.id=d.garment_type_id JOIN quality_grades q ON q.id=d.quality_grade_id
                JOIN package_types p ON p.id=d.package_type_id WHERE d.order_id=? ORDER BY d.id
                """)) {
            ps.setLong(1, orderId);
            try (var rs = ps.executeQuery()) {
                var rows = new ArrayList<Line>();
                while (rs.next()) rows.add(new Line(rs.getLong("id"), product(rs), rs.getString("description"), rs.getInt("quantity")));
                return List.copyOf(rows);
            }
        }
    }

    public void setQuantity(Connection conn, long orderId, Product product, int quantity) throws SQLException {
        try (var ps = conn.prepareStatement("""
                INSERT INTO order_requested_products(order_id,target_group_id,garment_type_id,quality_grade_id,package_type_id,quantity)
                VALUES (?,?,?,?,?,?) ON CONFLICT(order_id,target_group_id,garment_type_id,quality_grade_id,package_type_id)
                DO UPDATE SET quantity=excluded.quantity
                """)) {
            ps.setLong(1, orderId);
            bind(ps, product, 2);
            ps.setInt(6, quantity);
            ps.executeUpdate();
        }
    }

    public void remove(Connection conn, long orderId, long lineId) throws SQLException {
        try (var ps = conn.prepareStatement("DELETE FROM order_requested_products WHERE order_id=? AND id=?")) {
            ps.setLong(1, orderId);
            ps.setLong(2, lineId);
            ps.executeUpdate();
        }
    }

    public List<Long> available(Connection conn, Product product, int limit) throws SQLException {
        try (var ps = conn.prepareStatement("""
                SELECT p.id FROM inventory_packages p JOIN warehouse_locations w ON w.id=p.location_id
                WHERE p.status='IN_STOCK' AND w.location_type='STORAGE' AND w.active=1
                  AND p.target_group_id=? AND p.garment_type_id=? AND p.quality_grade_id=? AND p.package_type_id=?
                ORDER BY p.created_at,p.id LIMIT ?
                """)) {
            bind(ps, product, 1);
            ps.setInt(5, limit);
            try (var rs = ps.executeQuery()) {
                var ids = new ArrayList<Long>();
                while (rs.next()) ids.add(rs.getLong(1));
                return List.copyOf(ids);
            }
        }
    }

    public int availableCount(Connection conn, Product product) throws SQLException {
        try (var ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM inventory_packages p JOIN warehouse_locations w ON w.id=p.location_id
                WHERE p.status='IN_STOCK' AND w.location_type='STORAGE' AND w.active=1
                  AND p.target_group_id=? AND p.garment_type_id=? AND p.quality_grade_id=? AND p.package_type_id=?
                """)) {
            bind(ps, product, 1);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    public boolean isActiveStorage(Connection conn, long locationId) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT 1 FROM warehouse_locations WHERE id=? AND active=1 AND location_type='STORAGE'")) {
            ps.setLong(1, locationId);
            try (var rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public int reservedCount(Connection conn, long orderId, Product product) throws SQLException {
        try (var ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM order_items i JOIN inventory_packages p ON p.id=i.package_id
                WHERE i.order_id=? AND p.target_group_id=? AND p.garment_type_id=?
                  AND p.quality_grade_id=? AND p.package_type_id=?
                """)) {
            ps.setLong(1, orderId);
            bind(ps, product, 2);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    public OptionalLong bySource(Connection conn, String source, String externalId) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT order_id FROM order_webshop_references WHERE source=? AND external_id=?")) {
            ps.setString(1, source);
            ps.setString(2, externalId);
            try (var rs = ps.executeQuery()) { return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty(); }
        }
    }

    public void setSource(Connection conn, long id, String source, String externalId) throws SQLException {
        try (var ps = conn.prepareStatement("INSERT INTO order_webshop_references(order_id,source,external_id) VALUES(?,?,?)")) {
            ps.setLong(1, id);
            ps.setString(2, source);
            ps.setString(3, externalId);
            ps.executeUpdate();
        }
    }

    private static Product product(ResultSet rs) throws SQLException {
        return new Product(rs.getLong("target_group_id"), rs.getLong("garment_type_id"),
                rs.getLong("quality_grade_id"), rs.getLong("package_type_id"));
    }

    private static void bind(java.sql.PreparedStatement ps, Product p, int start) throws SQLException {
        ps.setLong(start, p.target());
        ps.setLong(start+1, p.garment());
        ps.setLong(start+2, p.grade());
        ps.setLong(start+3, p.packaging());
    }
}
