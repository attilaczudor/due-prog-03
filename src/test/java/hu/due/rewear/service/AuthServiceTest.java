package hu.due.rewear.service;

import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.DemoDataSeeder;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.dao.RoleDao;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.model.RoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    @TempDir
    Path tempDir;

    private UserDao userDao;
    private RoleDao roleDao;
    private SessionContext session;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("auth-test.db"));
        new SchemaInitializer(db).initialize();
        new DemoDataSeeder(db).seedIfEmpty();
        userDao = new UserDao(db);
        roleDao = new RoleDao(db);
        session = new SessionContext();
        authService = new AuthService(userDao, session);
    }

    @Test
    void authenticatesDemoUsersAndEnforcesRbacMatrix() {
        // Operator permissions
        AuthService.AuthResult opRes = authService.login("operator", "operator123".toCharArray());
        assertTrue(opRes.isSuccess());
        assertFalse(session.isAdmin());
        assertFalse(session.canManageOrders());
        assertFalse(session.canManageLocationsAndDictionaries());
        assertFalse(session.canManageUsersAndMaintenance());

        // Manager permissions
        authService.logout();
        assertFalse(session.isAuthenticated());
        AuthService.AuthResult mgrRes = authService.login("manager", "manager123".toCharArray());
        assertTrue(mgrRes.isSuccess());
        assertFalse(session.isAdmin());
        assertTrue(session.canManageOrders());
        assertTrue(session.canManageLocationsAndDictionaries());
        assertFalse(session.canManageUsersAndMaintenance());

        // Admin permissions
        AuthService.AuthResult adminRes = authService.login("admin", "admin123".toCharArray());
        assertTrue(adminRes.isSuccess());
        assertTrue(session.isAdmin());
        assertTrue(session.canManageUsersAndMaintenance());
    }

    @Test
    void rejectsWrongPasswordAndInactiveUsers() {
        AuthService.AuthResult bad = authService.login("admin", "wrong".toCharArray());
        assertEquals(AuthService.AuthStatus.INVALID_CREDENTIALS, bad.status());
        assertFalse(session.isAuthenticated());

        long operatorRoleId = roleDao.findByCode(RoleCode.OPERATOR).orElseThrow().id();
        authService.createUser("disabled_op", "secret123", "Disabled Operator", operatorRoleId, false);

        AuthService.AuthResult inactive = authService.login("disabled_op", "secret123".toCharArray());
        assertEquals(AuthService.AuthStatus.INACTIVE_USER, inactive.status());
    }

    @Test
    void supportsPasswordChangeAndPersistsUiPreferences() {
        long adminId = userDao.findByUsername("admin").orElseThrow().id();
        assertFalse(authService.changePassword(adminId, "wrong", "newPass123"));
        assertTrue(authService.changePassword(adminId, "admin123", "newPass123"));

        assertTrue(authService.login("admin", "newPass123".toCharArray()).isSuccess());

        Path prefsFile = tempDir.resolve("app.properties");
        AppPreferences prefs = new AppPreferences(prefsFile);
        prefs.setLanguage("hu");
        prefs.setDarkTheme(true);

        AppPreferences reloaded = new AppPreferences(prefsFile);
        assertEquals("hu", reloaded.getLanguage());
        assertTrue(reloaded.isDarkTheme());
    }
}
