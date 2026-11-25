package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.DemoDataSeeder;
import hu.due.rewear.config.StarterData;
import hu.due.rewear.dao.BankLedgerDao;
import hu.due.rewear.dao.DictionaryDao;
import hu.due.rewear.dao.RoleDao;
import hu.due.rewear.dao.SettingsDao;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * First-run setup: decides whether an installation becomes the demo workspace or a real company, and
 * records which one it is.
 * <p>
 * Nothing here runs on an installation that already has users. A fresh database starts empty (roles and
 * settings only) and waits for exactly one of two explicit choices:
 * <ul>
 *   <li>{@link #startDemoWorkspace()} loads the sample warehouse, which comes with publicly known demo
 *       logins, and</li>
 *   <li>{@link #createCompanyWorkspace(CompanySetup)} creates the owner's own super-administrator account,
 *       optionally the standard clothing catalogues and a starter shelf layout, and nothing else.</li>
 * </ul>
 * No other code path creates users, partners, documents or default-password accounts.
 */
public final class OnboardingService {

    private static final Logger LOG = LoggerFactory.getLogger(OnboardingService.class);

    public static final String KEY_MODE = "workspace.mode";

    public enum Mode { DEMO, COMPANY }

    /** Everything the owner supplies on the setup screen. The password array is wiped after use. */
    public record CompanySetup(String companyName, String lastName, String firstName, String username,
                               String email, char[] password, boolean standardCatalogs, boolean starterLocations) { }

    /** Usernames and passwords that ship with the demo workspace; they are public, so never acceptable for a company. */
    private static final Map<String, String> DEMO_LOGINS = Map.of(
            "superadmin", "superadmin123",
            "admin", "admin123",
            "manager", "manager123",
            "operator", "operator123",
            "forklift", "forklift123");

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "12345678", "123456789", "1234567890", "qwertyui", "qwerty123", "letmein123",
            "iloveyou", "welcome1", "abc12345", "jelszo123");

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,32}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    static final int MIN_PASSWORD_LENGTH = 8;
    /** BCrypt silently ignores everything past 72 bytes, so a longer password would be a false promise. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final DatabaseManager db;
    private final UserDao userDao;
    private final RoleDao roleDao;
    private final SettingsDao settingsDao;
    private final DictionaryDao dictionaryDao;
    private final WarehouseLocationDao locationDao;

    public OnboardingService(DatabaseManager db) {
        this.db = db;
        this.userDao = new UserDao(db);
        this.roleDao = new RoleDao(db);
        this.settingsDao = new SettingsDao(db);
        this.dictionaryDao = new DictionaryDao(db);
        this.locationDao = new WarehouseLocationDao(db);
    }

    /** True while the installation has no users at all, i.e. before either choice was made. */
    public boolean needsSetup() {
        return userDao.count() == 0;
    }

    public Optional<Mode> mode() {
        return settingsDao.get(KEY_MODE).flatMap(OnboardingService::parseMode);
    }

    /** Whether the public demo logins are meant to exist here; the login screen only offers them then. */
    public boolean isDemoWorkspace() {
        return mode().filter(m -> m == Mode.DEMO).isPresent();
    }

    /**
     * Databases created before this setup existed carry no mode. They are classified once: a database
     * where any demo account still has its published password is a demo workspace, anything else is a
     * company's own data. Recorded so the check (a few password hashes) never runs again.
     */
    public void recordModeOfExistingDatabase() {
        if (mode().isPresent() || userDao.count() == 0) {
            return;
        }
        boolean demo = DEMO_LOGINS.entrySet().stream().anyMatch(e -> userDao.findByUsername(e.getKey())
                .map(u -> BCrypt.checkpw(e.getValue(), u.passwordHash())).orElse(false));
        settingsDao.set(KEY_MODE, (demo ? Mode.DEMO : Mode.COMPANY).name());
        LOG.info("Existing database classified as a {} workspace", demo ? "demo" : "company");
    }

    /** Loads the sample warehouse with its demo accounts. Only possible on an installation with no users. */
    public void startDemoWorkspace() {
        requireFreshInstallation();
        DemoDataSeeder seeder = new DemoDataSeeder(db);
        seeder.seedIfEmpty();
        new BankLedgerDao(db).seedDemoAccounts();
        settingsDao.set(KEY_MODE, Mode.DEMO.name());
        LOG.info("Demo workspace created");
    }

    /**
     * Creates a real company workspace: the owner's account plus whatever starter reference data was
     * chosen. The owner's account is the only user, and holds the super-administrator role.
     *
     * @throws IllegalArgumentException if any field is unacceptable; nothing is changed in that case
     * @throws IllegalStateException    if the installation already has users
     */
    public User createCompanyWorkspace(CompanySetup setup) {
        char[] password = setup.password() == null ? new char[0] : setup.password();
        try {
            Checked checked = check(setup, password);
            requireFreshInstallation();
            String hash = BCrypt.hashpw(new String(password), BCrypt.gensalt(10));
            // Read before the write transaction opens: SQLite allows one writer, and a second
            // connection used inside it would have to wait on its own caller.
            boolean addCatalogs = setup.standardCatalogs() && StarterData.catalogsAreEmpty(dictionaryDao);
            boolean addLocations = setup.starterLocations() && locationDao.findAll(false).isEmpty();

            settingsDao.set(SettingsDao.KEY_COMPANY_NAME, checked.company());
            db.runInTransaction(conn -> {
                try (PreparedStatement count = conn.prepareStatement("SELECT COUNT(*) FROM users");
                     var rs = count.executeQuery()) {
                    if (rs.next() && rs.getLong(1) > 0) {
                        throw new IllegalStateException("This installation is already set up.");
                    }
                }
                long roleId = roleDao.ensure(conn, RoleCode.SUPER_ADMIN, "Super Administrator");
                long userId = userDao.insert(conn, checked.username(), hash, checked.fullName(), roleId, true,
                        BusinessClock.now());
                if (!checked.email().isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET email = ? WHERE id = ?")) {
                        ps.setString(1, checked.email());
                        ps.setLong(2, userId);
                        ps.executeUpdate();
                    }
                }
                if (addCatalogs) {
                    StarterData.insertCatalogs(conn, dictionaryDao);
                }
                if (addLocations) {
                    StarterData.insertLocations(conn, locationDao);
                }
            });
            settingsDao.set(KEY_MODE, Mode.COMPANY.name());
            LOG.info("Company workspace created for '{}' (catalogues: {}, locations: {})",
                    checked.company(), addCatalogs, addLocations);
            return userDao.findByUsername(checked.username()).orElseThrow();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Whether this password is one of the published demo passwords or a trivially guessed one. */
    public static boolean isForbiddenPassword(String password) {
        String lower = password.toLowerCase(Locale.ROOT);
        return DEMO_LOGINS.containsValue(lower) || COMMON_PASSWORDS.contains(lower);
    }

    // ---- validation -------------------------------------------------------------------------

    private record Checked(String company, String fullName, String username, String email) { }

    private Checked check(CompanySetup s, char[] password) {
        String company = trim(s.companyName());
        if (company.isEmpty()) {
            throw new IllegalArgumentException("Company name is required.");
        }
        if (company.length() > 120) {
            throw new IllegalArgumentException("Company name is too long (120 characters at most).");
        }
        String fullName = (trim(s.lastName()) + " " + trim(s.firstName())).trim();
        if (fullName.isEmpty()) {
            throw new IllegalArgumentException("Enter the administrator's name.");
        }
        String username = trim(s.username());
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Username must be 3-32 characters: letters, digits, dot, dash or underscore.");
        }
        String email = trim(s.email());
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("That e-mail address does not look valid.");
        }
        String plain = new String(password);
        if (plain.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters long.");
        }
        if (plain.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("Password is too long (72 bytes at most).");
        }
        if (isForbiddenPassword(plain)) {
            throw new IllegalArgumentException("That password is too common or is a published demo password.");
        }
        if (plain.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("The password must not contain the username.");
        }
        return new Checked(company, fullName, username, email);
    }

    private void requireFreshInstallation() {
        if (!needsSetup()) {
            throw new IllegalStateException("This installation is already set up.");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static Optional<Mode> parseMode(String raw) {
        try {
            return Optional.of(Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
