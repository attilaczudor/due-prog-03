package hu.due.rewear.config;

import hu.due.rewear.dao.CustomerDao;
import hu.due.rewear.dao.DictionaryDao;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
import hu.due.rewear.dao.RoleDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.service.AuthService;
import hu.due.rewear.service.ProductCodeService;
import hu.due.rewear.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Central dependency container wiring database, DAOs, services, i18n, and user preferences.
 */
public final class AppContext {

    private static final Logger LOG = LoggerFactory.getLogger(AppContext.class);

    private final DatabaseManager databaseManager;
    private final AppPreferences preferences;
    private final I18nManager i18n;
    private final SessionContext session;
    private final ProductCodeService productCodeService;

    private final RoleDao roleDao;
    private final UserDao userDao;
    private final DictionaryDao dictionaryDao;
    private final WarehouseLocationDao locationDao;
    private final InventoryPackageDao packageDao;
    private final StockMovementDao movementDao;
    private final CustomerDao customerDao;
    private final OrderDao orderDao;
    private final OrderItemDao orderItemDao;

    private final AuthService authService;

    public AppContext() {
        this(new DatabaseManager(), new AppPreferences(), I18nManager.getInstance());
    }

    public AppContext(DatabaseManager databaseManager, AppPreferences preferences, I18nManager i18n) {
        this.databaseManager = databaseManager;
        this.preferences = preferences;
        this.i18n = i18n;
        this.session = new SessionContext();
        this.productCodeService = new ProductCodeService();

        new SchemaInitializer(databaseManager).initialize();
        new DemoDataSeeder(databaseManager).seedIfEmpty();

        this.roleDao = new RoleDao(databaseManager);
        this.userDao = new UserDao(databaseManager);
        this.dictionaryDao = new DictionaryDao(databaseManager);
        this.locationDao = new WarehouseLocationDao(databaseManager);
        this.packageDao = new InventoryPackageDao(databaseManager);
        this.movementDao = new StockMovementDao(databaseManager);
        this.customerDao = new CustomerDao(databaseManager);
        this.orderDao = new OrderDao(databaseManager);
        this.orderItemDao = new OrderItemDao(databaseManager);

        this.authService = new AuthService(userDao, session);

        initLocalizationFromPreferences();
    }

    private void initLocalizationFromPreferences() {
        String extPath = preferences.getExternalTranslationsPath();
        if (extPath != null && !extPath.isBlank()) {
            Path p = Path.of(extPath);
            if (Files.exists(p)) {
                try {
                    i18n.loadFromFile(p);
                } catch (IOException e) {
                    LOG.warn("Failed to load external translations CSV {}: {}", p, e.getMessage());
                }
            }
        }
        String savedLang = preferences.getLanguage();
        if (i18n.getAvailableLanguages().contains(savedLang)) {
            i18n.setLanguage(savedLang);
        }
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public AppPreferences getPreferences() {
        return preferences;
    }

    public I18nManager getI18n() {
        return i18n;
    }

    public SessionContext getSession() {
        return session;
    }

    public ProductCodeService getProductCodeService() {
        return productCodeService;
    }

    public RoleDao getRoleDao() {
        return roleDao;
    }

    public UserDao getUserDao() {
        return userDao;
    }

    public DictionaryDao getDictionaryDao() {
        return dictionaryDao;
    }

    public WarehouseLocationDao getLocationDao() {
        return locationDao;
    }

    public InventoryPackageDao getPackageDao() {
        return packageDao;
    }

    public StockMovementDao getMovementDao() {
        return movementDao;
    }

    public CustomerDao getCustomerDao() {
        return customerDao;
    }

    public OrderDao getOrderDao() {
        return orderDao;
    }

    public OrderItemDao getOrderItemDao() {
        return orderItemDao;
    }

    public AuthService getAuthService() {
        return authService;
    }
}
