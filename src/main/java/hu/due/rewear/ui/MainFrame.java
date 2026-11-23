package hu.due.rewear.ui;

import com.formdev.flatlaf.FlatClientProperties;
import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.i18n.LanguageChangeListener;
import hu.due.rewear.model.AppModule;
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
    public static final String VIEW_TODO = "todo";
    public static final String VIEW_INTAKE = "intake";
    public static final String VIEW_PUTAWAY = "putaway";
    public static final String VIEW_STAGING = "staging";
    public static final String VIEW_TRANSPORT = "transport";
    public static final String VIEW_SCANNER = "scanner";
    public static final String VIEW_INVENTORY = "inventory";
    public static final String VIEW_LOCATIONS = "locations";
    public static final String VIEW_DICTIONARIES = "dictionaries";
    public static final String VIEW_CUSTOMERS = "customers";
    public static final String VIEW_ORDERS = "orders";
    public static final String VIEW_INVOICES = "invoices";
    public static final String VIEW_EMAIL = "email";
    public static final String VIEW_NOTIFICATIONS = "notifications";
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
    private final Map<String, BadgeNavButton> navButtons = new LinkedHashMap<>();
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
    private final JButton notificationBellBtn = new JButton();
    private final Runnable notifListener = () -> javax.swing.SwingUtilities.invokeLater(this::refreshSidebarBadges);
    private final Runnable moduleListener = () -> javax.swing.SwingUtilities.invokeLater(this::rebuildSidebarNavigation);
    private final javax.swing.Timer autoRefreshTimer;
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
        ctx.getNotificationService().addChangeListener(notifListener);
        ctx.getRoleModuleService().addChangeListener(moduleListener);
        refreshSidebarBadges();
        // Automatic background refresh every 10s for live sidebar badges & operational feeds
        this.autoRefreshTimer = new javax.swing.Timer(10_000, e -> performBackgroundAutoRefresh());
        this.autoRefreshTimer.setRepeats(true);
        this.autoRefreshTimer.start();
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
        notificationBellBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        notificationBellBtn.addActionListener(e -> navigateTo(VIEW_NOTIFICATIONS));
        topBar.add(notificationBellBtn, BorderLayout.EAST);
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

        // Core & Operations modules
        addModuleGroup(session, false,
                AppModule.DASHBOARD, AppModule.TODO,
                AppModule.INTAKE, AppModule.PUTAWAY, AppModule.STAGING,
                AppModule.SCANNER, AppModule.INVENTORY);

        // Management modules (Locations, Dictionaries, Customers, Orders, Invoices)
        addModuleGroup(session, true,
                AppModule.LOCATIONS, AppModule.DICTIONARIES,
                AppModule.CUSTOMERS, AppModule.ORDERS, AppModule.INVOICES);

        // Communications modules (Notifications, Messages, Email — in that order for all roles)
        addModuleGroup(session, true,
                AppModule.NOTIFICATIONS, AppModule.MESSAGES, AppModule.EMAIL);

        // Administration module
        addModuleGroup(session, true, AppModule.ADMIN);

        navList.add(Box.createVerticalGlue());

        if (currentViewKey != null && !navButtons.containsKey(currentViewKey)) {
            currentViewKey = VIEW_DASHBOARD;
            cardLayout.show(contentHost, VIEW_DASHBOARD);
        }
        if (currentViewKey != null && navButtons.containsKey(currentViewKey)) {
            navButtons.get(currentViewKey).setSelected(true);
        }
        applySidebarState();
        updateNavButtonStyles();
    }

    private void addModuleGroup(SessionContext session, boolean withTopDivider, AppModule... modules) {
        List<AppModule> visible = new ArrayList<>();
        for (AppModule mod : modules) {
            if (session.canAccessModule(mod)) {
                visible.add(mod);
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        if (withTopDivider && !navButtons.isEmpty()) {
            navList.add(Box.createVerticalStrut(7));
            navList.add(createHairlineSeparator());
            navList.add(Box.createVerticalStrut(7));
        }
        for (AppModule mod : visible) {
            addNavButton(navList, mod.viewKey(), mod.navI18nKey());
        }
    }

    private void applySidebarState() {
        I18nManager i18n = ctx.getI18n();
        int width = sidebarCollapsed ? SIDEBAR_COLLAPSED_WIDTH : SIDEBAR_EXPANDED_WIDTH;
        sidebar.setPreferredSize(new Dimension(width, 0));
        sidebar.setMinimumSize(new Dimension(width, 0));

        // Logo size adapts slightly when collapsed
        brandLogoLabel.setIcon(LogoAssets.logoIcon(sidebarCollapsed ? 42 : 52, ThemeManager.isDarkMode()));

        // Update nav buttons: icons + text when expanded, centered icon-only when collapsed
        for (Map.Entry<String, BadgeNavButton> entry : navButtons.entrySet()) {
            BadgeNavButton btn = entry.getValue();
            btn.setCollapsedMode(sidebarCollapsed);
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
        BadgeNavButton btn = new BadgeNavButton();
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
        registerViewFactory(VIEW_TODO, () -> new TodoView(ctx, this::navigateTo));
        registerViewFactory(VIEW_PUTAWAY, () -> new TransportView(ctx, TransportView.Mode.PUTAWAY));
        registerViewFactory(VIEW_STAGING, () -> new TransportView(ctx, TransportView.Mode.STAGING));
        registerViewFactory(VIEW_LOCATIONS, () -> new LocationsView(ctx));
        registerViewFactory(VIEW_DICTIONARIES, () -> new DictionariesView(ctx));
        registerViewFactory(VIEW_CUSTOMERS, () -> new CustomersView(ctx));
        registerViewFactory(VIEW_ORDERS, () -> new OrdersView(ctx));
        registerViewFactory(VIEW_INVOICES, () -> new InvoicesView(ctx));
        registerViewFactory(VIEW_EMAIL, () -> new EmailView(ctx));
        registerViewFactory(VIEW_NOTIFICATIONS, () -> new NotificationsView(ctx, this::navigateTo));
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
        if (VIEW_TRANSPORT.equals(viewKey)) {
            viewKey = VIEW_PUTAWAY;
        }
        var targetMod = AppModule.fromViewKey(viewKey);
        if (targetMod.isPresent() && !ctx.getSession().canAccessModule(targetMod.get())) {
            return;
        }
        NavigableView view = views.get(viewKey);
        if (view == null) {
            Supplier<NavigableView> factory = viewFactories.get(viewKey);
            if (factory == null) {
                return;
            }
            view = factory.get();
            registerView(view);
        }
        currentViewKey = viewKey;
        JToggleButton btn = navButtons.get(viewKey);
        if (btn != null) {
            btn.setSelected(true);
        }
        updateNavButtonStyles();
        activeViewTitleLabel.setText(ctx.getI18n().t(view.getTitleI18nKey()).toUpperCase(Locale.ROOT));
        notificationBellBtn.setVisible(VIEW_DASHBOARD.equals(viewKey));
        view.refreshData();
        cardLayout.show(contentHost, viewKey);
        refreshSidebarBadges();
    }

    private record BadgeSnapshot(int unreadNotifications,
                                 int unreadEmails,
                                 int unreadMessages,
                                 int todoCount,
                                 int putawayCount,
                                 int stagingCount,
                                 int openOrders,
                                 int overdueInvoices) {
    }

    private void refreshSidebarBadges() {
        if (!ctx.getSession().isAuthenticated()) {
            return;
        }
        final User me = ctx.getSession().requireCurrentUser();
        final boolean hasTodo = navButtons.containsKey(VIEW_TODO);
        final boolean hasPutaway = navButtons.containsKey(VIEW_PUTAWAY);
        final boolean hasStaging = navButtons.containsKey(VIEW_STAGING);
        final boolean hasOrders = navButtons.containsKey(VIEW_ORDERS);
        final boolean hasInvoices = navButtons.containsKey(VIEW_INVOICES);
        final boolean isForklift = ctx.getSession().isForklift();

        AsyncUiRunner.runLatest("mainframe.badges", null, () -> {
            int unreadNotifs = ctx.getNotificationService().countUnread(me.id());
            int unreadEmails = ctx.getEmailService().countUnreadInbox(me.id());
            int unreadMessages = ctx.getChatService().countTotalUnread(me.id());
            int todoCount = 0;
            if (hasTodo) {
                int sysCount = ctx.getTodoService().buildSystemTodosFor(me, ctx.getI18n()).size();
                int openPersonal = (int) ctx.getTodoService().listPersonalTodos(me.id()).stream()
                        .filter(t -> !t.completed()).count();
                todoCount = sysCount + openPersonal;
            }
            int putawayCount = hasPutaway ? ctx.getTransportService().findPutawayQueue().size() : 0;
            int stagingCount = 0;
            if (hasStaging) {
                Long driverFilter = isForklift ? me.id() : null;
                stagingCount = (int) ctx.getTransportService().findOrderStagingTasksForDriver(driverFilter).stream()
                        .filter(t -> !t.isAtDispatchZone()).count();
            }
            int openOrders = hasOrders ? ctx.getOrderDao().countOpenOrders() : 0;
            int overdueInvoices = hasInvoices
                    ? (int) ctx.getInvoiceDao().findAll().stream()
                            .filter(inv -> inv.status() == hu.due.rewear.model.InvoiceStatus.OVERDUE).count()
                    : 0;
            return new BadgeSnapshot(
                    unreadNotifs, unreadEmails, unreadMessages,
                    todoCount, putawayCount, stagingCount, openOrders, overdueInvoices);
        }, snap -> {
            if (!ctx.getSession().isAuthenticated()) {
                return;
            }
            String label = ctx.getI18n().t("todo.tab.notifications").toUpperCase(Locale.ROOT);
            if (snap.unreadNotifications() > 0) {
                notificationBellBtn.setText("[ ! " + snap.unreadNotifications() + " ]  " + label);
                BrutalistStyle.stylePrimaryButton(notificationBellBtn);
            } else {
                notificationBellBtn.setText("[ 0 ]  " + label);
                BrutalistStyle.styleSecondaryButton(notificationBellBtn);
            }
            setNavBadge(VIEW_NOTIFICATIONS, snap.unreadNotifications());
            setNavBadge(VIEW_EMAIL, snap.unreadEmails());
            setNavBadge(VIEW_MESSAGES, snap.unreadMessages());
            if (hasTodo) {
                setNavBadge(VIEW_TODO, snap.todoCount());
            }
            if (hasPutaway) {
                setNavBadge(VIEW_PUTAWAY, snap.putawayCount());
            }
            if (hasStaging) {
                setNavBadge(VIEW_STAGING, snap.stagingCount());
            }
            if (hasOrders) {
                setNavBadge(VIEW_ORDERS, snap.openOrders());
            }
            if (hasInvoices) {
                setNavBadge(VIEW_INVOICES, snap.overdueInvoices());
            }
        }, err -> {
            // Silently ignore transient background badge errors
        });
    }

    private void setNavBadge(String viewKey, int count) {
        BadgeNavButton btn = navButtons.get(viewKey);
        if (btn != null) {
            btn.setBadgeCount(Math.max(0, count));
        }
    }

    /**
     * Sidebar navigation button with an automatic red notification badge:
     * right-aligned numbered badge when expanded, and a top-right red dot without number when collapsed.
     */
    private static final class BadgeNavButton extends JToggleButton {
        private static final java.awt.Color BADGE_RED = new java.awt.Color(0xDC2626);
        private int badgeCount = 0;
        private boolean collapsedMode = false;

        void setBadgeCount(int count) {
            if (this.badgeCount != count) {
                this.badgeCount = count;
                repaint();
            }
        }

        void setCollapsedMode(boolean collapsed) {
            if (this.collapsedMode != collapsed) {
                this.collapsedMode = collapsed;
                repaint();
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (badgeCount <= 0) {
                return;
            }
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(BADGE_RED);
                if (collapsedMode) {
                    int dot = 8;
                    int x = getWidth() - dot - 5;
                    int y = 5;
                    g2.fillOval(x, y, dot, dot);
                } else {
                    String text = badgeCount > 99 ? "99+" : String.valueOf(badgeCount);
                    g2.setFont(ThemeManager.monoFont(Font.BOLD, 10.5f));
                    java.awt.FontMetrics fm = g2.getFontMetrics();
                    int textW = fm.stringWidth(text);
                    int badgeH = 18;
                    int badgeW = Math.max(badgeH, textW + 10);
                    int x = getWidth() - badgeW - 8;
                    int y = (getHeight() - badgeH) / 2;
                    g2.fillRoundRect(x, y, badgeW, badgeH, badgeH, badgeH);
                    g2.setColor(java.awt.Color.WHITE);
                    int tx = x + (badgeW - textW) / 2;
                    int ty = y + ((badgeH - fm.getHeight()) / 2) + fm.getAscent();
                    g2.drawString(text, tx, ty);
                }
            } finally {
                g2.dispose();
            }
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
        String unselBg = dark ? "#000000" : "#FFFFFF";
        String unselFg = dark ? "#FFFFFF" : "#000000";
        String unselHover = dark ? "#1C1C1C" : "#EAEAEA";
        String unselPress = dark ? "#333333" : "#CFCFCF";

        for (Map.Entry<String, BadgeNavButton> entry : navButtons.entrySet()) {
            BadgeNavButton btn = entry.getValue();
            boolean selected = entry.getKey().equals(currentViewKey);
            btn.setCursor(Cursor.getPredefinedCursor(selected ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
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
                                + "; hoverBackground:" + selBg + "; hoverForeground:" + selFg
                                + "; pressedBackground:" + selBg + "; pressedForeground:" + selFg
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
        refreshSidebarBadges();

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

    private void performBackgroundAutoRefresh() {
        if (!isVisible() || !isActive() || !ctx.getSession().isAuthenticated()) {
            return;
        }
        refreshSidebarBadges();
        if (currentViewKey != null && views.containsKey(currentViewKey)) {
            switch (currentViewKey) {
                case VIEW_NOTIFICATIONS, VIEW_TODO, VIEW_PUTAWAY, VIEW_STAGING, VIEW_EMAIL, VIEW_MESSAGES ->
                        views.get(currentViewKey).refreshData();
                default -> { }
            }
        }
    }

    private void performLogout() {
        autoRefreshTimer.stop();
        ctx.getI18n().removeListener(this);
        ThemeManager.removeListener(this);
        ctx.getNotificationService().removeChangeListener(notifListener);
        ctx.getRoleModuleService().removeChangeListener(moduleListener);
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
        refreshSidebarBadges();

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
