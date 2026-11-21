package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.i18n.LanguageChangeListener;
import hu.due.rewear.model.User;
import hu.due.rewear.service.SessionContext;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Main ReWear WMS application window styled in stark Brutalist Monochrome (Light & Dark),
 * with RBAC-filtered sidebar navigation, live CSV language switcher, theme toggle,
 * and modular CardLayout workspace.
 */
public final class MainFrame extends JFrame implements LanguageChangeListener, ThemeManager.ThemeChangeListener {

    public static final String VIEW_DASHBOARD = "dashboard";
    public static final String VIEW_INTAKE = "intake";
    public static final String VIEW_SCANNER = "scanner";
    public static final String VIEW_INVENTORY = "inventory";
    public static final String VIEW_LOCATIONS = "locations";
    public static final String VIEW_DICTIONARIES = "dictionaries";
    public static final String VIEW_CUSTOMERS = "customers";
    public static final String VIEW_ORDERS = "orders";
    public static final String VIEW_ADMIN = "admin";

    private final AppContext ctx;
    private final Runnable onLogout;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentHost = new JPanel(cardLayout);
    private final Map<String, NavigableView> views = new LinkedHashMap<>();
    private final Map<String, JToggleButton> navButtons = new LinkedHashMap<>();
    private final ButtonGroup navGroup = new ButtonGroup();

    private final JPanel sidebar = new JPanel(new BorderLayout());
    private final JPanel topBar = new JPanel(new BorderLayout(12, 0));
    private final JPanel statusBar = new JPanel(new BorderLayout());
    private final JLabel brandTagLabel = new JLabel("DUE-PROG-03");

    private final JLabel activeViewTitleLabel = new JLabel();
    private final JButton quickScanButton = new JButton();
    private final JComboBox<String> langCombo = new JComboBox<>();
    private final JButton themeToggleButton = new JButton();
    private final JLabel userBadgeLabel = new JLabel();
    private final JLabel roleBadgeLabel = new JLabel();
    private final JButton logoutButton = new JButton();
    private final JLabel statusBarLabel = new JLabel();

    private String currentViewKey;
    private boolean updatingLanguageCombo = false;

