package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.CountryDirectory;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.PartnerType;

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

    private static final String SELECT_COLS =
            "id, name, partner_type, contact_name, email, phone, address, tax_number, country_code, preferred_currency, active, created_at";

    private final DatabaseManager db;

    public CustomerDao(DatabaseManager db) {
        this.db = db;
    }

    public List<Customer> findAll(boolean activeOnly) {
        String sql = "SELECT " + SELECT_COLS + " FROM customers "
                + (activeOnly ? "WHERE active = 1 " : "") + "ORDER BY name";
        return queryList(sql);
    }

    public List<Customer> findBuyers(boolean activeOnly) {
        String sql = "SELECT " + SELECT_COLS + " FROM customers WHERE partner_type IN ('CUSTOMER', 'BOTH') "
                + (activeOnly ? "AND active = 1 " : "") + "ORDER BY name";
        return queryList(sql);
    }

    public List<Customer> findSuppliers(boolean activeOnly) {
        String sql = "SELECT " + SELECT_COLS + " FROM customers WHERE partner_type IN ('SUPPLIER', 'BOTH') "
                + (activeOnly ? "AND active = 1 " : "") + "ORDER BY name";
        return queryList(sql);
    }

    private List<Customer> queryList(String sql) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Customer> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list business partners", e);
        }
    }

    public Optional<Customer> findById(long id) {
        String sql = "SELECT " + SELECT_COLS + " FROM customers WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find partner: " + id, e);
        }
    }

    public long insert(String name, String contactName, String email, String phone, String address, String taxNumber) {
        return insert(name, PartnerType.CUSTOMER, contactName, email, phone, address, taxNumber, "HU", "EUR");
    }

    public long insert(String name, String contactName, String email, String phone, String address,
                       String taxNumber, CurrencyCode preferredCurrency) {
        return insert(name, PartnerType.CUSTOMER, contactName, email, phone, address, taxNumber, "HU",
                preferredCurrency != null ? preferredCurrency.name() : "EUR");
    }

    public long insert(String name, PartnerType partnerType, String contactName, String email, String phone,
                       String address, String taxNumber, String countryCode, String preferredCurrency) {
        return db.executeInTransaction(conn -> insert(conn, name, partnerType, contactName, email, phone,
                address, taxNumber, countryCode, preferredCurrency, LocalDateTime.now()));
    }

    public long insert(Connection conn, String name, String contactName, String email, String phone,
                       String address, String taxNumber, LocalDateTime createdAt) throws SQLException {
        return insert(conn, name, PartnerType.CUSTOMER, contactName, email, phone, address, taxNumber,
                "HU", "EUR", createdAt);
    }

    public long insert(Connection conn, String name, String contactName, String email, String phone,
                       String address, String taxNumber, CurrencyCode preferredCurrency, LocalDateTime createdAt) throws SQLException {
        return insert(conn, name, PartnerType.CUSTOMER, contactName, email, phone, address, taxNumber,
                "HU", preferredCurrency != null ? preferredCurrency.name() : "EUR", createdAt);
    }

    public long insert(Connection conn, String name, PartnerType partnerType, String contactName, String email,
                       String phone, String address, String taxNumber, String countryCode,
                       String preferredCurrency, LocalDateTime createdAt) throws SQLException {
        String sql = """
                INSERT INTO customers (
                    name, partner_type, contact_name, email, phone, address, tax_number,
                    country_code, preferred_currency, active, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name.trim());
            ps.setString(2, (partnerType != null ? partnerType : PartnerType.CUSTOMER).name());
            ps.setString(3, contactName);
            ps.setString(4, email);
            ps.setString(5, phone);
            ps.setString(6, address);
            ps.setString(7, taxNumber);
            ps.setString(8, CountryDirectory.normalizeCode(countryCode));
            ps.setString(9, preferredCurrency != null && !preferredCurrency.isBlank()
                    ? preferredCurrency.trim().toUpperCase() : "EUR");
            ps.setString(10, DaoDateUtil.format(createdAt));
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
        update(id, name, PartnerType.CUSTOMER, contactName, email, phone, address, taxNumber, "HU", "EUR", active);
    }

    public void update(long id, String name, PartnerType partnerType, String contactName, String email,
                       String phone, String address, String taxNumber, String countryCode,
                       String preferredCurrency, boolean active) {
        String sql = """
                UPDATE customers
                SET name = ?, partner_type = ?, contact_name = ?, email = ?, phone = ?,
                    address = ?, tax_number = ?, country_code = ?, preferred_currency = ?, active = ?
                WHERE id = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name.trim());
            ps.setString(2, (partnerType != null ? partnerType : PartnerType.CUSTOMER).name());
            ps.setString(3, contactName);
            ps.setString(4, email);
            ps.setString(5, phone);
            ps.setString(6, address);
            ps.setString(7, taxNumber);
            ps.setString(8, CountryDirectory.normalizeCode(countryCode));
            ps.setString(9, preferredCurrency != null && !preferredCurrency.isBlank()
                    ? preferredCurrency.trim().toUpperCase() : "EUR");
            ps.setInt(10, active ? 1 : 0);
            ps.setLong(11, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update partner: " + id, e);
        }
    }

    private static Customer mapRow(ResultSet rs) throws SQLException {
        return new Customer(
                rs.getLong("id"),
                rs.getString("name"),
                PartnerType.parse(rs.getString("partner_type")),
                rs.getString("contact_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("address"),
                rs.getString("tax_number"),
                CountryDirectory.normalizeCode(rs.getString("country_code")),
                rs.getString("preferred_currency"),
                rs.getInt("active") == 1,
                DaoDateUtil.parse(rs.getString("created_at"))
        );
    }
}
