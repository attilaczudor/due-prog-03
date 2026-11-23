package hu.due.rewear.config;

import hu.due.rewear.dao.ChatDao;
import hu.due.rewear.dao.CustomerDao;
import hu.due.rewear.dao.DictionaryDao;
import hu.due.rewear.dao.EmailDao;
import hu.due.rewear.dao.FinanceConfigDao;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.InvoiceDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
import hu.due.rewear.dao.RoleDao;
import hu.due.rewear.dao.SettingsDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.service.AuthService;
import hu.due.rewear.service.BackupService;
import hu.due.rewear.service.ChatService;
import hu.due.rewear.service.CsvExportService;
import hu.due.rewear.service.DeliveryNotePdfExporter;
import hu.due.rewear.service.EmailService;
import hu.due.rewear.service.FinancialReportPdfExporter;
import hu.due.rewear.service.InventoryService;
import hu.due.rewear.service.InvoicePdfExporter;
import hu.due.rewear.service.InvoiceService;
import hu.due.rewear.service.LabelPdfExporter;
import hu.due.rewear.service.OrderService;
import hu.due.rewear.service.PartnerStatementService;
import hu.due.rewear.service.ProductCodeService;
import hu.due.rewear.service.QrCodeService;
import hu.due.rewear.service.ReceiptVaultService;
import hu.due.rewear.service.ScannerService;
import hu.due.rewear.service.SessionContext;
import hu.due.rewear.service.TicketLabelPainter;
import hu.due.rewear.service.TransportService;
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
    private final InvoiceDao invoiceDao;
    private final SettingsDao settingsDao;
    private final FinanceConfigDao financeConfigDao;
    private final EmailDao emailDao;
    private final ChatDao chatDao;

    private final AuthService authService;
    private final QrCodeService qrCodeService;
    private final InventoryService inventoryService;
    private final OrderService orderService;
    private final InvoiceService invoiceService;
    private final ScannerService scannerService;
    private final TicketLabelPainter ticketLabelPainter;
    private final LabelPdfExporter labelPdfExporter;
    private final CsvExportService csvExportService;
    private final DeliveryNotePdfExporter deliveryNotePdfExporter;
    private final InvoicePdfExporter invoicePdfExporter;
    private final FinancialReportPdfExporter financialReportPdfExporter;
    private final PartnerStatementService partnerStatementService;
    private final ReceiptVaultService receiptVaultService;
    private final EmailService emailService;
    private final ChatService chatService;
    private final TransportService transportService;
    private final BackupService backupService;

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
        this.invoiceDao = new InvoiceDao(databaseManager);
        this.settingsDao = new SettingsDao(databaseManager);
        this.financeConfigDao = new FinanceConfigDao(databaseManager);
        this.emailDao = new EmailDao(databaseManager);
        this.chatDao = new ChatDao(databaseManager);

        this.authService = new AuthService(userDao, session);
        this.qrCodeService = new QrCodeService();
        this.inventoryService = new InventoryService(databaseManager, packageDao, movementDao, productCodeService);
        this.orderService = new OrderService(databaseManager, orderDao, orderItemDao, packageDao, movementDao);
        this.invoiceService = new InvoiceService(databaseManager, invoiceDao, orderDao, orderItemDao, customerDao, financeConfigDao);
        this.scannerService = new ScannerService(productCodeService, qrCodeService, packageDao, movementDao);
        this.ticketLabelPainter = new TicketLabelPainter(qrCodeService, i18n);
        this.labelPdfExporter = new LabelPdfExporter(ticketLabelPainter);
        this.csvExportService = new CsvExportService(i18n);
        this.deliveryNotePdfExporter = new DeliveryNotePdfExporter(i18n, qrCodeService);
        this.invoicePdfExporter = new InvoicePdfExporter(i18n, qrCodeService, settingsDao::getCompanyProfile);
        this.financialReportPdfExporter = new FinancialReportPdfExporter(i18n, settingsDao::getCompanyProfile);
        this.partnerStatementService = new PartnerStatementService(i18n, settingsDao::getCompanyProfile);
        Path receiptsDir = databaseManager.getDatabasePath().getParent() != null
                ? databaseManager.getDatabasePath().getParent().resolve("receipts")
                : AppPaths.defaultReceiptsDir();
        this.receiptVaultService = new ReceiptVaultService(receiptsDir, invoiceDao);
        this.emailService = new EmailService(databaseManager, emailDao, settingsDao, userDao);
        this.chatService = new ChatService(databaseManager, chatDao, userDao);
        this.transportService = new TransportService(databaseManager, packageDao, locationDao, inventoryService);
        this.backupService = new BackupService(databaseManager);

        invoiceService.syncOverdueStatuses();
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

    public InvoiceDao getInvoiceDao() {
        return invoiceDao;
    }

    public SettingsDao getSettingsDao() {
        return settingsDao;
    }

    public FinanceConfigDao getFinanceConfigDao() {
        return financeConfigDao;
    }

    public AuthService getAuthService() {
        return authService;
    }

    public QrCodeService getQrCodeService() {
        return qrCodeService;
    }

    public InventoryService getInventoryService() {
        return inventoryService;
    }

    public OrderService getOrderService() {
        return orderService;
    }

    public InvoiceService getInvoiceService() {
        return invoiceService;
    }

    public ScannerService getScannerService() {
        return scannerService;
    }

    public TicketLabelPainter getTicketLabelPainter() {
        return ticketLabelPainter;
    }

    public LabelPdfExporter getLabelPdfExporter() {
        return labelPdfExporter;
    }

    public CsvExportService getCsvExportService() {
        return csvExportService;
    }

    public DeliveryNotePdfExporter getDeliveryNotePdfExporter() {
        return deliveryNotePdfExporter;
    }

    public InvoicePdfExporter getInvoicePdfExporter() {
        return invoicePdfExporter;
    }

    public FinancialReportPdfExporter getFinancialReportPdfExporter() {
        return financialReportPdfExporter;
    }

    public PartnerStatementService getPartnerStatementService() {
        return partnerStatementService;
    }

    public ReceiptVaultService getReceiptVaultService() {
        return receiptVaultService;
    }

    public EmailDao getEmailDao() {
        return emailDao;
    }

    public ChatDao getChatDao() {
        return chatDao;
    }

    public EmailService getEmailService() {
        return emailService;
    }

    public ChatService getChatService() {
        return chatService;
    }

    public TransportService getTransportService() {
        return transportService;
    }

    public BackupService getBackupService() {
        return backupService;
    }
}
