package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppContext.InitialData;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.DemoDataSeeder;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import hu.due.rewear.service.AuthService.AuthStatus;
import hu.due.rewear.service.OnboardingService.CompanySetup;
import hu.due.rewear.service.OnboardingService.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mindrot.jbcrypt.BCrypt;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fresh installation must start empty and wait for an explicit choice, a real company must never
 * receive demo data or a default-password account, and the demo workspace must stay available.
 */
class OnboardingServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseManager db() {
        return new DatabaseManager(tempDir.resolve("onboarding.db"));
    }

    private AppContext open(InitialData initialData) {
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        return new AppContext(db(), prefs, i18n, step -> { }, initialData);
    }

    private static CompanySetup setup(String company, String username, String password, boolean catalogs, boolean locations) {
        return new CompanySetup(company, "Kiss", "Béla", username, "bela@example.com",
                password.toCharArray(), catalogs, locations);
    }

    private static CompanySetup valid() {
        return setup("Példa Textil Kft.", "owner", "correct-horse-9", true, false);
    }

    /** Everything the demo workspace would have created, as a single comparable list. */
    private static List<String> demoFootprint(AppContext ctx) {
        return List.of(
                "users=" + ctx.getUserDao().count(),
                "customers=" + ctx.getCustomerDao().findAll(false).size(),
                "orders=" + ctx.getOrderDao().findAll().size(),
                "invoices=" + ctx.getInvoiceDao().count(),
                "mail=" + ctx.getEmailDao().countAll(),
                "chatRooms=" + ctx.getChatDao().countRooms(),
                "bankAccounts=" + ctx.getBankLedgerDao().accounts().size());
    }

    private static final List<String> NOTHING = List.of(
            "users=0", "customers=0", "orders=0", "invoices=0", "mail=0", "chatRooms=0", "bankAccounts=0");

    @Test
    void aFreshInstallationStaysEmptyUntilTheOwnerChooses() {
        AppContext ctx = open(InitialData.AWAIT_SETUP);

        assertTrue(ctx.getOnboardingService().needsSetup());
        assertEquals(NOTHING, demoFootprint(ctx));
        assertTrue(ctx.getOnboardingService().mode().isEmpty());
        for (RoleCode role : RoleCode.values()) {
            assertTrue(ctx.getRoleDao().findByCode(role).isPresent(), "The schema must provide role " + role);
        }
        assertEquals(AuthStatus.INVALID_CREDENTIALS,
                ctx.getAuthService().login("superadmin", "superadmin123".toCharArray()).status());

        // Opening it again changes nothing either: nothing seeds in the background.
        assertEquals(NOTHING, demoFootprint(open(InitialData.AWAIT_SETUP)));
    }

    @Test
    void companyWorkspaceHasOnlyTheOwnersAccountAndTheChosenStarterData() {
        AppContext ctx = open(InitialData.AWAIT_SETUP);

        User owner = ctx.getOnboardingService().createCompanyWorkspace(setup("Példa Textil Kft.", "owner", "correct-horse-9", true, true));

        assertEquals(RoleCode.SUPER_ADMIN, owner.roleCode());
        assertEquals("Kiss Béla", owner.fullName());
        assertEquals("bela@example.com", owner.email());
        assertFalse(owner.passwordHash().contains("correct-horse-9"));
        assertTrue(BCrypt.checkpw("correct-horse-9", owner.passwordHash()));
        assertEquals(AuthStatus.SUCCESS, ctx.getAuthService().login("owner", "correct-horse-9".toCharArray()).status());

        assertFalse(ctx.getOnboardingService().needsSetup());
        assertEquals(Mode.COMPANY, ctx.getOnboardingService().mode().orElseThrow());
        assertFalse(ctx.getOnboardingService().isDemoWorkspace());
        assertEquals("Példa Textil Kft.", ctx.getSettingsDao().getCompanyProfile().name());
        assertFalse(ctx.getDictionaryDao().findGarmentTypes(false).isEmpty(), "Standard catalogues were requested");
        assertFalse(ctx.getDictionaryDao().findQualityGrades(false).isEmpty());
        assertFalse(ctx.getLocationDao().findAll(false).isEmpty(), "Starter locations were requested");

        List<String> footprint = demoFootprint(ctx);
        assertEquals(List.of("users=1", "customers=0", "orders=0", "invoices=0", "mail=0", "chatRooms=0", "bankAccounts=0"),
                footprint, "A company starts with its owner and nothing else");
    }

    @Test
    void starterDataIsOptional() {
        AppContext ctx = open(InitialData.AWAIT_SETUP);

        ctx.getOnboardingService().createCompanyWorkspace(setup("Minimal Kft.", "owner", "correct-horse-9", false, false));

        assertTrue(ctx.getDictionaryDao().findGarmentTypes(false).isEmpty());
        assertTrue(ctx.getDictionaryDao().findTargetGroups(false).isEmpty());
        assertTrue(ctx.getLocationDao().findAll(false).isEmpty());
    }

    /**
     * The regression this feature exists for: every start used to top up demo invoices, mail and chat
     * wherever those tables were empty, and recreate default-password accounts that were missing.
     */
    @Test
    void aCompanyWorkspaceNeverGainsDemoDataOrDefaultAccountsOnLaterStarts() {
        AppContext first = open(InitialData.AWAIT_SETUP);
        first.getOnboardingService().createCompanyWorkspace(valid());
        List<String> before = demoFootprint(first);

        // Reopened the way the application does it, and the way old tests/scripts did.
        for (InitialData how : InitialData.values()) {
            AppContext again = open(how);
            assertEquals(before, demoFootprint(again), "Reopening with " + how + " must add nothing");
            for (String demo : List.of("superadmin", "admin", "manager", "operator", "forklift", "sorter01", "helper01", "forklift2")) {
                assertTrue(again.getUserDao().findByUsername(demo).isEmpty(),
                        "'" + demo + "' must not appear in a company workspace");
            }
            assertEquals(Mode.COMPANY, again.getOnboardingService().mode().orElseThrow());
        }
    }

    @Test
    void weakOrMalformedInputIsRefusedAndChangesNothing() {
        AppContext ctx = open(InitialData.AWAIT_SETUP);
        OnboardingService service = ctx.getOnboardingService();
        String longPassword = "x".repeat(40) + "é".repeat(20);                       // 80 bytes in UTF-8

        List<CompanySetup> bad = List.of(
                setup("", "owner", "correct-horse-9", true, true),
                setup("   ", "owner", "correct-horse-9", true, true),
                setup("K".repeat(121), "owner", "correct-horse-9", true, true),
                new CompanySetup("Co", " ", "", "owner", "", "correct-horse-9".toCharArray(), false, false),
                setup("Co", "ab", "correct-horse-9", true, true),
                setup("Co", "has space", "correct-horse-9", true, true),
                setup("Co", "x".repeat(33), "correct-horse-9", true, true),
                new CompanySetup("Co", "Kiss", "Béla", "owner", "not-an-email", "correct-horse-9".toCharArray(), false, false),
                setup("Co", "owner", "short7!", true, true),
                setup("Co", "owner", "admin123", true, true),
                setup("Co", "owner", "SuperAdmin123", true, true),
                setup("Co", "owner", "password", true, true),
                setup("Co", "owner", "my-OWNER-secret", true, true),
                setup("Co", "owner", longPassword, true, true));

        for (CompanySetup candidate : bad) {
            assertThrows(IllegalArgumentException.class, () -> service.createCompanyWorkspace(candidate),
                    String.valueOf(candidate.companyName()).length() + "/" + candidate.username());
        }
        assertTrue(service.needsSetup(), "A refused setup must leave the installation unconfigured");
        assertEquals(NOTHING, demoFootprint(ctx));
        assertTrue(ctx.getSettingsDao().get("company.name").isEmpty(), "No company name may be stored by a refused setup");
    }

    @Test
    void setupCanOnlyHappenOnce() {
        AppContext ctx = open(InitialData.AWAIT_SETUP);
        OnboardingService service = ctx.getOnboardingService();
        service.createCompanyWorkspace(valid());

        assertThrows(IllegalStateException.class,
                () -> service.createCompanyWorkspace(setup("Other Kft.", "intruder", "another-secret-1", false, false)));
        assertThrows(IllegalStateException.class, service::startDemoWorkspace);
        assertEquals(1, ctx.getUserDao().count());
        assertTrue(ctx.getUserDao().findByUsername("intruder").isEmpty());
    }

    @Test
    void demoWorkspaceIsExplicitAndKeepsItsPublishedLogins() {
        AppContext ctx = open(InitialData.AWAIT_SETUP);

        ctx.getOnboardingService().startDemoWorkspace();

        assertFalse(ctx.getOnboardingService().needsSetup());
        assertTrue(ctx.getOnboardingService().isDemoWorkspace());
        assertEquals(AuthStatus.SUCCESS, ctx.getAuthService().login("manager", "manager123".toCharArray()).status());
        assertTrue(ctx.getInvoiceDao().count() > 0);
        assertTrue(ctx.getEmailDao().countAll() > 0);
        assertFalse(ctx.getBankLedgerDao().accounts().isEmpty(), "Demo bank accounts belong to the demo workspace only");
        assertThrows(IllegalStateException.class, () -> ctx.getOnboardingService().startDemoWorkspace());
    }

    @Test
    void databasesWithoutAModeAreClassifiedByTheirAccountsOnce() throws Exception {
        // An older demo database: seeded the old way, no mode recorded.
        DatabaseManager demoDb = new DatabaseManager(tempDir.resolve("old-demo.db"));
        new SchemaInitializer(demoDb).initialize();
        new DemoDataSeeder(demoDb).seedIfEmpty();
        AppContext demo = new AppContext(demoDb, new AppPreferences(tempDir.resolve("a.properties")),
                I18nManager.getInstance(), step -> { }, InitialData.AWAIT_SETUP);
        assertEquals(Mode.DEMO, demo.getOnboardingService().mode().orElseThrow());

        // An older real database: its owner changed the demo passwords.
        DatabaseManager realDb = new DatabaseManager(tempDir.resolve("old-real.db"));
        new SchemaInitializer(realDb).initialize();
        new DemoDataSeeder(realDb).seedIfEmpty();
        AppContext firstOpen = new AppContext(realDb, new AppPreferences(tempDir.resolve("b.properties")),
                I18nManager.getInstance(), step -> { }, InitialData.AWAIT_SETUP);
        for (User u : firstOpen.getUserDao().findAll()) {
            firstOpen.getUserDao().updatePasswordHash(u.id(), BCrypt.hashpw("a-private-password-" + u.id(), BCrypt.gensalt(4)));
        }
        // Forget the classification made on the first open, as if this were a pre-upgrade file.
        firstOpen.getSettingsDao().set(OnboardingService.KEY_MODE, "");
        AppContext reopened = new AppContext(realDb, new AppPreferences(tempDir.resolve("b.properties")),
                I18nManager.getInstance(), step -> { }, InitialData.AWAIT_SETUP);
        assertEquals(Mode.COMPANY, reopened.getOnboardingService().mode().orElseThrow());
        assertFalse(reopened.getOnboardingService().isDemoWorkspace());
        assertNotEquals(Mode.DEMO, reopened.getOnboardingService().mode().orElseThrow());
    }

    @Test
    void anExistingWorkspaceIsNotToppedUpWithSampleRecords() throws Exception {
        // Demo users exist, but the owner has since removed every invoice, mail and chat room.
        AppContext ctx = open(InitialData.DEMO_IF_EMPTY);
        try (var conn = ctx.getDatabaseManager().getConnection(); var st = conn.createStatement()) {
            st.execute("DELETE FROM invoice_items");
            st.execute("DELETE FROM invoices");
            st.execute("DELETE FROM emails");
        }
        assertEquals(0, ctx.getInvoiceDao().count());

        AppContext reopened = open(InitialData.AWAIT_SETUP);

        assertEquals(0, reopened.getInvoiceDao().count(), "Demo invoices must not reappear on startup");
        assertEquals(0, reopened.getEmailDao().countAll(), "Demo mail must not reappear on startup");
    }
}
