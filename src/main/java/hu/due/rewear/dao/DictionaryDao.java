package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.GarmentType;
import hu.due.rewear.model.PackageType;
import hu.due.rewear.model.QualityGrade;
import hu.due.rewear.model.TargetGroup;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the four normalized QR code dictionary tables:
 * {@code target_groups}, {@code garment_types}, {@code quality_grades}, and {@code package_types}.
 */
public final class DictionaryDao {

    private final DatabaseManager db;

    public DictionaryDao(DatabaseManager db) {
        this.db = db;
    }

    // --- Target Groups ---

    public List<TargetGroup> findTargetGroups(boolean activeOnly) {
        String sql = "SELECT id, code, name_en, name_hu, active FROM target_groups "
                + (activeOnly ? "WHERE active = 1 " : "") + "ORDER BY code";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<TargetGroup> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new TargetGroup(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name_en"),
                        rs.getString("name_hu"),
                        rs.getInt("active") == 1
                ));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list target groups", e);
        }
    }

    public long insertTargetGroup(String code, String nameEn, String nameHu) {
        return db.executeInTransaction(conn -> insertTargetGroup(conn, code, nameEn, nameHu));
    }

    public long insertTargetGroup(Connection conn, String code, String nameEn, String nameHu) throws SQLException {
        String sql = "INSERT INTO target_groups (code, name_en, name_hu, active) VALUES (?, ?, ?, 1)";
        return executeInsertWithKey(conn, sql, ps -> {
            ps.setString(1, code.trim().toUpperCase());
            ps.setString(2, nameEn.trim());
            ps.setString(3, nameHu.trim());
        });
    }

    public void updateTargetGroup(long id, String code, String nameEn, String nameHu, boolean active) {
        String sql = "UPDATE target_groups SET code = ?, name_en = ?, name_hu = ?, active = ? WHERE id = ?";
        executeUpdate(sql, ps -> {
            ps.setString(1, code.trim().toUpperCase());
            ps.setString(2, nameEn.trim());
            ps.setString(3, nameHu.trim());
            ps.setInt(4, active ? 1 : 0);
            ps.setLong(5, id);
        });
    }

    public boolean isTargetGroupReferenced(long id) {
        return existsReference("SELECT 1 FROM inventory_packages WHERE target_group_id = ? LIMIT 1", id);
    }

    public void deleteTargetGroup(long id) {
        executeUpdate("DELETE FROM target_groups WHERE id = ?", ps -> ps.setLong(1, id));
    }

    // --- Garment / Product Types ---

    public List<GarmentType> findGarmentTypes(boolean activeOnly) {
        String sql = "SELECT id, code, name_en, name_hu, active FROM garment_types "
                + (activeOnly ? "WHERE active = 1 " : "") + "ORDER BY code";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<GarmentType> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new GarmentType(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name_en"),
                        rs.getString("name_hu"),
                        rs.getInt("active") == 1
                ));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list garment types", e);
        }
    }

    public long insertGarmentType(String code, String nameEn, String nameHu) {
        return db.executeInTransaction(conn -> insertGarmentType(conn, code, nameEn, nameHu));
    }

    public long insertGarmentType(Connection conn, String code, String nameEn, String nameHu) throws SQLException {
        String sql = "INSERT INTO garment_types (code, name_en, name_hu, active) VALUES (?, ?, ?, 1)";
        return executeInsertWithKey(conn, sql, ps -> {
            ps.setString(1, code.trim().toUpperCase());
            ps.setString(2, nameEn.trim());
            ps.setString(3, nameHu.trim());
        });
    }

    public void updateGarmentType(long id, String code, String nameEn, String nameHu, boolean active) {
        String sql = "UPDATE garment_types SET code = ?, name_en = ?, name_hu = ?, active = ? WHERE id = ?";
        executeUpdate(sql, ps -> {
            ps.setString(1, code.trim().toUpperCase());
            ps.setString(2, nameEn.trim());
            ps.setString(3, nameHu.trim());
            ps.setInt(4, active ? 1 : 0);
            ps.setLong(5, id);
        });
    }

    public boolean isGarmentTypeReferenced(long id) {
        return existsReference("SELECT 1 FROM inventory_packages WHERE garment_type_id = ? LIMIT 1", id);
    }

    public void deleteGarmentType(long id) {
        executeUpdate("DELETE FROM garment_types WHERE id = ?", ps -> ps.setLong(1, id));
    }

    // --- Quality Grades ---

    public List<QualityGrade> findQualityGrades(boolean activeOnly) {
        String sql = "SELECT id, code, name_en, name_hu, default_price_per_kg, active FROM quality_grades "
                + (activeOnly ? "WHERE active = 1 " : "") + "ORDER BY default_price_per_kg DESC, code";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<QualityGrade> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new QualityGrade(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name_en"),
                        rs.getString("name_hu"),
                        BigDecimal.valueOf(rs.getDouble("default_price_per_kg")),
                        rs.getInt("active") == 1
                ));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list quality grades", e);
        }
    }

    public long insertQualityGrade(String code, String nameEn, String nameHu, BigDecimal defaultPricePerKg) {
        return db.executeInTransaction(conn -> insertQualityGrade(conn, code, nameEn, nameHu, defaultPricePerKg));
    }

    public long insertQualityGrade(Connection conn, String code, String nameEn, String nameHu,
                                   BigDecimal defaultPricePerKg) throws SQLException {
        String sql = """
                INSERT INTO quality_grades (code, name_en, name_hu, default_price_per_kg, active)
                VALUES (?, ?, ?, ?, 1)
                """;
        return executeInsertWithKey(conn, sql, ps -> {
            ps.setString(1, code.trim().toUpperCase());
            ps.setString(2, nameEn.trim());
            ps.setString(3, nameHu.trim());
            ps.setDouble(4, defaultPricePerKg.doubleValue());
        });
    }

    public void updateQualityGrade(long id, String code, String nameEn, String nameHu,
                                   BigDecimal defaultPricePerKg, boolean active) {
        String sql = """
                UPDATE quality_grades
                SET code = ?, name_en = ?, name_hu = ?, default_price_per_kg = ?, active = ?
                WHERE id = ?
                """;
        executeUpdate(sql, ps -> {
            ps.setString(1, code.trim().toUpperCase());
            ps.setString(2, nameEn.trim());
            ps.setString(3, nameHu.trim());
            ps.setDouble(4, defaultPricePerKg.doubleValue());
            ps.setInt(5, active ? 1 : 0);
            ps.setLong(6, id);
        });
    }

    public boolean isQualityGradeReferenced(long id) {
        return existsReference("SELECT 1 FROM inventory_packages WHERE quality_grade_id = ? LIMIT 1", id);
    }

    public void deleteQualityGrade(long id) {
        executeUpdate("DELETE FROM quality_grades WHERE id = ?", ps -> ps.setLong(1, id));
    }

    // --- Package Types ---

    public List<PackageType> findPackageTypes(boolean activeOnly) {
        String sql = "SELECT id, code, name_en, name_hu, tare_weight_kg, max_weight_kg, active FROM package_types "
                + (activeOnly ? "WHERE active = 1 " : "") + "ORDER BY code";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<PackageType> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new PackageType(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name_en"),
                        rs.getString("name_hu"),
                        BigDecimal.valueOf(rs.getDouble("tare_weight_kg")),
                        BigDecimal.valueOf(rs.getDouble("max_weight_kg")),
                        rs.getInt("active") == 1
                ));
            }
            return list;
        } catch (SQLException e) {
            throw new DaoException("Failed to list package types", e);
        }
    }

    public long insertPackageType(String code, String nameEn, String nameHu,
                                  BigDecimal tareWeightKg, BigDecimal maxWeightKg) {
        return db.executeInTransaction(conn -> insertPackageType(conn, code, nameEn, nameHu, tareWeightKg, maxWeightKg));
    }

    public long insertPackageType(Connection conn, String code, String nameEn, String nameHu,
                                  BigDecimal tareWeightKg, BigDecimal maxWeightKg) throws SQLException {
        String sql = """
                INSERT INTO package_types (code, name_en, name_hu, tare_weight_kg, max_weight_kg, active)
                VALUES (?, ?, ?, ?, ?, 1)
                """;
        return executeInsertWithKey(conn, sql, ps -> {
            ps.setString(1, code.trim().toUpperCase());
            ps.setString(2, nameEn.trim());
            ps.setString(3, nameHu.trim());
            ps.setDouble(4, tareWeightKg.doubleValue());
            ps.setDouble(5, maxWeightKg.doubleValue());
        });
    }

    public void updatePackageType(long id, String code, String nameEn, String nameHu,
                                  BigDecimal tareWeightKg, BigDecimal maxWeightKg, boolean active) {
        String sql = """
                UPDATE package_types
                SET code = ?, name_en = ?, name_hu = ?, tare_weight_kg = ?, max_weight_kg = ?, active = ?
                WHERE id = ?
                """;
        executeUpdate(sql, ps -> {
            ps.setString(1, code.trim().toUpperCase());
            ps.setString(2, nameEn.trim());
            ps.setString(3, nameHu.trim());
            ps.setDouble(4, tareWeightKg.doubleValue());
            ps.setDouble(5, maxWeightKg.doubleValue());
            ps.setInt(6, active ? 1 : 0);
            ps.setLong(7, id);
        });
    }

    public boolean isPackageTypeReferenced(long id) {
        return existsReference("SELECT 1 FROM inventory_packages WHERE package_type_id = ? LIMIT 1", id);
    }

    public void deletePackageType(long id) {
        executeUpdate("DELETE FROM package_types WHERE id = ?", ps -> ps.setLong(1, id));
    }

    // --- Shared Helpers ---

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private long executeInsertWithKey(Connection conn, String sql, StatementBinder binder) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            binder.bind(ps);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned");
            }
        }
    }

    private void executeUpdate(String sql, StatementBinder binder) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Dictionary update failed: " + e.getMessage(), e);
        }
    }

    private boolean existsReference(String sql, long id) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to check dictionary reference", e);
        }
    }
}
