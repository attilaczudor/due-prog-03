package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.i18n.LanguageChangeListener;
import hu.due.rewear.model.RoleCode;
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
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Main ReWear WMS application window with RBAC-filtered sidebar navigation,
 * live CSV language switcher, Light/Dark theme toggle, and modular CardLayout workspace.
 */
public final class MainFrame extends JFrame implements LanguageChangeListener {

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
        setMinimumSize(new Dimension(1180, 740));
        setPreferredSize(new Dimension(1320, 820));

        buildUi();
        registerDefaultViews();
        populateLanguageCombo();
        applyTranslations();

        ctx.getI18n().addListener(this);
        navigateTo(VIEW_DASHBOARD);

        pack();
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout());

        // Left Sidebar
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(235, 0));
        sidebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY),
                BorderFactory.createEmptyBorder(16, 12, 16, 12)
        ));

        JPanel brandPanel = new JPanel();
        brandPanel.setLayout(new BoxLayout(brandPanel, BoxLayout.Y_AXIS));
        JLabel brandTitle = new JLabel("ReWear WMS");
        brandTitle.setFont(brandTitle.getFont().deriveFont(Font.BOLD, 20f));
        JLabel brandSub = new JLabel("due-prog-03");
        brandSub.setFont(brandSub.getFont().deriveFont(Font.PLAIN, 12f));
        brandPanel.add(brandTitle);
        brandPanel.add(brandSub);
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

        JPanel topBar = new JPanel(new BorderLayout(12, 0));
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)
        ));

        activeViewTitleLabel.setFont(activeViewTitleLabel.getFont().deriveFont(Font.BOLD, 17f));
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
            updateThemeButtonText();
        });
        rightControls.add(themeToggleButton);

        User user = ctx.getSession().requireCurrentUser();
        userBadgeLabel.setText(user.fullName());
        userBadgeLabel.setFont(userBadgeLabel.getFont().deriveFont(Font.BOLD, 12.5f));
        rightControls.add(userBadgeLabel);

        roleBadgeLabel.setOpaque(true);
        roleBadgeLabel.setForeground(Color.WHITE);
        roleBadgeLabel.setBackground(roleColor(user.roleCode()));
        roleBadgeLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        roleBadgeLabel.setFont(roleBadgeLabel.getFont().deriveFont(Font.BOLD, 11f));
        rightControls.add(roleBadgeLabel);

        logoutButton.addActionListener(e -> performLogout());
        rightControls.add(logoutButton);

        topBar.add(rightControls, BorderLayout.EAST);
        rightArea.add(topBar, BorderLayout.NORTH);

        rightArea.add(contentHost, BorderLayout.CENTER);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)
        ));
        statusBarLabel.setFont(statusBarLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
        statusBar.add(statusBarLabel, BorderLayout.WEST);
        rightArea.add(statusBar, BorderLayout.SOUTH);

        root.add(rightArea, BorderLayout.CENTER);
        setContentPane(root);
    }

    private void addNavButton(JPanel container, String viewKey, String i18nKey) {
        JToggleButton btn = new JToggleButton();
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        btn.setPreferredSize(new Dimension(205, 38));
        btn.putClientProperty("i18nKey", i18nKey);
        btn.addActionListener(e -> navigateTo(viewKey));
        navGroup.add(btn);
        navButtons.put(viewKey, btn);
        container.add(btn);
        container.add(Box.createVerticalStrut(4));
    }

    private void registerDefaultViews() {
        registerView(new PlaceholderView(VIEW_DASHBOARD, "nav.dashboard", ctx));
        registerView(new PlaceholderView(VIEW_INTAKE, "nav.intake", ctx));
        registerView(new PlaceholderView(VIEW_SCANNER, "nav.scanner", ctx));
        registerView(new PlaceholderView(VIEW_INVENTORY, "nav.inventory", ctx));
        registerView(new PlaceholderView(VIEW_LOCATIONS, "nav.locations", ctx));
        registerView(new PlaceholderView(VIEW_DICTIONARIES, "nav.dictionaries", ctx));
        registerView(new PlaceholderView(VIEW_CUSTOMERS, "nav.customers", ctx));
        registerView(new PlaceholderView(VIEW_ORDERS, "nav.orders", ctx));
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
        activeViewTitleLabel.setText(ctx.getI18n().t(view.getTitleI18nKey()));
        cardLayout.show(contentHost, viewKey);
        view.refreshData();
    }

    public void setStatusMessage(String message) {
        statusBarLabel.setText(message + "   |   DB: " + ctx.getDatabaseManager().getDatabasePath().getFileName());
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
        ctx.getAuthService().logout();
        dispose();
        onLogout.run();
    }

    private void updateThemeButtonText() {
        boolean isDark = ctx.getPreferences().isDarkTheme();
        themeToggleButton.setText(ctx.getI18n().t(isDark ? "app.theme.light" : "app.theme.dark"));
    }

    private void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        setTitle(i18n.t("app.title"));
        quickScanButton.setText("QR " + i18n.t("nav.scanner"));
        logoutButton.setText(i18n.t("app.logout"));
        updateThemeButtonText();

        User user = ctx.getSession().requireCurrentUser();
        roleBadgeLabel.setText(i18n.t("role." + user.roleCode().name()));

        for (JToggleButton btn : navButtons.values()) {
            Object key = btn.getClientProperty("i18nKey");
            if (key instanceof String i18nKey) {
                btn.setText(i18n.t(i18nKey));
            }
        }
        for (NavigableView view : views.values()) {
            view.applyTranslations();
        }
        if (currentViewKey != null && views.containsKey(currentViewKey)) {
            activeViewTitleLabel.setText(i18n.t(views.get(currentViewKey).getTitleI18nKey()));
        }
        setStatusMessage(i18n.t("app.ready"));
    }

    private static Color roleColor(RoleCode role) {
        return switch (role) {
            case ADMIN -> new Color(0x5E35B1);
            case MANAGER -> new Color(0x00796B);
            case OPERATOR -> new Color(0x1565C0);
        };
    }

    @Override
    public void onLanguageChanged(String languageCode) {
        populateLanguageCombo();
        applyTranslations();
    }
}