    public MainFrame(AppContext ctx, Runnable onLogout) {
        this.ctx = ctx;
        this.onLogout = onLogout;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1200, 750));
        setPreferredSize(new Dimension(1360, 840));

        buildUi();
        registerDefaultViews();
        populateLanguageCombo();
        applyTranslations();
        applyBrutalistChrome();

        ctx.getI18n().addListener(this);
        ThemeManager.addListener(this);
        navigateTo(VIEW_DASHBOARD);

        pack();
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout());

        // Left Brutalist Sidebar
        sidebar.setPreferredSize(new Dimension(245, 0));

        JPanel brandPanel = new JPanel();
        brandPanel.setLayout(new BoxLayout(brandPanel, BoxLayout.Y_AXIS));
        JPanel brandTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel brandTitle = new JLabel("REWEAR WMS");
        brandTitle.setFont(ThemeManager.monoFont(Font.BOLD, 16f));
        brandTop.add(brandTitle);
        brandTop.add(brandTagLabel);
        brandTop.setAlignmentX(LEFT_ALIGNMENT);

        brandPanel.add(brandTop);
        brandPanel.add(Box.createVerticalStrut(12));
        brandPanel.add(new JSeparator());
        brandPanel.add(Box.createVerticalStrut(10));
        sidebar.add(brandPanel, BorderLayout.NORTH);

        JPanel navList = new JPanel();
        navList.setLayout(new BoxLayout(navList, BoxLayout.Y_AXIS));
        SessionContext session = ctx.getSession();

        addNavButton(navList, VIEW_DASHBOARD, "nav.dashboard");
        addNavButton(navList, VIEW_INTAKE, "nav.intake");
        addNavButton(navList, VIEW_SCANNER, "nav.scanner");
        addNavButton(navList, VIEW_INVENTORY, "nav.inventory");

        if (session.canManageLocationsAndDictionaries()) {
            navList.add(Box.createVerticalStrut(8));
            navList.add(new JSeparator());
            navList.add(Box.createVerticalStrut(8));
            addNavButton(navList, VIEW_LOCATIONS, "nav.locations");
            addNavButton(navList, VIEW_DICTIONARIES, "nav.dictionaries");
        }
        if (session.canManageOrders()) {
            addNavButton(navList, VIEW_CUSTOMERS, "nav.customers");
            addNavButton(navList, VIEW_ORDERS, "nav.orders");
        }
        if (session.canManageUsersAndMaintenance()) {
            navList.add(Box.createVerticalStrut(8));
            navList.add(new JSeparator());
            navList.add(Box.createVerticalStrut(8));
            addNavButton(navList, VIEW_ADMIN, "nav.admin");
        }
        navList.add(Box.createVerticalGlue());
        sidebar.add(navList, BorderLayout.CENTER);
        root.add(sidebar, BorderLayout.WEST);

        // Right Workspace (Topbar + Content + Footer)
        JPanel rightArea = new JPanel(new BorderLayout());

        activeViewTitleLabel.setFont(ThemeManager.monoFont(Font.BOLD, 15.5f));
        topBar.add(activeViewTitleLabel, BorderLayout.WEST);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        quickScanButton.addActionListener(e -> navigateTo(VIEW_SCANNER));
        rightControls.add(quickScanButton);

        langCombo.addActionListener(e -> {
            if (!updatingLanguageCombo && langCombo.getSelectedItem() instanceof String code) {
                ctx.getI18n().setLanguage(code.toLowerCase(Locale.ROOT));
                ctx.getPreferences().setLanguage(code.toLowerCase(Locale.ROOT));
            }
        });
        rightControls.add(langCombo);

        themeToggleButton.addActionListener(e -> {
            boolean nextDark = !ctx.getPreferences().isDarkTheme();
            ctx.getPreferences().setDarkTheme(nextDark);
            ThemeManager.applyTheme(nextDark);
        });
        rightControls.add(themeToggleButton);

        User user = ctx.getSession().requireCurrentUser();
        userBadgeLabel.setText(user.fullName().toUpperCase(Locale.ROOT));
        userBadgeLabel.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        rightControls.add(userBadgeLabel);

        rightControls.add(roleBadgeLabel);

        logoutButton.addActionListener(e -> performLogout());
        rightControls.add(logoutButton);

        topBar.add(rightControls, BorderLayout.EAST);
        rightArea.add(topBar, BorderLayout.NORTH);

        rightArea.add(contentHost, BorderLayout.CENTER);

        statusBarLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 11f));
        statusBar.add(statusBarLabel, BorderLayout.WEST);
        rightArea.add(statusBar, BorderLayout.SOUTH);

        root.add(rightArea, BorderLayout.CENTER);
        setContentPane(root);
    }

    private void addNavButton(JPanel container, String viewKey, String i18nKey) {
        JToggleButton btn = new JToggleButton();
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        btn.setPreferredSize(new Dimension(215, 36));
        btn.putClientProperty("i18nKey", i18nKey);
        btn.addActionListener(e -> navigateTo(viewKey));
        navGroup.add(btn);
        navButtons.put(viewKey, btn);
        container.add(btn);
        container.add(Box.createVerticalStrut(5));
    }

    private void registerDefaultViews() {
        registerView(new PlaceholderView(VIEW_DASHBOARD, "nav.dashboard", ctx));
        registerView(new PackageIntakeView(ctx));
        registerView(new QrScannerView(ctx));
        registerView(new InventoryView(ctx));
        registerView(new LocationsView(ctx));
        registerView(new DictionariesView(ctx));
        registerView(new CustomersView(ctx));
        registerView(new OrdersView(ctx));
        registerView(new PlaceholderView(VIEW_ADMIN, "nav.admin", ctx));
    }

    public void registerView(NavigableView view) {
        views.put(view.getViewKey(), view);
        contentHost.add(view.getComponent(), view.getViewKey());
    }

    public void navigateTo(String viewKey) {
        NavigableView view = views.get(viewKey);
        if (view == null) {
            return;
        }
        currentViewKey = viewKey;
        JToggleButton btn = navButtons.get(viewKey);
        if (btn != null) {
            btn.setSelected(true);
        }
        updateNavButtonStyles();
        activeViewTitleLabel.setText(ctx.getI18n().t(view.getTitleI18nKey()).toUpperCase(Locale.ROOT));
        cardLayout.show(contentHost, viewKey);
        view.refreshData();
    }

    private void updateNavButtonStyles() {
        for (Map.Entry<String, JToggleButton> entry : navButtons.entrySet()) {
            JToggleButton btn = entry.getValue();
            boolean selected = entry.getKey().equals(currentViewKey);
            btn.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ThemeManager.border(), 1),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)
            ));
            if (selected) {
                btn.setBackground(ThemeManager.fg());
                btn.setForeground(ThemeManager.bg());
            } else {
                btn.setBackground(ThemeManager.bg());
                btn.setForeground(ThemeManager.fg());
            }
        }
    }

    private void applyBrutalistChrome() {
        sidebar.setBackground(ThemeManager.bg());
        sidebar.setBorder(BrutalistStyle.hairlineRightRule(16, 12));

        topBar.setBackground(ThemeManager.bg());
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, ThemeManager.border()),
                BorderFactory.createEmptyBorder(10, 18, 10, 18)
        ));

        statusBar.setBackground(ThemeManager.bg());
        statusBar.setBorder(BrutalistStyle.hairlineTopRule(5, 14));
        statusBarLabel.setForeground(ThemeManager.muted());

        BrutalistStyle.styleBoxedTag(brandTagLabel, false);
        BrutalistStyle.styleBoxedTag(roleBadgeLabel, true);
        BrutalistStyle.stylePrimaryButton(quickScanButton);
        updateNavButtonStyles();

        for (NavigableView view : views.values()) {
            view.applyTheme();
        }
    }

    public void setStatusMessage(String message) {
        statusBarLabel.setText(message.toUpperCase(Locale.ROOT)
                + "  //  DB: " + ctx.getDatabaseManager().getDatabasePath().getFileName());
    }

    public void populateLanguageCombo() {
        updatingLanguageCombo = true;
        try {
            langCombo.removeAllItems();
            for (String lang : ctx.getI18n().getAvailableLanguages()) {
                langCombo.addItem(lang.toUpperCase(Locale.ROOT));
            }
            langCombo.setSelectedItem(ctx.getI18n().getActiveLanguage().toUpperCase(Locale.ROOT));
        } finally {
            updatingLanguageCombo = false;
        }
    }

    private void performLogout() {
        ctx.getI18n().removeListener(this);
        ThemeManager.removeListener(this);
        ctx.getAuthService().logout();
        dispose();
        onLogout.run();
    }

    private void updateThemeButtonText() {
        boolean isDark = ctx.getPreferences().isDarkTheme();
        themeToggleButton.setText(ctx.getI18n().t(isDark ? "app.theme.light" : "app.theme.dark").toUpperCase(Locale.ROOT));
    }

    private void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        setTitle(i18n.t("app.title"));
        quickScanButton.setText("[ QR " + i18n.t("nav.scanner").toUpperCase(Locale.ROOT) + " ]");
        logoutButton.setText(i18n.t("app.logout").toUpperCase(Locale.ROOT));
        updateThemeButtonText();

        User user = ctx.getSession().requireCurrentUser();
        roleBadgeLabel.setText(i18n.t("role." + user.roleCode().name()).toUpperCase(Locale.ROOT));

        for (JToggleButton btn : navButtons.values()) {
            Object key = btn.getClientProperty("i18nKey");
            if (key instanceof String i18nKey) {
                btn.setText(i18n.t(i18nKey).toUpperCase(Locale.ROOT));
            }
        }
        for (NavigableView view : views.values()) {
            view.applyTranslations();
        }
        if (currentViewKey != null && views.containsKey(currentViewKey)) {
            activeViewTitleLabel.setText(i18n.t(views.get(currentViewKey).getTitleI18nKey()).toUpperCase(Locale.ROOT));
        }
        setStatusMessage(i18n.t("app.ready"));
    }

    @Override
    public void onLanguageChanged(String languageCode) {
        populateLanguageCombo();
        applyTranslations();
    }

    @Override
    public void onThemeChanged(boolean dark) {
        updateThemeButtonText();
        applyBrutalistChrome();
        repaint();
    }
}
