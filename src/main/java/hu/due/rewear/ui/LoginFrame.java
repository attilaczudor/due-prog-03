package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.i18n.LanguageChangeListener;
import hu.due.rewear.service.AuthService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Bilingual Brutalist Monochrome sign-in window with live language selector,
 * Light/Dark theme toggle, and 1-click quick-fill buttons.
 */
public final class LoginFrame extends JFrame implements LanguageChangeListener, ThemeManager.ThemeChangeListener {

    private final AppContext ctx;
    private final Runnable onLoginSuccess;

    private final JPanel outer = new JPanel(new BorderLayout(0, 12));
    private final JPanel cardPanel = new JPanel(new BorderLayout(0, 12));
    private final JPanel footer = new JPanel();
    private final JLabel logoLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel loginHeadingLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel usernameLabel = new JLabel();
    private final JLabel passwordLabel = new JLabel();
    private final JTextField usernameField = new JTextField(20);
    private final JPasswordField passwordField = new JPasswordField(20);
    private final JLabel errorLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JButton loginButton = new JButton();
    private final JLabel loginAsLabel = new JLabel("", SwingConstants.CENTER);
    private final JButton superAdminBtn = new JButton("SUPERADMIN");
    private final JButton adminBtn = new JButton("ADMIN");
    private final JButton managerBtn = new JButton("MANAGER");
    private final JButton operatorBtn = new JButton();
    private final JButton sorterBtn = new JButton();
    private final JButton helperBtn = new JButton();
    private final JButton fulfilmentDriverBtn = new JButton();
    private final JButton productionLeadBtn = new JButton();
    private final JButton shiftLeadBtn = new JButton();
    private final JButton forkliftBtn = new JButton();

    private final JComboBox<String> langCombo = new JComboBox<>();

    private boolean updatingControls = false;

