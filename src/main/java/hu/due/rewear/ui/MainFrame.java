package hu.due.rewear.ui;

import com.formdev.flatlaf.FlatClientProperties;
import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.i18n.LanguageChangeListener;
import hu.due.rewear.model.User;
import hu.due.rewear.service.SessionContext;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Main ReWear WMS application window styled in stark Brutalist Monochrome (Light & Dark),
 * with collapsible vector-icon sidebar navigation, bottom-left circular user avatar profile bar
 * with settings gear + collapse/expand arrow controls, minimal top bar, and CardLayout workspace.
 */
public final class MainFrame extends JFrame implements LanguageChangeListener, ThemeManager.ThemeChangeListener {

    public static final String VIEW_DASHBOARD = "dashboard";
    public static final String VIEW_INTAKE = "intake";
    public static final String VIEW_TRANSPORT = "transport";
    public static final String VIEW_SCANNER = "scanner";
    public static final String VIEW_INVENTORY = "inventory";
    public static final String VIEW_LOCATIONS = "locations";
    public static final String VIEW_DICTIONARIES = "dictionaries";
    public static final String VIEW_CUSTOMERS = "customers";
    public static final String VIEW_ORDERS = "orders";
    public static final String VIEW_INVOICES = "invoices";
    public static final String VIEW_EMAIL = "email";
    public static final String VIEW_MESSAGES = "messages";
    public static final String VIEW_ADMIN = "admin";

    private static final int SIDEBAR_EXPANDED_WIDTH = 276;
    private static final int SIDEBAR_COLLAPSED_WIDTH = 74;

    private final AppContext ctx;
    private final Runnable onLogout;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentHost = new JPanel(cardLayout);
    private final Map<String, Supplier<NavigableView>> viewFactories = new LinkedHashMap<>();
    private final Map<String, NavigableView> views = new LinkedHashMap<>();
    private final Map<String, JToggleButton> navButtons = new LinkedHashMap<>();
    private ButtonGroup navGroup = new ButtonGroup();

    private final JPanel sidebar = new JPanel(new BorderLayout());
    private final JPanel brandPanel = new JPanel();
    private final JLabel brandLogoLabel = new JLabel("", SwingConstants.CENTER);
    private final JPanel navList = new JPanel();

    // Sidebar bottom footer (rebuilt cleanly for Expanded vs. Collapsed rail)
    private final JPanel sidebarFooter = new JPanel();
    private final JPanel profileHost = new JPanel(new BorderLayout());
    private final AvatarBadge avatarBadge = new AvatarBadge(38);
    private final JPanel userMetaPanel = new JPanel();
    private final JLabel userNameLabel = new JLabel();
    private final JLabel userRoleLabel = new JLabel();
    private final JButton settingsButton = new JButton();
    private final JButton collapseButton = new JButton();

    private final JPanel topBar = new JPanel(new BorderLayout(12, 0));
    private final JLabel activeViewTitleLabel = new JLabel();
    private final JPanel statusBar = new JPanel(new BorderLayout());
    private final JLabel statusBarLabel = new JLabel();

    private String currentViewKey;
    private boolean sidebarCollapsed = false;

