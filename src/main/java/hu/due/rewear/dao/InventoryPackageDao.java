package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.CategoryWeightStat;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.PackageFilter;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.StockValuation;

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
import java.util.Locale;

/**
 * Data Access Object for physical sorted-clothing packages ({@code inventory_packages} table).
 * Joins target group, garment type (with dual EUR/HUF pricing), quality grade, package type,
 * warehouse location, and creator metadata for inventory search, QR lookup, and dashboard analytics.
 */
public final class InventoryPackageDao {

    private static final String BASE_SELECT = """
            SELECT ip.id, ip.qr_code,
                   ip.target_group_id, tg.code AS tg_code, tg.name_en AS tg_en, tg.name_hu AS tg_hu,
                   ip.garment_type_id, gt.code AS gt_code, gt.name_en AS gt_en, gt.name_hu AS gt_hu,
                   gt.price_eur_per_kg AS gt_price_eur, gt.price_huf_per_kg AS gt_price_huf,
                   ip.quality_grade_id, qg.code AS qg_code, qg.name_en AS qg_en, qg.name_hu AS qg_hu,
                   qg.default_price_per_kg, qg.default_price_huf_per_kg,
                   ip.package_type_id, pt.code AS pt_code, pt.name_en AS pt_en, pt.name_hu AS pt_hu,
                   ip.location_id, wl.code AS loc_code,
                   ip.weight_kg, ip.hex_suffix, ip.status, ip.notes,
                   ip.created_by, u.username AS created_by_user, u.full_name AS created_by_name,
                   ip.created_at, ip.updated_at
            FROM inventory_packages ip
            JOIN target_groups tg ON tg.id = ip.target_group_id
            JOIN garment_types gt ON gt.id = ip.garment_type_id
            JOIN quality_grades qg ON qg.id = ip.quality_grade_id
            JOIN package_types pt ON pt.id = ip.package_type_id
            JOIN warehouse_locations wl ON wl.id = ip.location_id
            JOIN users u ON u.id = ip.created_by
            """;

    private final DatabaseManager db;

    public InventoryPackageDao(DatabaseManager db) {
        this.db = db;
    }

