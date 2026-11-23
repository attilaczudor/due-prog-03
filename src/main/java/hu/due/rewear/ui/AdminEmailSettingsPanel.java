package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.EmailSettings;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Super-Admin-only external SMTP/IMAP mail gateway and corporate domain configuration panel
 * embedded inside {@link AdminView}.
 */
final class AdminEmailSettingsPanel extends JPanel {

    private final AppContext ctx;
    private final Runnable onUsersChanged;

    private final JPanel domainCard = new JPanel();
    private final JLabel domainTitle = new JLabel();
    private final JTextField domainField = new JTextField(22);
    private final JCheckBox simModeBox = new JCheckBox();
    private final JButton syncEmailsBtn = new JButton();

    private final JPanel smtpCard = new JPanel();
    private final JLabel smtpTitle = new JLabel();
    private final JTextField smtpHostField = new JTextField(22);
    private final JTextField smtpPortField = new JTextField(6);
    private final JComboBox<String> smtpSecCombo = new JComboBox<>(new String[]{"STARTTLS", "SSL/TLS", "NONE"});
    private final JTextField smtpUserField = new JTextField(22);
    private final JPasswordField smtpPassField = new JPasswordField(22);
    private final JButton testSmtpBtn = new JButton();

    private final JPanel imapCard = new JPanel();
    private final JLabel imapTitle = new JLabel();
    private final JTextField imapHostField = new JTextField(22);
    private final JTextField imapPortField = new JTextField(6);
    private final JComboBox<String> imapSecCombo = new JComboBox<>(new String[]{"SSL/TLS", "STARTTLS", "NONE"});
    private final JTextField imapUserField = new JTextField(22);
    private final JPasswordField imapPassField = new JPasswordField(22);
    private final JButton testImapBtn = new JButton();

    private final JButton saveBtn = new JButton();
    private final JLabel statusLabel = new JLabel(" ");

