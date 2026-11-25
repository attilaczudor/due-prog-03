package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.ui.AdminModulesPanel;
import hu.due.rewear.ui.AdminView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import javax.swing.JCheckBox;
import javax.swing.JToggleButton;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

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
        return new AppContext(db, new AppPreferences(tempDir.resolve("app.properties")), new I18nManager());
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
        // The two driver roles have different jobs: the supply driver serves the sorting floor,
        // the delivery driver serves customer orders.
        assertTrue(svc.isModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.PUTAWAY));
        assertTrue(svc.isModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.SORTING));
        assertFalse(svc.isModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.STAGING));
        assertTrue(svc.isModuleEnabled(RoleCode.DELIVERY_DRIVER, AppModule.STAGING));
        assertFalse(svc.isModuleEnabled(RoleCode.DELIVERY_DRIVER, AppModule.PUTAWAY));
        assertFalse(svc.isModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.SCANNER));
        // Payroll figures stay with the office roles responsible for them.
        assertTrue(svc.isModuleEnabled(RoleCode.HR_PAYROLL, AppModule.PAYROLL));
        assertTrue(svc.isModuleEnabled(RoleCode.HR_PAYROLL, AppModule.ROTA));
        assertFalse(svc.isModuleEnabled(RoleCode.MANAGER, AppModule.PAYROLL));
        assertFalse(svc.isModuleEnabled(RoleCode.HR_PAYROLL, AppModule.INVENTORY));
        // The production leaders run the floor but see no money.
        assertTrue(svc.isModuleEnabled(RoleCode.PRODUCTION_LEAD, AppModule.SORTING));
        assertTrue(svc.isModuleEnabled(RoleCode.PRODUCTION_LEAD, AppModule.DICTIONARIES));
        assertFalse(svc.isModuleEnabled(RoleCode.PRODUCTION_LEAD, AppModule.INVOICES));
        assertTrue(svc.isModuleEnabled(RoleCode.SHIFT_LEAD, AppModule.ROTA));
        assertFalse(svc.isModuleEnabled(RoleCode.SHIFT_LEAD, AppModule.DICTIONARIES));
        assertFalse(svc.isModuleEnabled(RoleCode.SHIFT_LEAD, AppModule.INVOICES));

        // 2. Non-Super-Admin cannot modify module rules
        ctx.getAuthService().login("admin", "admin123".toCharArray());
        assertThrows(SecurityException.class,
                () -> svc.setModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.SCANNER, true));
        ctx.getAuthService().logout();

        // 3. Super Admin enables SCANNER for FORKLIFT and disables EMAIL for OPERATOR
        ctx.getAuthService().login("superadmin", "superadmin123".toCharArray());
        svc.setModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.SCANNER, true);
        svc.setModuleEnabled(RoleCode.OPERATOR, AppModule.EMAIL, false);

        assertTrue(svc.isModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.SCANNER));
        assertFalse(svc.isModuleEnabled(RoleCode.OPERATOR, AppModule.EMAIL));

        // 4. Super Admin can never be locked out of any module, and Dashboard remains always on
        svc.setModuleEnabled(RoleCode.SUPER_ADMIN, AppModule.ADMIN, false);
        svc.setModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.DASHBOARD, false);
        assertTrue(svc.isModuleEnabled(RoleCode.SUPER_ADMIN, AppModule.ADMIN));
        assertTrue(svc.isModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.DASHBOARD));

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
        assertFalse(svc.isModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.SCANNER));
        assertTrue(svc.isModuleEnabled(RoleCode.OPERATOR, AppModule.EMAIL));
    }

    @Test
    void adminMenuOrderPreservesSuperAdminAccess() throws Exception {
        AppContext ctx = newCtx();
        assertTrue(ctx.getAuthService().login("superadmin", "superadmin123".toCharArray()).isSuccess());
        ctx.getI18n().setLanguage("hu");

        SwingUtilities.invokeAndWait(() -> {
            AdminView view = new AdminView(ctx);
            view.refreshData();
            assertEquals(List.of("Felhasználók", "Szerepkörök", "Számlabeállítások", "E-mail beállítások", "NAV-integráció", "Banki integráció", "Adatbázis",
                    "Fordítások", "Log", "Rendszer"), menuLabels(view));
            String label = ctx.getI18n().t("admin.tab.modules");
            AdminModulesPanel matrix = components(view, AdminModulesPanel.class).findFirst().orElseThrow();
            assertFalse(matrix.isVisible());
            JToggleButton modules = components(view, JToggleButton.class)
                    .filter(button -> label.equals(button.getAccessibleContext().getAccessibleName()))
                    .findFirst().orElseThrow();
            modules.doClick(0);
            assertTrue(matrix.isVisible());
        });

        assertTrue(ctx.getAuthService().login("admin", "admin123".toCharArray()).isSuccess());
        ctx.getI18n().setLanguage("en");
        SwingUtilities.invokeAndWait(() -> {
            AdminView view = new AdminView(ctx);
            assertEquals(List.of("Users", "Invoice Settings", "NAV Integration", "Bank Integration", "Database", "Translations", "Log", "System"), menuLabels(view));
            assertEquals(0, components(view, AdminModulesPanel.class).count());
        });
    }

    @Test
    void translationAlertsAreScopedDeduplicatedAndClearAfterReload() throws Exception {
        var ctx = newCtx();
        var i18n = ctx.getI18n();
        var service = ctx.getNotificationService();
        var admin = ctx.getUserDao().findByUsername("admin").orElseThrow();
        var superAdmin = ctx.getUserDao().findByUsername("superadmin").orElseThrow();
        var operator = ctx.getUserDao().findByUsername("operator").orElseThrow();
        Path csv = tempDir.resolve("translations.csv");
        Files.writeString(csv, "key,en,hu\ncustom.label,First,Első\n");
        i18n.loadFromFile(csv);
        assertEquals(0, service.checkTranslationUpdate(admin, i18n.externalSourceStatus(csv.toString()), i18n));

        Files.writeString(csv, "key,en,hu\ncustom.label,Updated,Frissítve\n");
        var changed = i18n.externalSourceStatus(csv.toString());
        assertEquals(1, service.checkTranslationUpdate(admin, changed, i18n));
        assertEquals(1, service.checkTranslationUpdate(admin, changed, i18n));
        assertEquals(1, service.checkTranslationUpdate(superAdmin, changed, i18n));
        assertEquals(0, service.checkTranslationUpdate(operator, changed, i18n));
        var alerts = service.listForUser(admin.id(), 100).stream()
                .filter(item -> NotificationService.TRANSLATIONS_TARGET.equals(item.targetView())).toList();
        assertEquals(1, alerts.size());
        assertEquals(i18n.t("admin.i18n.alert.changed"), alerts.get(0).message());
        service.markRead(alerts.get(0).id());
        assertEquals(0, service.checkTranslationUpdate(admin, changed, i18n));

        i18n.loadFromFile(csv);
        assertEquals(0, service.checkTranslationUpdate(superAdmin, i18n.externalSourceStatus(csv.toString()), i18n));
        assertEquals(0, service.checkTranslationUpdate(admin, i18n.externalSourceStatus(csv.toString()), i18n));
        Files.delete(csv);
        assertEquals(1, service.checkTranslationUpdate(admin, i18n.externalSourceStatus(csv.toString()), i18n));
    }

    private static List<String> menuLabels(AdminView view) {
        return components(view, JToggleButton.class)
                .filter(button -> !(button instanceof JCheckBox))
                .map(button -> button.getAccessibleContext().getAccessibleName()).toList();
    }

    private static <T extends Component> Stream<T> components(Container parent, Class<T> type) {
        return Arrays.stream(parent.getComponents()).flatMap(child -> {
            Stream<T> match = type.isInstance(child) ? Stream.of(type.cast(child)) : Stream.empty();
            return child instanceof Container nested ? Stream.concat(match, components(nested, type)) : match;
        });
    }
}
