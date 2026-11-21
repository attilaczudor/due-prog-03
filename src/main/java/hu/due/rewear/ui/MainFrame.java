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
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Main ReWear WMS application window styled in stark Brutalist Monochrome (Light & Dark),
 * with RBAC-filtered sidebar navigation, clean bottom-left circular user avatar profile bar
 * with settings gear trigger (hosting profile, role/permissions, language, theme & logout),
 * minimal top bar, and modular CardLayout workspace.
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
    private ButtonGroup navGroup = new ButtonGroup();

    private final JPanel sidebar = new JPanel(new BorderLayout());
    private final JPanel brandPanel = new JPanel();
    private final JPanel brandTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    private final JLabel brandLogoLabel = new JLabel();
    private final JLabel brandTagLabel = new JLabel("DUE-PROG-03");
    private final JPanel navList = new JPanel();

    // Sidebar bottom footer: circular avatar | name + role | gear button
    private final JPanel sidebarFooter = new JPanel();
    private final JPanel profilePanel = new JPanel(new BorderLayout(10, 0));
    private final AvatarBadge avatarBadge = new AvatarBadge(38);
    private final JPanel userMetaPanel = new JPanel();
    private final JLabel userNameLabel = new JLabel();
    private final JLabel userRoleLabel = new JLabel();
    private final JButton settingsButton = new JButton();

    private final JPanel topBar = new JPanel(new BorderLayout(12, 0));
    private final JLabel activeViewTitleLabel = new JLabel();
    private final JPanel statusBar = new JPanel(new BorderLayout());
    private final JLabel statusBarLabel = new JLabel();

    private String currentViewKey;

    public MainFrame(AppContext ctx, Runnable onLogout) {
        this.ctx = ctx;
        this.onLogout = onLogout;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1080, 720));
        setPreferredSize(new Dimension(1360, 840));
        LogoAssets.applyWindowIcon(this);

        buildUi();
        registerDefaultViews();
        refreshUserProfileCard();
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
        sidebar.setPreferredSize(new Dimension(260, 0));

        brandPanel.setLayout(new BoxLayout(brandPanel, BoxLayout.Y_AXIS));
        brandTop.add(brandLogoLabel);
        brandTop.add(brandTagLabel);
        brandTop.setAlignmentX(LEFT_ALIGNMENT);

        brandPanel.add(brandTop);
        brandPanel.add(Box.createVerticalStrut(12));
        brandPanel.add(createHairlineSeparator());
        brandPanel.add(Box.createVerticalStrut(10));
        sidebar.add(brandPanel, BorderLayout.NORTH);

        navList.setLayout(new BoxLayout(navList, BoxLayout.Y_AXIS));
        rebuildSidebarNavigation();
        sidebar.add(navList, BorderLayout.CENTER);

        // Sidebar Bottom Footer: Profile Strip ([Avatar] [Name / Role] [Gear])
        sidebarFooter.setLayout(new BoxLayout(sidebarFooter, BoxLayout.Y_AXIS));

        JSeparator topSep = createHairlineSeparator();
        topSep.setAlignmentX(LEFT_ALIGNMENT);
        sidebarFooter.add(topSep);
        sidebarFooter.add(Box.createVerticalStrut(10));

        MouseAdapter openProfileOnClick = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openProfileSettings();
            }
        };

        avatarBadge.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        avatarBadge.addMouseListener(openProfileOnClick);
        JPanel avatarHolder = new JPanel(new BorderLayout());
        avatarHolder.setOpaque(false);
        avatarHolder.add(avatarBadge, BorderLayout.CENTER);
        profilePanel.add(avatarHolder, BorderLayout.WEST);

        userMetaPanel.setLayout(new BoxLayout(userMetaPanel, BoxLayout.Y_AXIS));
        userMetaPanel.setOpaque(false);
        userMetaPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        userMetaPanel.addMouseListener(openProfileOnClick);

        userNameLabel.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        userNameLabel.setAlignmentX(LEFT_ALIGNMENT);
        userRoleLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 10.5f));
        userRoleLabel.setAlignmentX(LEFT_ALIGNMENT);

        userMetaPanel.add(Box.createVerticalGlue());
        userMetaPanel.add(userNameLabel);
        userMetaPanel.add(Box.createVerticalStrut(2));
        userMetaPanel.add(userRoleLabel);
        userMetaPanel.add(Box.createVerticalGlue());
        profilePanel.add(userMetaPanel, BorderLayout.CENTER);

        settingsButton.setIcon(AvatarBadge.createGearIcon(17));
        settingsButton.setPreferredSize(new Dimension(34, 34));
        settingsButton.setMinimumSize(new Dimension(34, 34));
        settingsButton.setMaximumSize(new Dimension(34, 34));
        settingsButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        settingsButton.setFocusPainted(false);
        settingsButton.addActionListener(e -> openProfileSettings());
        JPanel gearHolder = new JPanel(new BorderLayout());
        gearHolder.setOpaque(false);
        gearHolder.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        gearHolder.add(settingsButton, BorderLayout.CENTER);
        profilePanel.add(gearHolder, BorderLayout.EAST);

        profilePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        profilePanel.setAlignmentX(LEFT_ALIGNMENT);
        sidebarFooter.add(profilePanel);

        sidebar.add(sidebarFooter, BorderLayout.SOUTH);
        root.add(sidebar, BorderLayout.WEST);

        // Right Workspace (Minimal Topbar + Content + Footer)
        JPanel rightArea = new JPanel(new BorderLayout());

        activeViewTitleLabel.setFont(ThemeManager.monoFont(Font.BOLD, 15.5f));
        topBar.add(activeViewTitleLabel, BorderLayout.WEST);
        rightArea.add(topBar, BorderLayout.NORTH);

        rightArea.add(contentHost, BorderLayout.CENTER);

        statusBarLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 11f));
        statusBar.add(statusBarLabel, BorderLayout.WEST);
        rightArea.add(statusBar, BorderLayout.SOUTH);

        root.add(rightArea, BorderLayout.CENTER);
        setContentPane(root);
    }

    private void rebuildSidebarNavigation() {
        navList.removeAll();
        navButtons.clear();
        navGroup = new ButtonGroup();

        SessionContext session = ctx.getSession();
        addNavButton(navList, VIEW_DASHBOARD, "nav.dashboard");
        addNavButton(navList, VIEW_INTAKE, "nav.intake");
        addNavButton(navList, VIEW_SCANNER, "nav.scanner");
        addNavButton(navList, VIEW_INVENTORY, "nav.inventory");

        if (session.canManageLocationsAndDictionaries()) {
            navList.add(Box.createVerticalStrut(8));
            navList.add(createHairlineSeparator());
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
            navList.add(createHairlineSeparator());
            navList.add(Box.createVerticalStrut(8));
            addNavButton(navList, VIEW_ADMIN, "nav.admin");
        }
        navList.add(Box.createVerticalGlue());

        I18nManager i18n = ctx.getI18n();
        for (JToggleButton btn : navButtons.values()) {
            Object key = btn.getClientProperty("i18nKey");
            if (key instanceof String i18nKey) {
                btn.setText(i18n.t(i18nKey).toUpperCase(Locale.ROOT));
            }
        }
        if (currentViewKey != null && !navButtons.containsKey(currentViewKey)) {
            currentViewKey = VIEW_DASHBOARD;
            cardLayout.show(contentHost, VIEW_DASHBOARD);
        }
        if (currentViewKey != null && navButtons.containsKey(currentViewKey)) {
            navButtons.get(currentViewKey).setSelected(true);
        }
        updateNavButtonStyles();
        navList.revalidate();
        navList.repaint();
    }

    private static JSeparator createHairlineSeparator() {
        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setPreferredSize(new Dimension(225, 1));
        return sep;
    }

    private void addNavButton(JPanel container, String viewKey, String i18nKey) {
        JToggleButton btn = new JToggleButton();
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        btn.setPreferredSize(new Dimension(225, 36));
        btn.putClientProperty("i18nKey", i18nKey);
        btn.addActionListener(e -> navigateTo(viewKey));
        navGroup.add(btn);
        navButtons.put(viewKey, btn);
        container.add(btn);
        container.add(Box.createVerticalStrut(5));
    }

    private void registerDefaultViews() {
        registerView(new DashboardView(ctx));
        registerView(new PackageIntakeView(ctx));
        registerView(new QrScannerView(ctx));
        registerView(new InventoryView(ctx));
        registerView(new LocationsView(ctx));
        registerView(new DictionariesView(ctx));
        registerView(new CustomersView(ctx));
        registerView(new OrdersView(ctx));
        registerView(new AdminView(ctx));
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

    private void openProfileSettings() {
        ProfileSettingsDialog dialog = new ProfileSettingsDialog(
                this,
                ctx,
                () -> {
                    rebuildSidebarNavigation();
                    refreshUserProfileCard();
                    if (currentViewKey != null && views.containsKey(currentViewKey)) {
                        activeViewTitleLabel.setText(
                                ctx.getI18n().t(views.get(currentViewKey).getTitleI18nKey()).toUpperCase(Locale.ROOT));
                        views.get(currentViewKey).refreshData();
                    }
                },
                this::performLogout
        );
        dialog.setVisible(true);
    }

    private void refreshUserProfileCard() {
        User user = ctx.getSession().requireCurrentUser();
        avatarBadge.setUserAppearance(user.initials(), user.avatarPng());
        userNameLabel.setText(user.displayName());
        userNameLabel.setToolTipText(user.fullName() + " (@" + user.username() + ")");
        userRoleLabel.setText(ctx.getI18n().t("role." + user.roleCode().name()).toUpperCase(Locale.ROOT));
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
        brandPanel.setBackground(ThemeManager.bg());
        brandTop.setBackground(ThemeManager.bg());
        brandLogoLabel.setIcon(LogoAssets.logoIcon(48, ThemeManager.isDarkMode()));
        navList.setBackground(ThemeManager.bg());
        sidebarFooter.setBackground(ThemeManager.bg());
        profilePanel.setBackground(ThemeManager.bg());
        sidebar.setBorder(BrutalistStyle.hairlineRightRule(14, 12));

        userNameLabel.setForeground(ThemeManager.fg());
        userRoleLabel.setForeground(ThemeManager.muted());
        settingsButton.setBackground(ThemeManager.bg());
        settingsButton.setForeground(ThemeManager.fg());
        settingsButton.setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));
        avatarBadge.repaint();

        topBar.setBackground(ThemeManager.bg());
        activeViewTitleLabel.setForeground(ThemeManager.fg());
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, ThemeManager.border()),
                BorderFactory.createEmptyBorder(12, 18, 12, 18)
        ));

        statusBar.setBackground(ThemeManager.bg());
        statusBar.setBorder(BrutalistStyle.hairlineTopRule(5, 14));
        statusBarLabel.setForeground(ThemeManager.muted());

        BrutalistStyle.styleBoxedTag(brandTagLabel, false);
        updateNavButtonStyles();

        for (NavigableView view : views.values()) {
            view.applyTheme();
        }
    }

    public void setStatusMessage(String message) {
        statusBarLabel.setText(message.toUpperCase(Locale.ROOT)
                + "  //  DB: " + ctx.getDatabaseManager().getDatabasePath().getFileName());
    }

    private void performLogout() {
        ctx.getI18n().removeListener(this);
        ThemeManager.removeListener(this);
        ctx.getAuthService().logout();
        dispose();
        onLogout.run();
    }

    private void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        setTitle(i18n.t("app.title"));
        settingsButton.setToolTipText(i18n.t("profile.settings.tooltip"));
        refreshUserProfileCard();

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
        applyTranslations();
    }

    @Override
    public void onThemeChanged(boolean dark) {
        applyBrutalistChrome();
        repaint();
    }
}
