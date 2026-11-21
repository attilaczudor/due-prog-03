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

    @Test
    void updatesOwnProfileAndProtectsSuperAdminRoleImmutability() {
        AuthService.AuthResult superLogin = authService.login("superadmin", "superadmin123".toCharArray());
        assertTrue(superLogin.isSuccess());
        assertEquals(RoleCode.SUPER_ADMIN, superLogin.user().roleCode());
        assertEquals("CA", superLogin.user().initials());
        assertEquals("Czudor Attila", superLogin.user().displayName());
        assertTrue(session.isSuperAdmin());
        assertTrue(session.isAdmin());

        long operatorRoleId = roleDao.findByCode(RoleCode.OPERATOR).orElseThrow().id();
        byte[] fakePng = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A};
        // Attempt to change Super Admin role to Operator must be ignored (always stays SUPER_ADMIN)
        var updated = authService.updateOwnProfile(
                superLogin.user().id(),
                "superadmin",
                "Czudor",
                "Attila",
                "attila@rewear.hu",
                "+36 30 555 0199",
                fakePng,
                operatorRoleId
        );

        assertEquals(RoleCode.SUPER_ADMIN, updated.roleCode());
        assertEquals("Czudor", updated.safeLastName());
        assertEquals("Attila", updated.safeFirstName());
        assertEquals("Czudor Attila", updated.fullName());
        assertEquals("attila@rewear.hu", updated.email());
        assertEquals("+36 30 555 0199", updated.phone());
        assertEquals(fakePng.length, updated.avatarPng().length);
        assertEquals("attila@rewear.hu", session.requireCurrentUser().email());

        // Verify separate Admin account has its own non-SuperAdmin identity and can change handle safely
        authService.logout();
        AuthService.AuthResult adminLogin = authService.login("admin", "admin123".toCharArray());
        assertTrue(adminLogin.isSuccess());
        assertEquals(RoleCode.ADMIN, adminLogin.user().roleCode());
        assertEquals("Szabó", adminLogin.user().safeLastName());
        assertEquals("Gábor", adminLogin.user().safeFirstName());
        assertEquals("Szabó Gábor", adminLogin.user().displayName());
        assertFalse(session.isSuperAdmin());
        assertTrue(session.isAdmin());

        var renamedAdmin = authService.updateOwnProfile(
                adminLogin.user().id(),
                "@gabor.szabo",
                "Szabó",
                "Gábor",
                "gabor@rewear.hu",
                null,
                null,
                null
        );
        assertEquals("gabor.szabo", renamedAdmin.username());
        assertEquals("gabor.szabo", session.requireCurrentUser().username());
    }
}
