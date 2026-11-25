package hu.due.rewear.config;

import hu.due.rewear.dao.ChatDao;
import hu.due.rewear.dao.BankLedgerDao;
import hu.due.rewear.dao.CustomerDao;
import hu.due.rewear.dao.DictionaryDao;
import hu.due.rewear.dao.EmailDao;
import hu.due.rewear.dao.FinanceConfigDao;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.InvoiceDao;
import hu.due.rewear.dao.NotificationDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
import hu.due.rewear.dao.RoleDao;
import hu.due.rewear.dao.RoleModulePermissionDao;
import hu.due.rewear.dao.SettingsDao;
import hu.due.rewear.dao.SmartFolderDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.dao.TodoDao;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.service.AuthService;
import hu.due.rewear.service.BackupService;
import hu.due.rewear.service.ChatService;
import hu.due.rewear.service.CsvExportService;
import hu.due.rewear.service.DeliveryNotePdfExporter;
import hu.due.rewear.service.EmailService;
import hu.due.rewear.service.MailAttachmentVault;
import hu.due.rewear.service.FinancialReportPdfExporter;
import hu.due.rewear.service.InventoryService;
import hu.due.rewear.service.InvoicePdfExporter;
import hu.due.rewear.service.InvoiceArchiveService;
import hu.due.rewear.service.InvoiceService;
import hu.due.rewear.service.LabelPdfExporter;
import hu.due.rewear.service.NotificationService;
import hu.due.rewear.service.OrderService;
import hu.due.rewear.service.PayrollService;
import hu.due.rewear.service.PartnerStatementService;
import hu.due.rewear.service.ProductCodeService;
import hu.due.rewear.service.QrCodeService;
import hu.due.rewear.service.ReceiptVaultService;
import hu.due.rewear.service.RoleModuleService;
import hu.due.rewear.service.ScannerService;
import hu.due.rewear.service.SessionContext;
import hu.due.rewear.service.SortingService;
import hu.due.rewear.service.TicketLabelPainter;
import hu.due.rewear.service.TodoService;
import hu.due.rewear.service.TransportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Central dependency container wiring database, DAOs, services, i18n, and user preferences.
 */
public final class AppContext {

    public enum StartupStep { STORAGE, DATABASE, INITIAL_DATA, SERVICES }

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
    private final SmartFolderDao smartFolderDao;
    private final FinanceConfigDao financeConfigDao;
    private final BankLedgerDao bankLedgerDao;
    private final EmailDao emailDao;
    private final ChatDao chatDao;
    private final NotificationDao notificationDao;
    private final TodoDao todoDao;
    private final RoleModulePermissionDao roleModulePermissionDao;

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
    private final InvoiceArchiveService invoiceArchiveService;
    private final FinancialReportPdfExporter financialReportPdfExporter;
    private final PartnerStatementService partnerStatementService;
    private final ReceiptVaultService receiptVaultService;
    private final EmailService emailService;
    private final MailAttachmentVault mailAttachmentVault;
    private final ChatService chatService;
    private final TransportService transportService;
    private final NotificationService notificationService;
    private final TodoService todoService;
    private final SortingService sortingService;
    private final PayrollService payrollService;
    private final RoleModuleService roleModuleService;
    private final BackupService backupService;

    public AppContext() {
        this(new DatabaseManager(), new AppPreferences(), I18nManager.getInstance());
    }

    public AppContext(DatabaseManager databaseManager, AppPreferences preferences, I18nManager i18n) {
        this(databaseManager, preferences, i18n, step -> { });
    }