    public long insert(Connection conn, String qrCode, long targetGroupId, long garmentTypeId,
                       long qualityGradeId, long packageTypeId, long locationId,
                       BigDecimal weightKg, String hexSuffix, PackageStatus status,
                       String notes, long createdBy, LocalDateTime createdAt) throws SQLException {
        String sql = """
                INSERT INTO inventory_packages (
                    qr_code, target_group_id, garment_type_id, quality_grade_id, package_type_id,
                    location_id, weight_kg, hex_suffix, status, notes, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String ts = DaoDateUtil.format(createdAt);
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, qrCode.trim().toUpperCase(Locale.ROOT));
            ps.setLong(2, targetGroupId);
            ps.setLong(3, garmentTypeId);
            ps.setLong(4, qualityGradeId);
            ps.setLong(5, packageTypeId);
            ps.setLong(6, locationId);
            ps.setDouble(7, weightKg.doubleValue());
            ps.setString(8, hexSuffix.trim().toUpperCase(Locale.ROOT));
            ps.setString(9, status.name());
            ps.setString(10, notes);
            ps.setLong(11, createdBy);
            ps.setString(12, ts);
            ps.setString(13, ts);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned for inventory_packages insert");
            }
        }
    }

    public boolean existsByQrCode(String qrCode) {
        try (Connection conn = db.getConnection()) {
            return existsByQrCode(conn, qrCode);
        } catch (SQLException e) {
            throw new DaoException("Failed to check QR code existence: " + qrCode, e);
        }
    }

    /** Transaction-aware overload that reuses the caller's active SQLite {@link Connection}. */
    public boolean existsByQrCode(Connection conn, String qrCode) throws SQLException {
        String sql = "SELECT 1 FROM inventory_packages WHERE upper(qr_code) = upper(?) LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qrCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Optional<InventoryPackage> findById(long id) {
        try (Connection conn = db.getConnection()) {
            return findById(conn, id);
        } catch (SQLException e) {
            throw new DaoException("Failed to find package by id: " + id, e);
        }
    }

    /** Transaction-aware overload that reuses the caller's active SQLite {@link Connection}. */
    public Optional<InventoryPackage> findById(Connection conn, long id) throws SQLException {
        String sql = BASE_SELECT + " WHERE ip.id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Lists all packages currently assigned to a specific physical {@code locationId}. */
    public List<InventoryPackage> findByLocationId(long locationId) {
        String sql = BASE_SELECT + " WHERE ip.location_id = ? ORDER BY ip.created_at DESC, ip.id DESC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, locationId);
            try (ResultSet rs = ps.executeQuery()) {
                List<InventoryPackage> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find packages by locationId: " + locationId, e);
        }
    }

    public Optional<InventoryPackage> findByQrCode(String qrCode) {
        String sql = BASE_SELECT + " WHERE upper(ip.qr_code) = upper(?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qrCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find package by QR code: " + qrCode, e);
        }
    }

    public List<InventoryPackage> findPutawayQueue() {
        String sql = BASE_SELECT + """
                WHERE ip.status = 'IN_STOCK' AND wl.location_type = 'OPERATOR_STATION'
                ORDER BY wl.code, ip.created_at DESC, ip.id DESC
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<InventoryPackage> packages = new ArrayList<>();
            while (rs.next()) {
                packages.add(mapRow(rs));
            }
            return packages;
        } catch (SQLException e) {
            throw new DaoException("Failed to load the putaway queue", e);
        }
    }

    public int countPutawayQueue() {
        String sql = """
                SELECT COUNT(*) FROM inventory_packages ip
                JOIN warehouse_locations wl ON wl.id = ip.location_id
                WHERE ip.status = 'IN_STOCK' AND wl.location_type = 'OPERATOR_STATION'
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new DaoException("Failed to count the putaway queue", e);
        }
    }

    /** What a filter matches in total, however many rows the screen chooses to show. */
    public record FilterSummary(long packages, BigDecimal totalWeightKg) { }

    /** Builds the {@code WHERE} clause shared by the listing and its totals. */
    private static String whereClause(PackageFilter filter, List<Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1 ");
        if (filter != null) {
            if (filter.qrSearch() != null && !filter.qrSearch().isBlank()) {
                sql.append(" AND (upper(ip.qr_code) LIKE upper(?) OR lower(COALESCE(ip.notes, '')) LIKE lower(?)) ");
                String pattern = "%" + filter.qrSearch().trim() + "%";
                params.add(pattern);
                params.add(pattern);
            }
            if (filter.status() != null) {
                sql.append(" AND ip.status = ? ");
                params.add(filter.status().name());
            }
            if (filter.targetGroupId() != null) {
                sql.append(" AND ip.target_group_id = ? ");
                params.add(filter.targetGroupId());
            }
            if (filter.garmentTypeId() != null) {
                sql.append(" AND ip.garment_type_id = ? ");
                params.add(filter.garmentTypeId());
            }
            if (filter.qualityGradeId() != null) {
                sql.append(" AND ip.quality_grade_id = ? ");
                params.add(filter.qualityGradeId());
            }
            if (filter.packageTypeId() != null) {
                sql.append(" AND ip.package_type_id = ? ");
                params.add(filter.packageTypeId());
            }
            if (filter.locationId() != null) {
                sql.append(" AND ip.location_id = ? ");
                params.add(filter.locationId());
            }
        }
        return sql.toString();
    }

    public List<InventoryPackage> findByFilter(PackageFilter filter) {
        return findByFilter(filter, 0);
    }

    /**
     * Packages matching the filter, newest first.
     *
     * @param limit how many rows at most, or {@code 0} for all of them. A warehouse can hold tens of
     *              thousands of packages, and a screen that tries to show every one of them keeps a person
     *              waiting for rows they will never scroll to.
     */
    public List<InventoryPackage> findByFilter(PackageFilter filter, int limit) {
        List<Object> params = new ArrayList<>();
        String sql = BASE_SELECT + whereClause(filter, params)
                + " ORDER BY ip.created_at DESC, ip.id DESC" + (limit > 0 ? " LIMIT " + limit : "");
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<InventoryPackage> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to search inventory packages", e);
        }
    }

    /** How many packages the filter matches and what they weigh, counted in the database. */
    public FilterSummary summarise(PackageFilter filter) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*) AS packages, COALESCE(SUM(ip.weight_kg), 0) AS total_kg"
                + " FROM inventory_packages ip" + whereClause(filter, params);
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new FilterSummary(rs.getLong("packages"),
                        BigDecimal.valueOf(rs.getDouble("total_kg")));
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to summarise inventory packages", e);
        }
    }

    public void updateLocation(Connection conn, long packageId, long newLocationId, LocalDateTime updatedAt) throws SQLException {
        String sql = "UPDATE inventory_packages SET location_id = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, newLocationId);
            ps.setString(2, DaoDateUtil.format(updatedAt));
            ps.setLong(3, packageId);
            ps.executeUpdate();
        }
    }

    /** Corrects the recorded weight of a package, used when a stocktake reweighs it. */
    public void updateWeight(Connection conn, long packageId, BigDecimal weightKg, LocalDateTime updatedAt) throws SQLException {
        String sql = "UPDATE inventory_packages SET weight_kg = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, weightKg);
            ps.setString(2, DaoDateUtil.format(updatedAt));
            ps.setLong(3, packageId);
            ps.executeUpdate();
        }
    }

    public void updateStatus(Connection conn, long packageId, PackageStatus newStatus, LocalDateTime updatedAt) throws SQLException {
        String sql = "UPDATE inventory_packages SET status = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus.name());
            ps.setString(2, DaoDateUtil.format(updatedAt));
            ps.setLong(3, packageId);
            ps.executeUpdate();
        }
    }

    public void updateDetails(Connection conn, long packageId, String qrCode,
                              long targetGroupId, long garmentTypeId, long qualityGradeId,
                              long packageTypeId, long locationId, BigDecimal weightKg,
                              String hexSuffix, PackageStatus status, String notes,
                              LocalDateTime updatedAt) throws SQLException {
        String sql = """
                UPDATE inventory_packages
                SET qr_code = ?, target_group_id = ?, garment_type_id = ?, quality_grade_id = ?,
                    package_type_id = ?, location_id = ?, weight_kg = ?, hex_suffix = ?,
                    status = ?, notes = ?, updated_at = ?
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qrCode.trim().toUpperCase(Locale.ROOT));
            ps.setLong(2, targetGroupId);
            ps.setLong(3, garmentTypeId);
            ps.setLong(4, qualityGradeId);
            ps.setLong(5, packageTypeId);
            ps.setLong(6, locationId);
            ps.setDouble(7, weightKg.doubleValue());
            ps.setString(8, hexSuffix.trim().toUpperCase(Locale.ROOT));
            ps.setString(9, status.name());
            ps.setString(10, notes);
            ps.setString(11, DaoDateUtil.format(updatedAt));
            ps.setLong(12, packageId);
            ps.executeUpdate();
        }
    }

    public int countByStatus(PackageStatus status) {
        String sql = "SELECT COUNT(*) FROM inventory_packages WHERE status = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to count packages by status", e);
        }
    }

    public BigDecimal sumWeightByStatus(PackageStatus status) {
        String sql = "SELECT COALESCE(SUM(weight_kg), 0) FROM inventory_packages WHERE status = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? BigDecimal.valueOf(rs.getDouble(1)) : BigDecimal.ZERO;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to sum package weight by status", e);
        }
    }

    /**
     * Computes live warehouse stock valuation (in both EUR and HUF) across all {@code IN_STOCK}
     * and {@code RESERVED} packages in a single SQL query.
     */
    public StockValuation sumInStockValuation() {
        String sql = """
                SELECT COUNT(ip.id) AS pkg_count,
                       COALESCE(SUM(ip.weight_kg), 0) AS total_kg,
                       COALESCE(SUM(ip.weight_kg * CAST(CASE WHEN CAST(gt.price_eur_per_kg AS REAL) > 0
                           THEN gt.price_eur_per_kg ELSE qg.default_price_per_kg END AS REAL)), 0) AS total_eur,
                       COALESCE(SUM(ip.weight_kg * CAST(CASE WHEN CAST(gt.price_huf_per_kg AS REAL) > 0
                           THEN gt.price_huf_per_kg ELSE qg.default_price_huf_per_kg END AS REAL)), 0) AS total_huf
                FROM inventory_packages ip
                JOIN garment_types gt ON gt.id = ip.garment_type_id
                JOIN quality_grades qg ON qg.id = ip.quality_grade_id
                WHERE ip.status IN ('IN_STOCK', 'RESERVED')
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new StockValuation(
                        rs.getInt("pkg_count"),
                        BigDecimal.valueOf(rs.getDouble("total_kg")),
                        BigDecimal.valueOf(rs.getDouble("total_eur")),
                        BigDecimal.valueOf(rs.getDouble("total_huf"))
                );
            }
            return StockValuation.zero();
        } catch (SQLException e) {
            throw new DaoException("Failed to compute stock valuation", e);
        }
    }

    public int countCreatedOnDate(LocalDate date) {
        String sql = "SELECT COUNT(*) FROM inventory_packages WHERE substr(created_at, 1, 10) = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to count intake on date " + date, e);
        }
    }

    public List<CategoryWeightStat> sumInStockWeightByQualityGrade() {
        String sql = """
                SELECT qg.code, qg.name_en, qg.name_hu,
                       COUNT(ip.id) AS pkg_count,
                       COALESCE(SUM(ip.weight_kg), 0) AS total_weight
                FROM quality_grades qg
                JOIN inventory_packages ip ON ip.quality_grade_id = qg.id
                WHERE ip.status IN ('IN_STOCK', 'RESERVED')
                GROUP BY qg.id
                ORDER BY total_weight DESC
                """;
        return queryStats(sql);
    }

    public List<CategoryWeightStat> sumInStockWeightByGarmentType() {
        String sql = """
                SELECT gt.code, gt.name_en, gt.name_hu,
                       COUNT(ip.id) AS pkg_count,
                       COALESCE(SUM(ip.weight_kg), 0) AS total_weight
                FROM garment_types gt
                JOIN inventory_packages ip ON ip.garment_type_id = gt.id
                WHERE ip.status IN ('IN_STOCK', 'RESERVED')
                GROUP BY gt.id
                ORDER BY total_weight DESC
                """;
        return queryStats(sql);
    }

    private List<CategoryWeightStat> queryStats(String sql) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<CategoryWeightStat> stats = new ArrayList<>();
            while (rs.next()) {
                stats.add(new CategoryWeightStat(
                        rs.getString("code"),
                        rs.getString("name_en"),
                        rs.getString("name_hu"),
                        rs.getInt("pkg_count"),
                        BigDecimal.valueOf(rs.getDouble("total_weight"))
                ));
            }
            return stats;
        } catch (SQLException e) {
            throw new DaoException("Failed to query category weight statistics", e);
        }
    }

    private static InventoryPackage mapRow(ResultSet rs) throws SQLException {
        return new InventoryPackage(
                rs.getLong("id"),
                rs.getString("qr_code"),
                rs.getLong("target_group_id"),
                rs.getString("tg_code"),
                rs.getString("tg_en"),
                rs.getString("tg_hu"),
                rs.getLong("garment_type_id"),
                rs.getString("gt_code"),
                rs.getString("gt_en"),
                rs.getString("gt_hu"),
                BigDecimal.valueOf(rs.getDouble("gt_price_eur")),
                BigDecimal.valueOf(rs.getDouble("gt_price_huf")),
                rs.getLong("quality_grade_id"),
                rs.getString("qg_code"),
                rs.getString("qg_en"),
                rs.getString("qg_hu"),
                BigDecimal.valueOf(rs.getDouble("default_price_per_kg")),
                BigDecimal.valueOf(rs.getDouble("default_price_huf_per_kg")),
                rs.getLong("package_type_id"),
                rs.getString("pt_code"),
                rs.getString("pt_en"),
                rs.getString("pt_hu"),
                rs.getLong("location_id"),
                rs.getString("loc_code"),
                BigDecimal.valueOf(rs.getDouble("weight_kg")),
                rs.getString("hex_suffix"),
                PackageStatus.valueOf(rs.getString("status")),
                rs.getString("notes"),
                rs.getLong("created_by"),
                rs.getString("created_by_user"),
                rs.getString("created_by_name"),
                DaoDateUtil.parse(rs.getString("created_at")),
                DaoDateUtil.parse(rs.getString("updated_at"))
        );
    }
}