    public MainFrame(AppContext ctx, Runnable onLogout) {
        this.ctx = ctx;
        this.onLogout = onLogout;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1080, 720));
        setPreferredSize(new Dimension(1360, 840));
        LogoAssets.applyWindowIcon(this);

        buildUi();
        registerDefaultViews();
        applyTranslations();
        applyBrutalistChrome();
        installKeyboardShortcuts();

        ctx.getI18n().addListener(this);
        ThemeManager.addListener(this);
        navigateTo(VIEW_DASHBOARD);

        pack();
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout());

        // Left Brutalist Sidebar
        sidebar.setPreferredSize(new Dimension(SIDEBAR_EXPANDED_WIDTH, 0));

        brandPanel.setLayout(new BoxLayout(brandPanel, BoxLayout.Y_AXIS));
        brandLogoLabel.setAlignmentX(CENTER_ALIGNMENT);
        brandPanel.add(brandLogoLabel);
        brandPanel.add(Box.createVerticalStrut(12));
        JSeparator brandSep = createHairlineSeparator();
        brandSep.setAlignmentX(CENTER_ALIGNMENT);
        brandPanel.add(brandSep);
        brandPanel.add(Box.createVerticalStrut(10));
        sidebar.add(brandPanel, BorderLayout.NORTH);

        navList.setLayout(new BoxLayout(navList, BoxLayout.Y_AXIS));
        rebuildSidebarNavigation();
        sidebar.add(navList, BorderLayout.CENTER);

        // Sidebar Bottom Footer
        sidebarFooter.setLayout(new BoxLayout(sidebarFooter, BoxLayout.Y_AXIS));
        JSeparator footerSep = createHairlineSeparator();
        footerSep.setAlignmentX(CENTER_ALIGNMENT);
        sidebarFooter.add(footerSep);
        sidebarFooter.add(Box.createVerticalStrut(10));

        MouseAdapter openProfileOnClick = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openProfileSettings();
            }
        };

        avatarBadge.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        avatarBadge.addMouseListener(openProfileOnClick);

        userMetaPanel.setLayout(new BoxLayout(userMetaPanel, BoxLayout.Y_AXIS));
        userMetaPanel.setOpaque(false);
        userMetaPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        userMetaPanel.addMouseListener(openProfileOnClick);

        userNameLabel.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        userNameLabel.setAlignmentX(LEFT_ALIGNMENT);
        userRoleLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 10f));
        userRoleLabel.setAlignmentX(LEFT_ALIGNMENT);

        userMetaPanel.add(Box.createVerticalGlue());
        userMetaPanel.add(userNameLabel);
        userMetaPanel.add(Box.createVerticalStrut(2));
        userMetaPanel.add(userRoleLabel);
        userMetaPanel.add(Box.createVerticalGlue());

        configureFooterIconButton(settingsButton);
        settingsButton.setIcon(AvatarBadge.createGearIcon(17));
        settingsButton.addActionListener(e -> openProfileSettings());

        configureFooterIconButton(collapseButton);
        collapseButton.addActionListener(e -> {
            sidebarCollapsed = !sidebarCollapsed;
            applySidebarState();
            applyBrutalistChrome();
        });

        profileHost.setOpaque(false);
        profileHost.setAlignmentX(CENTER_ALIGNMENT);
        sidebarFooter.add(profileHost);

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

    private static void configureFooterIconButton(JButton btn) {
        Dimension dim = new Dimension(34, 34);
        btn.setPreferredSize(dim);
        btn.setMinimumSize(dim);
        btn.setMaximumSize(dim);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setFocusPainted(false);
        btn.setMargin(new java.awt.Insets(0, 0, 0, 0));
    }

    private void rebuildSidebarNavigation() {
        navList.removeAll();
        navButtons.clear();
        navGroup = new ButtonGroup();

        SessionContext session = ctx.getSession();
        addNavButton(navList, VIEW_DASHBOARD, "nav.dashboard");
        if (session.canUseIntake()) {
            addNavButton(navList, VIEW_INTAKE, "nav.intake");
        }
        if (session.canUseTransportQueue()) {
            addNavButton(navList, VIEW_TRANSPORT, "nav.transport");
        }
        addNavButton(navList, VIEW_SCANNER, "nav.scanner");
        addNavButton(navList, VIEW_INVENTORY, "nav.inventory");

        if (session.canManageLocationsAndDictionaries()) {
            navList.add(Box.createVerticalStrut(7));
            navList.add(createHairlineSeparator());
            navList.add(Box.createVerticalStrut(7));
            addNavButton(navList, VIEW_LOCATIONS, "nav.locations");
            addNavButton(navList, VIEW_DICTIONARIES, "nav.dictionaries");
        }
        if (session.canManageOrders()) {
            addNavButton(navList, VIEW_CUSTOMERS, "nav.customers");
            addNavButton(navList, VIEW_ORDERS, "nav.orders");
            addNavButton(navList, VIEW_INVOICES, "nav.invoices");
        }

        // Communications (available to ALL logged-in users)
        navList.add(Box.createVerticalStrut(7));
        navList.add(createHairlineSeparator());
        navList.add(Box.createVerticalStrut(7));
        addNavButton(navList, VIEW_EMAIL, "nav.email");
        addNavButton(navList, VIEW_MESSAGES, "nav.messages");

        if (session.canManageUsersAndMaintenance()) {
            navList.add(Box.createVerticalStrut(7));
            navList.add(createHairlineSeparator());
            navList.add(Box.createVerticalStrut(7));
            addNavButton(navList, VIEW_ADMIN, "nav.admin");
        }
        navList.add(Box.createVerticalGlue());

        if (currentViewKey != null && !navButtons.containsKey(currentViewKey)) {
            currentViewKey = VIEW_DASHBOARD;
            cardLayout.show(contentHost, VIEW_DASHBOARD);
        }
        if (currentViewKey != null && navButtons.containsKey(currentViewKey)) {
            navButtons.get(currentViewKey).setSelected(true);
        }
        applySidebarState();
    }

    private void applySidebarState() {
        I18nManager i18n = ctx.getI18n();
        int width = sidebarCollapsed ? SIDEBAR_COLLAPSED_WIDTH : SIDEBAR_EXPANDED_WIDTH;
        sidebar.setPreferredSize(new Dimension(width, 0));
        sidebar.setMinimumSize(new Dimension(width, 0));

        // Logo size adapts slightly when collapsed
        brandLogoLabel.setIcon(LogoAssets.logoIcon(sidebarCollapsed ? 42 : 52, ThemeManager.isDarkMode()));

        // Update nav buttons: icons + text when expanded, centered icon-only when collapsed
        for (Map.Entry<String, JToggleButton> entry : navButtons.entrySet()) {
            JToggleButton btn = entry.getValue();
            String i18nKey = (String) btn.getClientProperty("i18nKey");
            String fullLabel = i18nKey != null ? i18n.t(i18nKey) : "";
            btn.setToolTipText(fullLabel);
            btn.setIcon(SidebarIcons.forView(entry.getKey(), 16));
            if (sidebarCollapsed) {
                btn.setText("");
                btn.setHorizontalAlignment(SwingConstants.CENTER);
                btn.setIconTextGap(0);
                Dimension sq = new Dimension(42, 42);
                btn.setPreferredSize(sq);
                btn.setMinimumSize(sq);
                btn.setMaximumSize(sq);
            } else {
                btn.setText(fullLabel.toUpperCase(Locale.ROOT));
                btn.setHorizontalAlignment(SwingConstants.LEFT);
                btn.setIconTextGap(10);
                btn.setPreferredSize(new Dimension(248, 36));
                btn.setMinimumSize(new Dimension(120, 36));
                btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            }
        }

        // Rebuild bottom profile footer for Expanded vs. Collapsed layout
        profileHost.removeAll();
        if (sidebarCollapsed) {
            Dimension collapsedSq = new Dimension(42, 42);
            for (JButton b : new JButton[]{settingsButton, collapseButton}) {
                b.setPreferredSize(collapsedSq);
                b.setMinimumSize(collapsedSq);
                b.setMaximumSize(collapsedSq);
            }
            collapseButton.setIcon(SidebarIcons.arrowRight(16));
            collapseButton.setToolTipText(i18n.t("sidebar.expand"));

            JPanel col = new JPanel();
            col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
            col.setOpaque(false);

            avatarBadge.setAlignmentX(CENTER_ALIGNMENT);
            settingsButton.setAlignmentX(CENTER_ALIGNMENT);
            collapseButton.setAlignmentX(CENTER_ALIGNMENT);

            col.add(avatarBadge);
            col.add(Box.createVerticalStrut(8));
            col.add(settingsButton);
            col.add(Box.createVerticalStrut(6));
            col.add(collapseButton);
            profileHost.add(col, BorderLayout.CENTER);
        } else {
            Dimension expandedSq = new Dimension(34, 34);
            for (JButton b : new JButton[]{settingsButton, collapseButton}) {
                b.setPreferredSize(expandedSq);
                b.setMinimumSize(expandedSq);
                b.setMaximumSize(expandedSq);
            }
            collapseButton.setIcon(SidebarIcons.arrowLeft(16));
            collapseButton.setToolTipText(i18n.t("sidebar.collapse"));

            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);

            JPanel avatarWrap = new JPanel(new BorderLayout());
            avatarWrap.setOpaque(false);
            avatarWrap.add(avatarBadge, BorderLayout.CENTER);
            row.add(avatarWrap, BorderLayout.WEST);

            row.add(userMetaPanel, BorderLayout.CENTER);

            JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
            rightBtns.setOpaque(false);
            rightBtns.add(settingsButton);
            rightBtns.add(collapseButton);
            row.add(rightBtns, BorderLayout.EAST);

            profileHost.add(row, BorderLayout.CENTER);
        }

        updateNavButtonStyles();
        sidebar.revalidate();
        sidebar.repaint();
        getContentPane().revalidate();
        getContentPane().repaint();
    }

    private static JSeparator createHairlineSeparator() {
        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setPreferredSize(new Dimension(44, 1));
        return sep;
    }

    private void addNavButton(JPanel container, String viewKey, String i18nKey) {
        JToggleButton btn = new JToggleButton();
        btn.setAlignmentX(CENTER_ALIGNMENT);
        btn.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.putClientProperty("i18nKey", i18nKey);
        btn.addActionListener(e -> navigateTo(viewKey));
        navGroup.add(btn);
        navButtons.put(viewKey, btn);
        container.add(btn);
        container.add(Box.createVerticalStrut(5));
    }

    private void installKeyboardShortcuts() {
        JRootPane rp = getRootPane();
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        var inputMap = rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var actionMap = rp.getActionMap();

        // Cmd/Ctrl + 1..9 (and 0 for 10th) switches directly between permitted sidebar views
        int[] digitKeys = {
                KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5,
                KeyEvent.VK_6, KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9, KeyEvent.VK_0
        };
        for (int idx = 0; idx < digitKeys.length; idx++) {
            final int slot = idx;
            String actionKey = "rewear.nav." + slot;
            inputMap.put(KeyStroke.getKeyStroke(digitKeys[idx], menuMask), actionKey);
            actionMap.put(actionKey, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    List<String> keys = new ArrayList<>(navButtons.keySet());
                    if (slot < keys.size()) {
                        navigateTo(keys.get(slot));
                    }
                }
            });
        }

        // F5 and Cmd/Ctrl + R refresh the active workspace view
        Runnable refreshAction = () -> {
            if (currentViewKey != null && views.containsKey(currentViewKey)) {
                views.get(currentViewKey).refreshData();
                setStatusMessage(ctx.getI18n().t("status.refreshed"));
            }
        };
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "rewear.refresh");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, menuMask), "rewear.refresh");
        actionMap.put("rewear.refresh", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshAction.run();
            }
        });
    }

    private void registerDefaultViews() {
        // Lazy factories: views are instantiated on first navigation so login only loads DashboardView
        registerViewFactory(VIEW_DASHBOARD, () -> new DashboardView(ctx));
        registerViewFactory(VIEW_INTAKE, () -> new PackageIntakeView(ctx));
        registerViewFactory(VIEW_SCANNER, () -> new QrScannerView(ctx));
        registerViewFactory(VIEW_INVENTORY, () -> new InventoryView(ctx));
        registerViewFactory(VIEW_TRANSPORT, () -> new TransportView(ctx));
        registerViewFactory(VIEW_LOCATIONS, () -> new LocationsView(ctx));
        registerViewFactory(VIEW_DICTIONARIES, () -> new DictionariesView(ctx));
        registerViewFactory(VIEW_CUSTOMERS, () -> new CustomersView(ctx));
        registerViewFactory(VIEW_ORDERS, () -> new OrdersView(ctx));
        registerViewFactory(VIEW_INVOICES, () -> new InvoicesView(ctx));
        registerViewFactory(VIEW_EMAIL, () -> new EmailView(ctx));
        registerViewFactory(VIEW_MESSAGES, () -> new MessagesView(ctx));
        registerViewFactory(VIEW_ADMIN, () -> new AdminView(ctx));
    }

    private void registerViewFactory(String viewKey, Supplier<NavigableView> factory) {
        viewFactories.put(viewKey, factory);
    }

    public void registerView(NavigableView view) {
        views.put(view.getViewKey(), view);
        contentHost.add(view.getComponent(), view.getViewKey());
    }

    public void navigateTo(String viewKey) {
        NavigableView view = views.get(viewKey);
        boolean newlyCreated = false;
        if (view == null) {
            Supplier<NavigableView> factory = viewFactories.get(viewKey);
            if (factory == null) {
                return;
            }
            view = factory.get();
            registerView(view);
            newlyCreated = true;
        }
        currentViewKey = viewKey;
        JToggleButton btn = navButtons.get(viewKey);
        if (btn != null) {
            btn.setSelected(true);
        }
        updateNavButtonStyles();
        activeViewTitleLabel.setText(ctx.getI18n().t(view.getTitleI18nKey()).toUpperCase(Locale.ROOT));
        cardLayout.show(contentHost, viewKey);
        if (!newlyCreated) {
            view.refreshData();
        }
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
        String roleText = ctx.getI18n().t("role." + user.roleCode().name()).toUpperCase(Locale.ROOT);
        avatarBadge.setUserAppearance(user.initials(), user.avatarPng());
        avatarBadge.setToolTipText(user.displayName() + " — " + roleText);
        userNameLabel.setText(user.displayName());
        userNameLabel.setToolTipText(user.fullName() + " (@" + user.username() + ")");
        userRoleLabel.setText(roleText);
    }

    private void updateNavButtonStyles() {
        int padH = sidebarCollapsed ? 0 : 10;
        boolean dark = ThemeManager.isDarkMode();
        String selBg = dark ? "#FFFFFF" : "#000000";
        String selFg = dark ? "#000000" : "#FFFFFF";
        String selHover = dark ? "#DCDCDC" : "#2A2A2A";
        String selPress = dark ? "#BDBDBD" : "#424242";
        String unselBg = dark ? "#000000" : "#FFFFFF";
        String unselFg = dark ? "#FFFFFF" : "#000000";
        String unselHover = dark ? "#1C1C1C" : "#EAEAEA";
        String unselPress = dark ? "#333333" : "#CFCFCF";

        for (Map.Entry<String, JToggleButton> entry : navButtons.entrySet()) {
            JToggleButton btn = entry.getValue();
            boolean selected = entry.getKey().equals(currentViewKey);
            btn.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ThemeManager.border(), 1),
                    BorderFactory.createEmptyBorder(6, padH, 6, padH)
            ));
            if (selected) {
                btn.setBackground(ThemeManager.fg());
                btn.setForeground(ThemeManager.bg());
                btn.putClientProperty(FlatClientProperties.STYLE,
                        "background:" + selBg + "; foreground:" + selFg
                                + "; selectedBackground:" + selBg + "; selectedForeground:" + selFg
                                + "; hoverBackground:" + selHover + "; hoverForeground:" + selFg
                                + "; pressedBackground:" + selPress + "; pressedForeground:" + selFg
                                + "; focusedBackground:" + selBg + "; focusedForeground:" + selFg);
            } else {
                btn.setBackground(ThemeManager.bg());
                btn.setForeground(ThemeManager.fg());
                btn.putClientProperty(FlatClientProperties.STYLE,
                        "background:" + unselBg + "; foreground:" + unselFg
                                + "; selectedBackground:" + selBg + "; selectedForeground:" + selFg
                                + "; hoverBackground:" + unselHover + "; hoverForeground:" + unselFg
                                + "; pressedBackground:" + unselPress + "; pressedForeground:" + unselFg
                                + "; focusedBackground:" + unselBg + "; focusedForeground:" + unselFg);
            }
        }
    }

    private void applyBrutalistChrome() {
        boolean dark = ThemeManager.isDarkMode();
        String footBg = dark ? "#000000" : "#FFFFFF";
        String footFg = dark ? "#FFFFFF" : "#000000";
        String footHover = dark ? "#1C1C1C" : "#EAEAEA";
        String footPress = dark ? "#333333" : "#CFCFCF";

        sidebar.setBackground(ThemeManager.bg());
        brandPanel.setBackground(ThemeManager.bg());
        brandLogoLabel.setIcon(LogoAssets.logoIcon(sidebarCollapsed ? 42 : 52, dark));
        navList.setBackground(ThemeManager.bg());
        sidebarFooter.setBackground(ThemeManager.bg());
        sidebar.setBorder(BrutalistStyle.hairlineRightRule(14, sidebarCollapsed ? 10 : 12));

        userNameLabel.setForeground(ThemeManager.fg());
        userRoleLabel.setForeground(ThemeManager.muted());
        for (JButton footerBtn : new JButton[]{settingsButton, collapseButton}) {
            footerBtn.setBackground(ThemeManager.bg());
            footerBtn.setForeground(ThemeManager.fg());
            footerBtn.putClientProperty(FlatClientProperties.STYLE,
                    "background:" + footBg + "; foreground:" + footFg
                            + "; hoverBackground:" + footHover + "; hoverForeground:" + footFg
                            + "; pressedBackground:" + footPress + "; pressedForeground:" + footFg
                            + "; focusedBackground:" + footBg + "; focusedForeground:" + footFg);
            footerBtn.setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));
        }
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

        updateNavButtonStyles();

        for (NavigableView view : views.values()) {
            view.applyTheme();
        }
    }

    public void setStatusMessage(String message) {
        java.nio.file.Path dbPath = ctx.getDatabaseManager().getDatabasePath();
        statusBarLabel.setText(message.toUpperCase(Locale.ROOT)
                + "  //  DB: ./data/" + dbPath.getFileName());
        statusBarLabel.setToolTipText(dbPath.toString());
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
        applySidebarState();

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