    public AppContext(DatabaseManager databaseManager, AppPreferences preferences, I18nManager i18n,
                      Consumer<StartupStep> progress) {
        this.databaseManager = databaseManager;
        this.preferences = preferences;
        this.i18n = i18n;
        this.session = new SessionContext();
        this.productCodeService = new ProductCodeService();

        progress.accept(StartupStep.STORAGE);
        Path dataDir = databaseManager.getDatabasePath().getParent();
        Path receiptsDir = dataDir.resolve("receipts");
        Path invoicesDir = dataDir.resolve("invoices");
        Path mailAttachmentsDir = dataDir.resolve("mail-attachments");
        try {
            Files.createDirectories(receiptsDir);
            Files.createDirectories(invoicesDir);
            Files.createDirectories(mailAttachmentsDir);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not prepare invoice, receipt and mail attachment storage in " + dataDir, ex);
        }

        progress.accept(StartupStep.DATABASE);
        new SchemaInitializer(databaseManager).initialize();
        progress.accept(StartupStep.INITIAL_DATA);
        new DemoDataSeeder(databaseManager).seedIfEmpty();

        progress.accept(StartupStep.SERVICES);
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
        this.smartFolderDao = new SmartFolderDao(databaseManager);
        this.financeConfigDao = new FinanceConfigDao(databaseManager);
        this.bankLedgerDao = new BankLedgerDao(databaseManager);
        this.bankLedgerDao.seedDemoAccounts();
        this.emailDao = new EmailDao(databaseManager);
        this.chatDao = new ChatDao(databaseManager);
        this.notificationDao = new NotificationDao(databaseManager);
        this.todoDao = new TodoDao(databaseManager);
        this.roleModulePermissionDao = new RoleModulePermissionDao(databaseManager);
        this.roleModuleService = new RoleModuleService(roleModulePermissionDao, session);
        this.session.setRoleModuleService(roleModuleService);

        this.authService = new AuthService(userDao, session);
        this.qrCodeService = new QrCodeService();
        this.inventoryService = new InventoryService(databaseManager, packageDao, movementDao, productCodeService);
        this.orderService = new OrderService(databaseManager, orderDao, orderItemDao, packageDao, movementDao);
        this.scannerService = new ScannerService(productCodeService, qrCodeService, packageDao, movementDao);
        this.ticketLabelPainter = new TicketLabelPainter(qrCodeService, i18n);
        this.labelPdfExporter = new LabelPdfExporter(ticketLabelPainter);
        this.csvExportService = new CsvExportService(i18n);
        this.deliveryNotePdfExporter = new DeliveryNotePdfExporter(i18n, qrCodeService);
        this.invoicePdfExporter = new InvoicePdfExporter(i18n, qrCodeService, settingsDao::getCompanyProfile);
        this.invoiceArchiveService = new InvoiceArchiveService(invoicesDir, invoicePdfExporter);
        this.invoiceService = new InvoiceService(databaseManager, invoiceDao, orderDao, orderItemDao,
                customerDao, financeConfigDao, invoiceArchiveService);
        this.financialReportPdfExporter = new FinancialReportPdfExporter(i18n, settingsDao::getCompanyProfile);
        this.partnerStatementService = new PartnerStatementService(i18n, settingsDao::getCompanyProfile);
        this.receiptVaultService = new ReceiptVaultService(receiptsDir, invoiceDao);
        this.mailAttachmentVault = new MailAttachmentVault(mailAttachmentsDir);
        this.emailService = new EmailService(databaseManager, emailDao, settingsDao, userDao, session,
                mailAttachmentVault);
        this.chatService = new ChatService(databaseManager, chatDao, userDao);
        this.transportService = new TransportService(databaseManager, packageDao, locationDao, inventoryService);
        this.notificationService = new NotificationService(databaseManager, notificationDao, userDao);
        this.sortingService = new SortingService(databaseManager, session, dictionaryDao, locationDao, inventoryService, packageDao, notificationService, i18n);
        this.payrollService = new PayrollService(databaseManager, session);
        this.payrollService.initializeDepartments();
        this.todoService = new TodoService(todoDao, transportService, packageDao, locationDao, orderDao, invoiceDao);
        this.inventoryService.setNotificationService(notificationService);
        this.orderService.setNotificationService(notificationService);
        this.transportService.setNotificationService(notificationService);
        this.backupService = new BackupService(databaseManager);

        invoiceService.syncOverdueStatuses();
        initLocalizationFromPreferences();
    }

    private void initLocalizationFromPreferences() {
        String extPath = preferences.getExternalTranslationsPath();
        if (extPath != null && !extPath.isBlank()) {
            try {
                i18n.loadFromFile(Path.of(extPath));
            } catch (IOException | InvalidPathException e) {
                LOG.warn("Failed to load external translations CSV {}: {}", extPath, e.getMessage());
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

    public SmartFolderDao getSmartFolderDao() {
        return smartFolderDao;
    }

    public FinanceConfigDao getFinanceConfigDao() {
        return financeConfigDao;
    }

    public BankLedgerDao getBankLedgerDao() {
        return bankLedgerDao;
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

    public InvoiceArchiveService getInvoiceArchiveService() {
        return invoiceArchiveService;
    }

    public EmailDao getEmailDao() {
        return emailDao;
    }

    public ChatDao getChatDao() {
        return chatDao;
    }

    public MailAttachmentVault getMailAttachmentVault() {
        return mailAttachmentVault;
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

    public PayrollService getPayrollService() { return payrollService; }

    public SortingService getSortingService() { return sortingService; }

    public NotificationService getNotificationService() {
        return notificationService;
    }

    public TodoService getTodoService() {
        return todoService;
    }

    public RoleModuleService getRoleModuleService() {
        return roleModuleService;
    }

    public BackupService getBackupService() {
        return backupService;
    }
}
