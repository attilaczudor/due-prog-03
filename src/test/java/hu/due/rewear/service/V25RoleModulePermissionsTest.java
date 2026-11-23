package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.RoleCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Super-Admin-controlled Role &times; Module permissions persistence,
 * default role presets, Super Admin unrestricted invariant, and non-Super-Admin security guard.
 */
class V25RoleModulePermissionsTest {

    @TempDir
    Path tempDir;

    private AppContext newCtx() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("v25-modules.db"));
        return new AppContext(db, new AppPreferences(), I18nManager.getInstance());
    }

    @Test
    void defaultsMatchRoleWorkflowsAndSuperAdminCanTogglePerRole() {
        AppContext ctx = newCtx();
        RoleModuleService svc = ctx.getRoleModuleService();

        // 1. Verify sensible defaults per role
        assertTrue(svc.isModuleEnabled(RoleCode.SUPER_ADMIN, AppModule.ADMIN));
        assertTrue(svc.isModuleEnabled(RoleCode.ADMIN, AppModule.ADMIN));
        assertFalse(svc.isModuleEnabled(RoleCode.MANAGER, AppModule.ADMIN));
        assertTrue(svc.isModuleEnabled(RoleCode.OPERATOR, AppModule.INTAKE));
        assertFalse(svc.isModuleEnabled(RoleCode.OPERATOR, AppModule.PUTAWAY));
        assertTrue(svc.isModuleEnabled(RoleCode.FORKLIFT, AppModule.PUTAWAY));
        assertTrue(svc.isModuleEnabled(RoleCode.FORKLIFT, AppModule.STAGING));
        assertFalse(svc.isModuleEnabled(RoleCode.FORKLIFT, AppModule.SCANNER));

        // 2. Non-Super-Admin cannot modify module rules
        ctx.getAuthService().login("admin", "admin123".toCharArray());
        assertThrows(SecurityException.class,
                () -> svc.setModuleEnabled(RoleCode.FORKLIFT, AppModule.SCANNER, true));
        ctx.getAuthService().logout();

        // 3. Super Admin enables SCANNER for FORKLIFT and disables EMAIL for OPERATOR
        ctx.getAuthService().login("superadmin", "superadmin123".toCharArray());
        svc.setModuleEnabled(RoleCode.FORKLIFT, AppModule.SCANNER, true);
        svc.setModuleEnabled(RoleCode.OPERATOR, AppModule.EMAIL, false);

        assertTrue(svc.isModuleEnabled(RoleCode.FORKLIFT, AppModule.SCANNER));
        assertFalse(svc.isModuleEnabled(RoleCode.OPERATOR, AppModule.EMAIL));

        // 4. Super Admin can never be locked out of any module, and Dashboard remains always on
        svc.setModuleEnabled(RoleCode.SUPER_ADMIN, AppModule.ADMIN, false);
        svc.setModuleEnabled(RoleCode.FORKLIFT, AppModule.DASHBOARD, false);
        assertTrue(svc.isModuleEnabled(RoleCode.SUPER_ADMIN, AppModule.ADMIN));
        assertTrue(svc.isModuleEnabled(RoleCode.FORKLIFT, AppModule.DASHBOARD));

        // 5. SessionContext reflects live persisted module rules for logged-in users
        ctx.getAuthService().logout();
        ctx.getAuthService().login("forklift", "forklift123".toCharArray());
        assertTrue(ctx.getSession().canAccessModule(AppModule.SCANNER));
        ctx.getAuthService().logout();

        ctx.getAuthService().login("operator", "operator123".toCharArray());
        assertFalse(ctx.getSession().canAccessModule(AppModule.EMAIL));
        ctx.getAuthService().logout();

        // 6. Reset to defaults restores original rules
        ctx.getAuthService().login("superadmin", "superadmin123".toCharArray());
        svc.resetAllToDefaults();
        assertFalse(svc.isModuleEnabled(RoleCode.FORKLIFT, AppModule.SCANNER));
        assertTrue(svc.isModuleEnabled(RoleCode.OPERATOR, AppModule.EMAIL));
    }
}
