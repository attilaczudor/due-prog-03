package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.EmailSettings;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;

/**
 * Super-Admin-only external SMTP/IMAP mail gateway and corporate domain configuration panel
 * embedded inside {@link AdminView}.
 */
final class AdminEmailSettingsPanel extends JPanel {

    private static final int FIELD_GAP = 8;
    private static final int SCROLL_STEP = 16;

    private final AppContext ctx;
    private final Runnable onUsersChanged;

    private final JPanel domainCard = new JPanel();
    private final JLabel domainTitle = new JLabel();
    private final JPanel domainForm = new JPanel(new GridBagLayout());
    private final JPanel domainInput = new JPanel(new BorderLayout(FIELD_GAP, 0));
    private final JTextField domainField = new JTextField(22);
    private final JCheckBox simModeBox = new JCheckBox();
    private final JButton syncEmailsBtn = new JButton();
    private int domainRows;

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
        JPanel stack = new FormStack();
        GridBagConstraints row = new GridBagConstraints();
        row.gridx = 0;
        row.weightx = 1;
        row.fill = GridBagConstraints.HORIZONTAL;
        row.insets = new Insets(0, 0, 10, 0);

        domainCard.setLayout(new BorderLayout(0, 8));
        domainTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        domainCard.add(domainTitle, BorderLayout.NORTH);
        domainForm.setOpaque(false);
        domainInput.setOpaque(false);
        JLabel domainLabel = new JLabel("@");
        domainLabel.setLabelFor(domainField);
        domainInput.add(domainLabel, BorderLayout.WEST);
        domainInput.add(domainField, BorderLayout.CENTER);
        domainForm.add(domainInput);
        domainForm.add(simModeBox);
        domainForm.add(syncEmailsBtn);
        layoutDomainForm(Integer.MAX_VALUE);
        domainCard.add(domainForm, BorderLayout.CENTER);
        stack.add(domainCard, row);

        smtpCard.setLayout(new BorderLayout(0, 8));
        smtpTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        smtpCard.add(smtpTitle, BorderLayout.NORTH);
        smtpCard.add(buildGatewayForm(smtpHostField, smtpPortField, smtpSecCombo, smtpUserField, smtpPassField, testSmtpBtn),
                BorderLayout.CENTER);
        stack.add(smtpCard, row);

        imapCard.setLayout(new BorderLayout(0, 8));
        imapTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        imapCard.add(imapTitle, BorderLayout.NORTH);
        imapCard.add(buildGatewayForm(imapHostField, imapPortField, imapSecCombo, imapUserField, imapPassField, testImapBtn),
                BorderLayout.CENTER);
        stack.add(imapCard, row);

