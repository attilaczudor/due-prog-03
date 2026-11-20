package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.Customer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CustomerDao {

    private final DatabaseManager db;

    public CustomerDao(DatabaseManager db) {
        this.db = db;
    }

    public List<Customer> findAll(boolean activeOnly) {
        String sql = "SELECT id, name, contact_name, email, phone, address, tax_number, active, created_at FROM customers "
                + (activeOnly ? "WHERE active = 1 " : "") + "ORDER BY name";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Customer> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list customers", e);
        }
    }

    public Optional<Customer> findById(long id) {
        String sql = "SELECT id, name, contact_name, email, phone, address, tax_number, active, created_at FROM customers WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find customer: " + id, e);
        }
    }

    public long insert(String name, String contactName, String email, String phone, String address, String taxNumber) {
        return db.executeInTransaction(conn -> insert(conn, name, contactName, email, phone, address, taxNumber, LocalDateTime.now()));
    }

    public long insert(Connection conn, String name, String contactName, String email, String phone,
                       String address, String taxNumber, LocalDateTime createdAt) throws SQLException {
        String sql = """
                INSERT INTO customers (name, contact_name, email, phone, address, tax_number, active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name.trim());
            ps.setString(2, contactName);
            ps.setString(3, email);
            ps.setString(4, phone);
            ps.setString(5, address);
            ps.setString(6, taxNumber);
            ps.setString(7, DaoDateUtil.format(createdAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for customers insert");
            }
        }
    }

    public void update(long id, String name, String contactName, String email, String phone,
                       String address, String taxNumber, boolean active) {
        String sql = """
                UPDATE customers
                SET name = ?, contact_name = ?, email = ?, phone = ?, address = ?, tax_number = ?, active = ?
                WHERE id = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name.trim());
            ps.setString(2, contactName);
            ps.setString(3, email);
            ps.setString(4, phone);
            ps.setString(5, address);
            ps.setString(6, taxNumber);
            ps.setInt(7, active ? 1 : 0);
            ps.setLong(8, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update customer: " + id, e);
        }
    }

    private static Customer mapRow(ResultSet rs) throws SQLException {
        return new Customer(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("contact_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("address"),
                rs.getString("tax_number"),
                rs.getInt("active") == 1,
                DaoDateUtil.parse(rs.getString("created_at"))
        );
    }
}