    public LoginFrame(AppContext ctx, Runnable onLoginSuccess) {
        this.ctx = ctx;
        this.onLoginSuccess = onLoginSuccess;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        LogoAssets.applyWindowIcon(this);
        buildUi();
        populateLanguages();
        applyTranslations();
        applyBrutalistStyling();

        ctx.getI18n().addListener(this);
        ThemeManager.addListener(this);
        pack();
        setMinimumSize(new Dimension(650, 460));
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        outer.setBorder(BorderFactory.createEmptyBorder(16, 26, 18, 26));

        // Top-right minimal language dropdown only
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        topBar.setOpaque(false);
        langCombo.addActionListener(e -> {
            if (!updatingControls && langCombo.getSelectedItem() instanceof String code) {
                ctx.getI18n().setLanguage(code.toLowerCase(Locale.ROOT));
                ctx.getPreferences().setLanguage(code.toLowerCase(Locale.ROOT));
            }
        });
        topBar.add(langCombo);
        outer.add(topBar, BorderLayout.NORTH);

        // Center borderless login content
        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        logoLabel.setAlignmentX(CENTER_ALIGNMENT);
        loginHeadingLabel.setFont(ThemeManager.monoFont(Font.BOLD, 16f));
        loginHeadingLabel.setAlignmentX(CENTER_ALIGNMENT);

        center.add(Box.createVerticalStrut(4));
        center.add(logoLabel);
        center.add(Box.createVerticalStrut(12));
        center.add(loginHeadingLabel);
        center.add(Box.createVerticalStrut(14));

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 4, 6, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        form.add(usernameLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        form.add(usernameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        form.add(passwordLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        form.add(passwordField, gbc);

        center.add(form);
        center.add(Box.createVerticalStrut(8));

        errorLabel.setForeground(new Color(0xDC2626));
        errorLabel.setAlignmentX(CENTER_ALIGNMENT);
        center.add(errorLabel);
        center.add(Box.createVerticalStrut(10));

        loginButton.setAlignmentX(CENTER_ALIGNMENT);
        loginButton.setPreferredSize(new Dimension(220, 36));
        loginButton.setMaximumSize(new Dimension(260, 36));
        loginButton.addActionListener(e -> attemptLogin());
        center.add(loginButton);

        cardPanel.add(center, BorderLayout.CENTER);
        outer.add(cardPanel, BorderLayout.CENTER);

        // Bottom quick-fill panel (crisp horizontal rule above "Login as:" + equal-sized role buttons)
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setOpaque(false);

        loginAsLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 11f));
        loginAsLabel.setAlignmentX(CENTER_ALIGNMENT);
        footer.add(loginAsLabel);
        footer.add(Box.createVerticalStrut(8));

        JPanel quickFillPanel = new JPanel(new java.awt.GridLayout(2, 4, 8, 6));
        quickFillPanel.setOpaque(false);
        Consumer<String[]> fill = creds -> {
            usernameField.setText(creds[0]);
            passwordField.setText(creds[1]);
            errorLabel.setText(" ");
        };
        superAdminBtn.addActionListener(e -> fill.accept(new String[]{"superadmin", "superadmin123"}));
        adminBtn.addActionListener(e -> fill.accept(new String[]{"admin", "admin123"}));
        managerBtn.addActionListener(e -> fill.accept(new String[]{"manager", "manager123"}));
        operatorBtn.addActionListener(e -> fill.accept(new String[]{"operator", "operator123"}));
        forkliftBtn.addActionListener(e -> fill.accept(new String[]{"forklift", "forklift123"}));

        sorterBtn.addActionListener(e -> fill.accept(new String[]{"sorter01", "sorter123"}));
        helperBtn.addActionListener(e -> fill.accept(new String[]{"helper01", "helper123"}));
        fulfilmentDriverBtn.addActionListener(e -> fill.accept(new String[]{"forklift2", "forklift2123"}));
        productionLeadBtn.addActionListener(e -> fill.accept(new String[]{"prodlead", "prodlead123"}));
        shiftLeadBtn.addActionListener(e -> fill.accept(new String[]{"shiftlead", "shiftlead123"}));

        quickFillPanel.add(superAdminBtn);
        quickFillPanel.add(adminBtn);
        quickFillPanel.add(managerBtn);
        quickFillPanel.add(operatorBtn);
        quickFillPanel.add(sorterBtn);
        quickFillPanel.add(helperBtn);
        quickFillPanel.add(forkliftBtn);
        quickFillPanel.add(fulfilmentDriverBtn);
        quickFillPanel.add(productionLeadBtn);
        quickFillPanel.add(shiftLeadBtn);
        for (var entry : java.util.Map.of(sorterBtn, "sorter01", helperBtn, "helper01",
                fulfilmentDriverBtn, "forklift2", productionLeadBtn, "prodlead",
                shiftLeadBtn, "shiftlead").entrySet()) {
            entry.getKey().setEnabled(ctx.getUserDao().findByUsername(entry.getValue()).isPresent());
        }
        footer.add(quickFillPanel);

        // The quick-fill buttons carry the published demo passwords, so only a demo workspace shows them.
        footer.setVisible(ctx.getOnboardingService().isDemoWorkspace());
        outer.add(footer, BorderLayout.SOUTH);

        setContentPane(outer);
        getRootPane().setDefaultButton(loginButton);
    }

    private void applyBrutalistStyling() {
        outer.setBackground(ThemeManager.bg());
        cardPanel.setBackground(ThemeManager.bg());
        cardPanel.setBorder(BorderFactory.createEmptyBorder(6, 16, 10, 16));
        footer.setBorder(BrutalistStyle.hairlineTopRule(12, 0));
        logoLabel.setIcon(LogoAssets.logoIcon(108, ThemeManager.isDarkMode()));
        loginHeadingLabel.setForeground(ThemeManager.fg());
        usernameLabel.setForeground(ThemeManager.fg());
        passwordLabel.setForeground(ThemeManager.fg());
        BrutalistStyle.stylePrimaryButton(loginButton);
        BrutalistStyle.styleSecondaryButtons(superAdminBtn, adminBtn, managerBtn, operatorBtn, sorterBtn,
                helperBtn, forkliftBtn, fulfilmentDriverBtn, productionLeadBtn, shiftLeadBtn);
        BrutalistStyle.matchButtonSizes(superAdminBtn, adminBtn, managerBtn, operatorBtn, sorterBtn, helperBtn, forkliftBtn, fulfilmentDriverBtn);
        loginAsLabel.setForeground(ThemeManager.muted());
    }

    private void populateLanguages() {
        updatingControls = true;
        try {
            langCombo.removeAllItems();
            for (String lang : ctx.getI18n().getAvailableLanguages()) {
                langCombo.addItem(lang.toUpperCase(Locale.ROOT));
            }
            langCombo.setSelectedItem(ctx.getI18n().getActiveLanguage().toUpperCase(Locale.ROOT));
        } finally {
            updatingControls = false;
        }
    }

    private void attemptLogin() {
        String username = usernameField.getText();
        char[] password = passwordField.getPassword();
        AuthService.AuthResult result = ctx.getAuthService().login(username, password);
        if (result.isSuccess()) {
            ctx.getI18n().removeListener(this);
            ThemeManager.removeListener(this);
            dispose();
            onLoginSuccess.run();
        } else if (result.status() == AuthService.AuthStatus.INACTIVE_USER) {
            errorLabel.setText(ctx.getI18n().t("login.inactive"));
        } else {
            errorLabel.setText(ctx.getI18n().t("login.failed"));
            passwordField.setText("");
            passwordField.requestFocusInWindow();
        }
    }

    private void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        setTitle(i18n.t("login.title"));
        loginHeadingLabel.setText(i18n.t("login.button").toUpperCase(Locale.ROOT));
        usernameLabel.setText(i18n.t("login.username"));
        passwordLabel.setText(i18n.t("login.password"));
        loginButton.setText(i18n.t("login.button"));
        loginAsLabel.setText(i18n.t("login.login_as"));
        operatorBtn.setText(i18n.t("role.OPERATOR"));
        sorterBtn.setText(i18n.t("role.SORTER"));
        helperBtn.setText(i18n.t("role.SORTING_HELPER"));
        productionLeadBtn.setText(i18n.t("role.PRODUCTION_LEAD"));
        shiftLeadBtn.setText(i18n.t("role.SHIFT_LEAD"));
        forkliftBtn.setText(i18n.t("login.supply_driver"));
        fulfilmentDriverBtn.setText(i18n.t("login.fulfilment_driver"));
        BrutalistStyle.matchButtonSizes(superAdminBtn, adminBtn, managerBtn, operatorBtn, sorterBtn, helperBtn, forkliftBtn, fulfilmentDriverBtn);
    }

    @Override
    public void onLanguageChanged(String languageCode) {
        populateLanguages();
        applyTranslations();
        pack();
    }

    @Override
    public void onThemeChanged(boolean dark) {
        applyBrutalistStyling();
        repaint();
    }
}