        JPanel footer = new JPanel(new BorderLayout(FIELD_GAP, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        footer.add(saveBtn, BorderLayout.WEST);
        statusLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
        footer.add(statusLabel, BorderLayout.CENTER);
        row.insets = new Insets(0, 0, 0, 0);
        stack.add(footer, row);
        row.weighty = 1;
        stack.add(Box.createVerticalGlue(), row);

        JScrollPane sp = new JScrollPane(stack, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        add(sp, BorderLayout.CENTER);
    }

    private void layoutDomainForm(int width) {
        int inputAndModeWidth = domainInput.getPreferredSize().width + FIELD_GAP + simModeBox.getPreferredSize().width;
        int allControlsWidth = inputAndModeWidth + FIELD_GAP + syncEmailsBtn.getPreferredSize().width;

        int rows = 3;
        if (width >= allControlsWidth) {
            rows = 1;
        } else if (width >= inputAndModeWidth) {
            rows = 2;
        }
        if (rows == domainRows) {
            return;
        }
        domainRows = rows;
        GridBagLayout layout = (GridBagLayout) domainForm.getLayout();
        GridBagConstraints input = new GridBagConstraints();
        input.gridx = 0;
        input.gridy = 0;
        input.weightx = 1;
        input.gridwidth = domainRows == 3 ? GridBagConstraints.REMAINDER : 1;
        input.fill = GridBagConstraints.HORIZONTAL;
        layout.setConstraints(domainInput, input);

        GridBagConstraints mode = new GridBagConstraints();
        mode.gridx = domainRows == 3 ? 0 : 1;
        mode.gridy = domainRows == 3 ? 1 : 0;
        mode.anchor = GridBagConstraints.LINE_START;
        mode.insets = domainRows == 3 ? new Insets(FIELD_GAP, 0, 0, 0) : new Insets(0, FIELD_GAP, 0, 0);
        mode.gridwidth = domainRows == 3 ? GridBagConstraints.REMAINDER : 1;
        layout.setConstraints(simModeBox, mode);

        GridBagConstraints sync = new GridBagConstraints();
        sync.gridx = domainRows == 1 ? 2 : 0;
        sync.gridy = domainRows - 1;
        sync.anchor = GridBagConstraints.LINE_START;
        sync.insets = domainRows == 1 ? new Insets(0, FIELD_GAP, 0, 0) : new Insets(FIELD_GAP, 0, 0, 0);
        sync.gridwidth = GridBagConstraints.REMAINDER;
        layout.setConstraints(syncEmailsBtn, sync);
        domainForm.invalidate();
    }

    private static JPanel buildGatewayForm(JTextField hostF, JTextField portF, JComboBox<String> secC,
                                           JTextField userF, JPasswordField passF, JButton testBtn) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        addGatewayField(form, 0, 0, "Host:", hostF, 1);
        addGatewayField(form, 2, 0, "Port:", portF, 0.75);
        addGatewayField(form, 0, 1, "User:", userF, 1);
        addGatewayField(form, 2, 1, "Pass:", passF, 0.75);

        JPanel actions = new JPanel(new BorderLayout(FIELD_GAP, 0));
        actions.setOpaque(false);
        actions.add(secC, BorderLayout.WEST);
        actions.add(testBtn, BorderLayout.EAST);
        GridBagConstraints actionRow = new GridBagConstraints();
        actionRow.gridx = 0;
        actionRow.gridy = 2;
        actionRow.gridwidth = GridBagConstraints.REMAINDER;
        actionRow.anchor = GridBagConstraints.LINE_START;
        form.add(actions, actionRow);
        return form;
    }

    private static void addGatewayField(JPanel form, int column, int row, String text, Component field, double weight) {
        JLabel label = new JLabel(text);
        label.setLabelFor(field);
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridx = column;
        cell.gridy = row;
        cell.insets = new Insets(0, column == 0 ? 0 : FIELD_GAP * 2, FIELD_GAP, FIELD_GAP);
        cell.anchor = GridBagConstraints.LINE_START;
        form.add(label, cell);
        cell.gridx++;
        cell.insets = new Insets(0, 0, FIELD_GAP, 0);
        cell.weightx = weight;
        cell.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, cell);
    }

    private final class FormStack extends JPanel implements Scrollable {
        private FormStack() {
            super(new GridBagLayout());
        }

        private void reflow(int width) {
            Insets insets = domainCard.getInsets();
            layoutDomainForm(width - insets.left - insets.right);
        }

        @Override
        public Dimension getPreferredSize() {
            // Measure the wrapped rows before the scroll pane decides whether they need vertical scrolling.
            if (getParent() instanceof JViewport viewport
                    && viewport.getParent() instanceof JScrollPane scroll && scroll.getWidth() > 0) {
                reflow(scroll.getWidth());
                Dimension size = super.getPreferredSize();
                if (size.height <= scroll.getHeight()) {
                    return size;
                }
                reflow(scroll.getWidth() - scroll.getVerticalScrollBar().getPreferredSize().width);
            }
            return super.getPreferredSize();
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return SCROLL_STEP;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(SCROLL_STEP, visibleRect.height - SCROLL_STEP);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
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
                    setStatus(result);
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
            setStatus(i18n.t("common.success") + " — @" + cfg.companyDomain());
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
        setStatus(i18n.t("admin.mail.synced", updated, domainField.getText().trim()));
        JOptionPane.showMessageDialog(this, i18n.t("admin.mail.synced", updated, domainField.getText().trim()),
                i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void setStatus(String message) {
        statusLabel.setText(message);
        statusLabel.setToolTipText(message);
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
