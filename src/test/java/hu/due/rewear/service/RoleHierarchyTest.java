package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.RoleCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The company hierarchy: who exists, who may see what, and how a database written when there was a single
 * forklift role is moved onto the two driver roles that replaced it.
 */
class RoleHierarchyTest {

    @TempDir
    Path tempDir;

    private AppContext open(String name) {
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        return new AppContext(new DatabaseManager(tempDir.resolve(name)),
                new AppPreferences(tempDir.resolve(name + ".properties")), i18n);
    }

    @Test
    void everyRoleExistsInTheDatabaseAndIsNamedInBothLanguages() {
        AppContext ctx = open("roles.db");
        I18nManager i18n = ctx.getI18n();

        for (RoleCode role : RoleCode.values()) {
            assertTrue(ctx.getRoleDao().findByCode(role).isPresent(), "Missing role row: " + role);
            for (String language : new String[]{"en", "hu"}) {
                i18n.setLanguage(language);
                String label = i18n.t("role." + role.name());
                assertFalse(label.startsWith("["), role + " has no " + language + " name");
            }
        }
        i18n.setLanguage("en");
        assertEquals(11, RoleCode.values().length);
    }

    @Test
    void theTwoDriverRolesHaveDifferentWorkAndTheOfficeRolesSeeDifferentThings() {
        AppContext ctx = open("modules.db");
        RoleModuleService modules = ctx.getRoleModuleService();

        // Supply serves the sorting floor; delivery serves customer orders.
        assertTrue(modules.isModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.PUTAWAY));
        assertTrue(modules.isModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.SORTING));
        assertFalse(modules.isModuleEnabled(RoleCode.SUPPLY_DRIVER, AppModule.STAGING));
        assertTrue(modules.isModuleEnabled(RoleCode.DELIVERY_DRIVER, AppModule.STAGING));
        assertFalse(modules.isModuleEnabled(RoleCode.DELIVERY_DRIVER, AppModule.SORTING));

        // Money is visible only where it belongs.
        for (RoleCode role : EnumSet.of(RoleCode.PRODUCTION_LEAD, RoleCode.SHIFT_LEAD, RoleCode.HR_PAYROLL,
                RoleCode.OPERATOR, RoleCode.SUPPLY_DRIVER, RoleCode.DELIVERY_DRIVER, RoleCode.SORTER,
                RoleCode.SORTING_HELPER)) {
            assertFalse(modules.isModuleEnabled(role, AppModule.INVOICES), role + " must not see invoices");
            assertFalse(modules.isModuleEnabled(role, AppModule.ADMIN), role + " must not see administration");
        }
        assertTrue(modules.isModuleEnabled(RoleCode.HR_PAYROLL, AppModule.PAYROLL));
        assertFalse(modules.isModuleEnabled(RoleCode.PRODUCTION_LEAD, AppModule.PAYROLL),
                "Payroll amounts belong to HR and the administrators");

        // The production lead owns the reference data; the shift lead only runs the shift.
        assertTrue(modules.isModuleEnabled(RoleCode.PRODUCTION_LEAD, AppModule.DICTIONARIES));
        assertFalse(modules.isModuleEnabled(RoleCode.SHIFT_LEAD, AppModule.DICTIONARIES));

        // Everyone who works a shift can look at the rota.
        for (RoleCode role : RoleCode.values()) {
            assertTrue(modules.isModuleEnabled(role, AppModule.ROTA), role + " should see the rota");
        }
        assertEquals(Set.of(RoleCode.SUPER_ADMIN, RoleCode.ADMIN, RoleCode.MANAGER, RoleCode.PRODUCTION_LEAD,
                RoleCode.SHIFT_LEAD, RoleCode.HR_PAYROLL),
                Set.copyOf(RoleCode.ROTA_PLANNERS), "Only these roles plan other people's shifts");
        assertFalse(RoleCode.SORTER.plansRota());
        assertTrue(RoleCode.SHIFT_LEAD.runsProduction());
        assertFalse(RoleCode.SHIFT_LEAD.isManagement());
    }

    @Test
    void aDatabaseWithTheOldSingleForkliftRoleIsSplitOnceByWhatEachDriverActuallyDid() throws SQLException {
        AppContext ctx = open("legacy.db");
        long supplyDriver = ctx.getUserDao().findByUsername("forklift").orElseThrow().id();
        long deliveryDriver = ctx.getUserDao().findByUsername("forklift2").orElseThrow().id();
        DatabaseManager db = ctx.getDatabaseManager();

        // Put the database back the way it looked with one driver role: both drivers share it.
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            st.execute("UPDATE users SET role_id=(SELECT id FROM roles WHERE code='SUPPLY_DRIVER') "
                    + "WHERE id IN (" + supplyDriver + "," + deliveryDriver + ")");
            st.execute("DELETE FROM role_module_permissions WHERE role_id=(SELECT id FROM roles WHERE code='DELIVERY_DRIVER')");
            st.execute("DELETE FROM roles WHERE code='DELIVERY_DRIVER'");
            st.execute("UPDATE roles SET code='FORKLIFT', name='Forklift Driver' WHERE code='SUPPLY_DRIVER'");
        }

        new SchemaInitializer(db).initialize();

        // The driver who supplies a sorting team keeps that job; the other one serves customer orders.
        assertEquals(RoleCode.SUPPLY_DRIVER, ctx.getUserDao().findById(supplyDriver).orElseThrow().roleCode());
        assertEquals(RoleCode.DELIVERY_DRIVER, ctx.getUserDao().findById(deliveryDriver).orElseThrow().roleCode());
        assertTrue(ctx.getRoleDao().findByCode(RoleCode.SUPPLY_DRIVER).isPresent());
        assertTrue(ctx.getRoleDao().findByCode(RoleCode.DELIVERY_DRIVER).isPresent());

        // Running again must not move anyone: a company may reassign drivers afterwards.
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            st.execute("UPDATE users SET role_id=(SELECT id FROM roles WHERE code='SUPPLY_DRIVER') WHERE id=" + deliveryDriver);
        }
        new SchemaInitializer(db).initialize();
        assertEquals(RoleCode.SUPPLY_DRIVER, ctx.getUserDao().findById(deliveryDriver).orElseThrow().roleCode(),
                "The one-off split must not run a second time");
    }
}
