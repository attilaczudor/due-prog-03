package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.RoleCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * JDBC DAO for the normalized {@code role_module_permissions} table storing which
 * {@link AppModule} menu modules are enabled for each {@link RoleCode}.
 */
public final class RoleModulePermissionDao {

    private final DatabaseManager db;

    public RoleModulePermissionDao(DatabaseManager db) {
        this.db = db;
    }

    /** Seeds missing {@code (role_id, module_code)} rows with role defaults without overwriting customizations. */
    public void seedDefaultsIfMissing() {
        String findRoleSql = "SELECT id, code FROM roles";
        String insertSql = """
                INSERT OR IGNORE INTO role_module_permissions (role_id, module_code, enabled, updated_at)
                VALUES (?, ?, ?, datetime('now'))
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement rolePs = conn.prepareStatement(findRoleSql);
             ResultSet rs = rolePs.executeQuery();
             PreparedStatement insPs = conn.prepareStatement(insertSql)) {
            while (rs.next()) {
                long roleId = rs.getLong("id");
                RoleCode roleCode;
                try {
                    roleCode = RoleCode.valueOf(rs.getString("code"));
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                for (AppModule mod : AppModule.values()) {
                    insPs.setLong(1, roleId);
                    insPs.setString(2, mod.name());
                    insPs.setInt(3, mod.defaultEnabledFor(roleCode) ? 1 : 0);
                    insPs.addBatch();
                }
            }
            insPs.executeBatch();
        } catch (SQLException e) {
            throw new DaoException("Failed to seed role_module_permissions defaults", e);
        }
    }

    public Set<AppModule> findEnabledModules(RoleCode roleCode) {
        if (roleCode == RoleCode.SUPER_ADMIN) {
            return EnumSet.allOf(AppModule.class);
        }
        String sql = """
                SELECT p.module_code, p.enabled
                FROM role_module_permissions p
                JOIN roles r ON r.id = p.role_id
                WHERE r.code = ?
                """;
        EnumSet<AppModule> enabled = EnumSet.noneOf(AppModule.class);
        boolean anyRow = false;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roleCode.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    anyRow = true;
                    if (rs.getInt("enabled") == 1) {
                        AppModule.fromCode(rs.getString("module_code")).ifPresent(enabled::add);
                    }
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to query role_module_permissions for " + roleCode, e);
        }
        if (!anyRow) {
            return AppModule.defaultsFor(roleCode);
        }
        for (AppModule m : AppModule.values()) {
            if (m.isAlwaysEnabled()) {
                enabled.add(m);
            }
        }
        return enabled;
    }

    public Map<RoleCode, Set<AppModule>> findAllMatrix() {
        Map<RoleCode, Set<AppModule>> matrix = new EnumMap<>(RoleCode.class);
        for (RoleCode role : RoleCode.values()) {
            matrix.put(role, findEnabledModules(role));
        }
        return matrix;
    }

    public void setEnabled(RoleCode roleCode, AppModule module, boolean enabled) {
        String sql = """
                INSERT INTO role_module_permissions (role_id, module_code, enabled, updated_at)
                VALUES ((SELECT id FROM roles WHERE code = ?), ?, ?, datetime('now'))
                ON CONFLICT(role_id, module_code) DO UPDATE SET
                    enabled = excluded.enabled,
                    updated_at = datetime('now')
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roleCode.name());
            ps.setString(2, module.name());
            ps.setInt(3, enabled ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to update module permission " + roleCode + "/" + module, e);
        }
    }
}
