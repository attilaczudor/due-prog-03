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
 * Bilingual FlatLaf sign-in window with live language selector, theme toggle, and demo quick-fill buttons.
 */
public final class LoginFrame extends JFrame implements LanguageChangeListener {

    private final AppContext ctx;
    private final Runnable onLoginSuccess;

    private final JLabel brandLabel = new JLabel("ReWear WMS", SwingConstants.CENTER);
    private final JLabel subtitleLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel usernameLabel = new JLabel();
    private final JLabel passwordLabel = new JLabel();
    private final JTextField usernameField = new JTextField(20);
    private final JPasswordField passwordField = new JPasswordField(20);
    private final JLabel errorLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JButton loginButton = new JButton();
    private final JLabel demoHintLabel = new JLabel("", SwingConstants.CENTER);

    private final JLabel langLabel = new JLabel();
    private final JComboBox<String> langCombo = new JComboBox<>();
    private final JButton themeToggleButton = new JButton();

    private boolean updatingControls = false;

    public LoginFrame(AppContext ctx, Runnable onLoginSuccess) {
        this.ctx = ctx;
        this.onLoginSuccess = onLoginSuccess;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        buildUi();
        populateLanguages();
        applyTranslations();

        ctx.getI18n().addListener(this);
        pack();
        setMinimumSize(new Dimension(460, 420));
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 24, 18, 24));

        // Top controls: language + theme
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        topBar.add(langLabel);
        langCombo.addActionListener(e -> {
            if (!updatingControls && langCombo.getSelectedItem() instanceof String code) {
                ctx.getI18n().setLanguage(code.toLowerCase(Locale.ROOT));
                ctx.getPreferences().setLanguage(code.toLowerCase(Locale.ROOT));
            }
        });
        topBar.add(langCombo);

        themeToggleButton.addActionListener(e -> {
            boolean nextDark = !ctx.getPreferences().isDarkTheme();
            ctx.getPreferences().setDarkTheme(nextDark);
            ThemeManager.applyTheme(nextDark);
            updateThemeButtonText();
        });
        topBar.add(themeToggleButton);
        root.add(topBar, BorderLayout.NORTH);

        // Center form card
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        brandLabel.setFont(brandLabel.getFont().deriveFont(Font.BOLD, 26f));
        brandLabel.setAlignmentX(CENTER_ALIGNMENT);
        subtitleLabel.setFont(subtitleLabel.getFont().deriveFont(Font.PLAIN, 13f));
        subtitleLabel.setAlignmentX(CENTER_ALIGNMENT);

        center.add(Box.createVerticalStrut(4));
        center.add(brandLabel);
        center.add(Box.createVerticalStrut(4));
        center.add(subtitleLabel);
        center.add(Box.createVerticalStrut(20));

        JPanel form = new JPanel(new GridBagLayout());
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

        errorLabel.setForeground(new Color(0xC62828));
        errorLabel.setAlignmentX(CENTER_ALIGNMENT);
        center.add(errorLabel);
        center.add(Box.createVerticalStrut(10));

        loginButton.setFont(loginButton.getFont().deriveFont(Font.BOLD, 14f));
        loginButton.setAlignmentX(CENTER_ALIGNMENT);
        loginButton.setPreferredSize(new Dimension(220, 38));
        loginButton.setMaximumSize(new Dimension(260, 38));
        loginButton.addActionListener(e -> attemptLogin());
        center.add(loginButton);

        root.add(center, BorderLayout.CENTER);

        // Bottom demo quick-fill panel
        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        demoHintLabel.setFont(demoHintLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
        demoHintLabel.setAlignmentX(CENTER_ALIGNMENT);
        footer.add(demoHintLabel);
        footer.add(Box.createVerticalStrut(6));

        JPanel quickFillPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        Consumer<String[]> fill = creds -> {
            usernameField.setText(creds[0]);
            passwordField.setText(creds[1]);
            errorLabel.setText(" ");
        };
        JButton adminBtn = new JButton("Admin");
        adminBtn.addActionListener(e -> fill.accept(new String[]{"admin", "admin123"}));
        JButton managerBtn = new JButton("Manager");
        managerBtn.addActionListener(e -> fill.accept(new String[]{"manager", "manager123"}));
        JButton operatorBtn = new JButton("Operator");
        operatorBtn.addActionListener(e -> fill.accept(new String[]{"operator", "operator123"}));

        quickFillPanel.add(adminBtn);
        quickFillPanel.add(managerBtn);
        quickFillPanel.add(operatorBtn);
        footer.add(quickFillPanel);

        root.add(footer, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().setDefaultButton(loginButton);
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

    private void updateThemeButtonText() {
        boolean isDark = ctx.getPreferences().isDarkTheme();
        themeToggleButton.setText(ctx.getI18n().t(isDark ? "app.theme.light" : "app.theme.dark"));
    }

    private void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        setTitle(i18n.t("login.title"));
        subtitleLabel.setText(i18n.t("app.subtitle"));
        usernameLabel.setText(i18n.t("login.username"));
        passwordLabel.setText(i18n.t("login.password"));
        loginButton.setText(i18n.t("login.button"));
        demoHintLabel.setText(i18n.t("login.demo_hint"));
        langLabel.setText(i18n.t("app.language") + ":");
        updateThemeButtonText();
    }

    @Override
    public void onLanguageChanged(String languageCode) {
        populateLanguages();
        applyTranslations();
    }
}