    AdminEmailSettingsPanel(AppContext ctx, Runnable onUsersChanged) {
        this.ctx = ctx;
        this.onUsersChanged = onUsersChanged;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 4));
        buildUi();
        wireEvents();
        applyTranslations();
        applyTheme();
        refreshData();
    }

    private void buildUi() {
        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));

        // 1. Domain & Mode Card
        domainCard.setLayout(new BorderLayout(0, 8));
        domainTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        domainCard.add(domainTitle, BorderLayout.NORTH);
        JPanel domainForm = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        domainForm.setOpaque(false);
        domainForm.add(new JLabel("@"));
        domainForm.add(domainField);
        domainForm.add(simModeBox);
        domainForm.add(syncEmailsBtn);
        domainCard.add(domainForm, BorderLayout.CENTER);
        stack.add(domainCard);
        stack.add(Box.createVerticalStrut(10));

        // 2. SMTP Card
        smtpCard.setLayout(new BorderLayout(0, 8));
        smtpTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        smtpCard.add(smtpTitle, BorderLayout.NORTH);
        smtpCard.add(buildGatewayForm(smtpHostField, smtpPortField, smtpSecCombo, smtpUserField, smtpPassField, testSmtpBtn),
                BorderLayout.CENTER);
        stack.add(smtpCard);
        stack.add(Box.createVerticalStrut(10));

        // 3. IMAP Card
        imapCard.setLayout(new BorderLayout(0, 8));
        imapTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        imapCard.add(imapTitle, BorderLayout.NORTH);
        imapCard.add(buildGatewayForm(imapHostField, imapPortField, imapSecCombo, imapUserField, imapPassField, testImapBtn),
                BorderLayout.CENTER);
        stack.add(imapCard);
        stack.add(Box.createVerticalStrut(10));

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        footer.add(saveBtn);
        statusLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
        footer.add(statusLabel);
        stack.add(footer);

        JScrollPane sp = new JScrollPane(stack);
        sp.setBorder(null);
        add(sp, BorderLayout.CENTER);
    }

    private static JPanel buildGatewayForm(JTextField hostF, JTextField portF, JComboBox<String> secC,
                                           JTextField userF, JPasswordField passF, JButton testBtn) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0; p.add(new JLabel("Host:"), gbc);
        gbc.gridx = 1; gbc.weightx = 0.6; p.add(hostF, gbc);
        gbc.gridx = 2; gbc.weightx = 0; p.add(new JLabel("Port:"), gbc);
        gbc.gridx = 3; gbc.weightx = 0.15; p.add(portF, gbc);
        gbc.gridx = 4; gbc.weightx = 0; p.add(secC, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0; p.add(new JLabel("User:"), gbc);
        gbc.gridx = 1; gbc.weightx = 0.6; p.add(userF, gbc);
        gbc.gridx = 2; gbc.weightx = 0; p.add(new JLabel("Pass:"), gbc);
        gbc.gridx = 3; gbc.weightx = 0.25; p.add(passF, gbc);
        gbc.gridx = 4; gbc.weightx = 0; p.add(testBtn, gbc);
        return p;
    }

    private void wireEvents() {
        saveBtn.addActionListener(e -> handleSave());
        syncEmailsBtn.addActionListener(e -> handleSyncEmails());
        testSmtpBtn.addActionListener(e -> runTest(smtpHostField.getText(), parsePort(smtpPortField.getText(), 587)));
        testImapBtn.addActionListener(e -> runTest(imapHostField.getText(), parsePort(imapPortField.getText(), 993)));
    }

    private void runTest(String host, int port) {
        boolean sim = simModeBox.isSelected();
        AsyncUiRunner.run(this,
                () -> ctx.getEmailService().testGatewayConnection(host.trim(), port, sim),
                result -> {
                    statusLabel.setText(result);
                    JOptionPane.showMessageDialog(this, result,
                            ctx.getI18n().t("admin.mail.test_btn"), JOptionPane.INFORMATION_MESSAGE);
                },
                ex -> JOptionPane.showMessageDialog(this, ex.getMessage(),
                        ctx.getI18n().t("common.error"), JOptionPane.ERROR_MESSAGE));
    }

    private void handleSave() {
        I18nManager i18n = ctx.getI18n();
        try {
            EmailSettings cfg = new EmailSettings(
                    domainField.getText(),
                    smtpHostField.getText(),
                    parsePort(smtpPortField.getText(), EmailSettings.DEFAULT_SMTP_PORT),
                    String.valueOf(smtpSecCombo.getSelectedItem()),
                    smtpUserField.getText(),
                    new String(smtpPassField.getPassword()),
                    imapHostField.getText(),
                    parsePort(imapPortField.getText(), EmailSettings.DEFAULT_IMAP_PORT),
                    String.valueOf(imapSecCombo.getSelectedItem()),
                    imapUserField.getText(),
                    new String(imapPassField.getPassword()),
                    simModeBox.isSelected()
            );
            ctx.getEmailService().saveSettings(cfg);
            refreshData();
            statusLabel.setText(i18n.t("common.success") + " — @" + cfg.companyDomain());
            JOptionPane.showMessageDialog(this, i18n.t("admin.mail.saved", cfg.companyDomain()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleSyncEmails() {
        I18nManager i18n = ctx.getI18n();
        handleSaveSilent();
        int updated = ctx.getEmailService().syncAllUserEmailsToDomain();
        if (onUsersChanged != null) {
            onUsersChanged.run();
        }
        statusLabel.setText(i18n.t("admin.mail.synced", updated, domainField.getText().trim()));
        JOptionPane.showMessageDialog(this, i18n.t("admin.mail.synced", updated, domainField.getText().trim()),
                i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void handleSaveSilent() {
        EmailSettings cfg = new EmailSettings(
                domainField.getText(),
                smtpHostField.getText(),
                parsePort(smtpPortField.getText(), EmailSettings.DEFAULT_SMTP_PORT),
                String.valueOf(smtpSecCombo.getSelectedItem()),
                smtpUserField.getText(),
                new String(smtpPassField.getPassword()),
                imapHostField.getText(),
                parsePort(imapPortField.getText(), EmailSettings.DEFAULT_IMAP_PORT),
                String.valueOf(imapSecCombo.getSelectedItem()),
                imapUserField.getText(),
                new String(imapPassField.getPassword()),
                simModeBox.isSelected()
        );
        ctx.getEmailService().saveSettings(cfg);
    }

    void refreshData() {
        EmailSettings s = ctx.getEmailService().getSettings();
        domainField.setText(s.companyDomain());
        simModeBox.setSelected(s.simulationMode());
        smtpHostField.setText(s.smtpHost());
        smtpPortField.setText(String.valueOf(s.smtpPort()));
        smtpSecCombo.setSelectedItem(s.smtpSecurity());
        smtpUserField.setText(s.smtpUsername());
        smtpPassField.setText(s.smtpPassword());
        imapHostField.setText(s.imapHost());
        imapPortField.setText(String.valueOf(s.imapPort()));
        imapSecCombo.setSelectedItem(s.imapSecurity());
        imapUserField.setText(s.imapUsername());
        imapPassField.setText(s.imapPassword());
    }

    void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        domainTitle.setText(i18n.t("admin.mail.domain_section"));
        simModeBox.setText(i18n.t("admin.mail.sim_mode"));
        syncEmailsBtn.setText(i18n.t("admin.mail.sync_users"));
        smtpTitle.setText(i18n.t("admin.mail.smtp_section"));
        imapTitle.setText(i18n.t("admin.mail.imap_section"));
        testSmtpBtn.setText(i18n.t("admin.mail.test_btn"));
        testImapBtn.setText(i18n.t("admin.mail.test_btn"));
        saveBtn.setText(i18n.t("common.save"));
    }

    void applyTheme() {
        BrutalistStyle.applyBoxCard(domainCard, 10, 12);
        BrutalistStyle.applyBoxCard(smtpCard, 10, 12);
        BrutalistStyle.applyBoxCard(imapCard, 10, 12);
        BrutalistStyle.stylePrimaryButton(saveBtn);
        BrutalistStyle.styleSecondaryButtons(syncEmailsBtn, testSmtpBtn, testImapBtn);
    }

    private static int parsePort(String raw, int fallback) {
        try {
            int p = Integer.parseInt(raw.trim());
            return p > 0 && p <= 65535 ? p : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }
}
