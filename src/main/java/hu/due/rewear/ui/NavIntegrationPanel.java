package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.model.NavReport;
import hu.due.rewear.model.NavReportState;
import hu.due.rewear.model.NavSettings;
import hu.due.rewear.model.NavSettings.Environment;
import hu.due.rewear.model.NavSettings.Field;
import hu.due.rewear.nav.NavCredentials;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;

/**
 * The Online Számla reporting screen: who this installation reports as, the technical user's credentials, and
 * the queue of invoices the tax authority has or has not accepted.
 *
 * <p>Reporting starts switched off and pointed at the test environment. Nothing is sent until somebody enters
 * a technical user and turns it on, which is deliberate: the alternative is an application that quietly files
 * tax data the moment it is installed.
 */
final class NavIntegrationPanel extends JPanel {
    private final AppContext ctx;
    private final JPanel configCard = new JPanel(new BorderLayout(0, 8));
    private final JPanel queueCard = new JPanel(new BorderLayout(0, 8));
    private final JPanel form = new JPanel(new GridBagLayout());
    private final java.util.Map<JLabel, String> labels = new java.util.LinkedHashMap<>();
    private final EnumMap<Field, JTextField> fields = new EnumMap<>(Field.class);
    private final JComboBox<Environment> environment = new JComboBox<>(Environment.values());
    private final JComboBox<String> softwareOperation =
            new JComboBox<>(new String[]{"LOCAL_SOFTWARE", "ONLINE_SERVICE"});
    private final JTextField endpoint = new JTextField(25);
    private final JPasswordField password = new JPasswordField(20);
    private final JPasswordField signingKey = new JPasswordField(20);
    private final JPasswordField exchangeKey = new JPasswordField(20);
    private final JCheckBox enabled = new JCheckBox();
    private final JLabel configTitle = new JLabel();
    private final JLabel queueTitle = new JLabel();
    private final JLabel status = new JLabel(" ");
    private final JTextArea notes = new JTextArea(4, 28);
    private final JButton save = new JButton();
    private final JButton saveCredentials = new JButton();
    private final JButton runQueue = new JButton();
    private final JButton retry = new JButton();
    private final JButton annul = new JButton();
    private final JButton history = new JButton();
    private final javax.swing.table.DefaultTableModel queueModel = BrutalistStyle.nonEditableTableModel();
    private final JTable queue = new JTable(queueModel) {
        @Override public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            int column = columnAtPoint(event.getPoint());
            return row < 0 || column < 0 ? null : String.valueOf(getValueAt(row, column));
        }
    };
    private int row;
    private java.util.List<NavReport> shown = List.of();

    NavIntegrationPanel(AppContext ctx) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        endpoint.setEditable(false);
        addField("nav.enabled", enabled);
        addField("nav.environment", environment);
        addField("nav.endpoint", endpoint);
        addText(Field.TAX_NUMBER);
        addText(Field.VAT_CODE);
        addText(Field.COUNTY_CODE);
        addText(Field.LOGIN);
        addField("nav.password", password);
        addField("nav.signing_key", signingKey);
        addField("nav.exchange_key", exchangeKey);
        addText(Field.SOFTWARE_ID);
        addText(Field.SOFTWARE_NAME);
        addText(Field.SOFTWARE_VERSION);
        addField("nav.software_operation", softwareOperation);
        addText(Field.DEVELOPER);
        addText(Field.DEVELOPER_CONTACT);
        addText(Field.DEVELOPER_COUNTRY);
        addText(Field.DEVELOPER_TAX);
        enabled.setOpaque(false);
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setFocusable(false);
        notes.setMargin(new Insets(2, 3, 2, 3));

        var top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);
        top.add(configTitle, BorderLayout.NORTH);
        top.add(notes, BorderLayout.CENTER);
        configCard.add(top, BorderLayout.NORTH);
        var scroll = new JScrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        configCard.add(scroll, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actions.setOpaque(false);
        for (var button : List.of(save, saveCredentials)) {
            actions.add(button);
        }
        var footer = new JPanel(new BorderLayout(0, 6));
        footer.setOpaque(false);
        footer.add(actions, BorderLayout.NORTH);
        footer.add(status, BorderLayout.SOUTH);
        configCard.add(footer, BorderLayout.SOUTH);

        var queueActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        queueActions.setOpaque(false);
        for (var button : List.of(runQueue, retry, annul, history)) {
            queueActions.add(button);
        }
        var queueTop = new JPanel(new BorderLayout(0, 4));
        queueTop.setOpaque(false);
        queueTop.add(queueTitle, BorderLayout.NORTH);
        queueTop.add(queueActions, BorderLayout.SOUTH);
        queueCard.add(queueTop, BorderLayout.NORTH);
        var tableScroll = new JScrollPane(queue);
        tableScroll.setPreferredSize(new Dimension(250, 160));
        queueCard.add(tableScroll, BorderLayout.CENTER);

        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, configCard, queueCard);
        split.setResizeWeight(0.65);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);

        environment.addActionListener(e -> updateEndpoint());
        save.addActionListener(e -> saveSettings());
        saveCredentials.addActionListener(e -> storeCredentials());
        runQueue.addActionListener(e -> runQueueNow());
        retry.addActionListener(e -> retrySelected());
        annul.addActionListener(e -> annulSelected());
        history.addActionListener(e -> showHistory());
        load(ctx.getSettingsDao().getNavSettings());
    }

    private void addText(Field field) {
        var input = new JTextField(20);
        fields.put(field, input);
        addField("nav." + field.key(), input);
    }

    private void addField(String key, JComponent input) {
        var label = new JLabel();
        label.setLabelFor(input);
        labels.put(label, key);
        var constraints = new GridBagConstraints();
        constraints.gridy = row++;
        constraints.anchor = GridBagConstraints.LINE_START;
        constraints.insets = new Insets(3, 4, 3, 6);
        constraints.gridx = 0;
        form.add(label, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        form.add(input, constraints);
    }

    private void load(NavSettings saved) {
        fields.forEach((key, field) -> field.setText(saved.value(key)));
        environment.setSelectedItem(saved.environment());
        softwareOperation.setSelectedItem(saved.value(Field.SOFTWARE_OPERATION));
        enabled.setSelected(saved.enabled());
        // The stored secrets are never read back into the form. A field that shows asterisks for a value the
        // screen does not actually hold invites somebody to save the asterisks over the real key.
        password.setText("");
        signingKey.setText("");
        exchangeKey.setText("");
        updateEndpoint();
    }

    private void updateEndpoint() {
        endpoint.setText(((Environment) environment.getSelectedItem()).endpoint());
        endpoint.setToolTipText(endpoint.getText());
        endpoint.setCaretPosition(0);
    }

    void applyTranslations() {
        configTitle.setText(t("nav.title"));
        notes.setText(t("nav.note", ctx.getNavCredentialStore().file().toString()));
        notes.setCaretPosition(0);
        queueTitle.setText(t("nav.queue"));
        labels.forEach((label, key) -> label.setText(t(key)));
        save.setText(t("common.save"));
        saveCredentials.setText(t("nav.save_credentials"));
        runQueue.setText(t("nav.run_queue"));
        retry.setText(t("nav.retry"));
        annul.setText(t("nav.annul"));
        history.setText(t("nav.history"));
        queueModel.setColumnIdentifiers(new String[]{t("invoice.number"), t("invoice.partner"),
                t("nav.operation"), t("common.status"), t("nav.transaction"), t("nav.message")});
        renderQueue();
    }

    void applyTheme() {
        BrutalistStyle.applyBoxCard(configCard, 10, 12);
        BrutalistStyle.applyBoxCard(queueCard, 10, 12);
        BrutalistStyle.stylePrimaryButton(runQueue);
        BrutalistStyle.styleSecondaryButtons(save, saveCredentials, retry, annul, history);
        BrutalistStyle.styleTable(queue);
        notes.setBackground(ThemeManager.bg());
        notes.setForeground(ThemeManager.muted());
        status.setForeground(ThemeManager.fg());
    }

    void refreshData() {
        status.setText(readiness());
        renderQueue();
    }

    /** One line telling an administrator whether anything would actually be sent, and why not. */
    private String readiness() {
        NavSettings saved = ctx.getSettingsDao().getNavSettings();
        if (!saved.enabled()) {
            return t("nav.state.off");
        }
        if (!ctx.getNavReportingService().isReady()) {
            return t("nav.state.incomplete");
        }
        var store = ctx.getNavCredentialStore();
        return t(saved.environment() == Environment.PRODUCTION ? "nav.state.live" : "nav.state.test")
                + (store.isProtected() ? "" : " — " + t("nav.state.unprotected"));
    }

    private void saveSettings() {
        try {
            var values = new EnumMap<Field, String>(Field.class);
            fields.forEach((key, field) -> values.put(key, field.getText().strip()));
            values.put(Field.ENVIRONMENT, ((Environment) environment.getSelectedItem()).name());
            values.put(Field.SOFTWARE_OPERATION, (String) softwareOperation.getSelectedItem());
            values.put(Field.ENABLED, Boolean.toString(enabled.isSelected()));
            validate(values);
            if (enabled.isSelected()
                    && environment.getSelectedItem() == Environment.PRODUCTION
                    && !confirmProduction()) {
                return;
            }
            ctx.getSettingsDao().saveNavSettings(new NavSettings(values));
            status.setText(readiness());
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Switching to the live service means real reports against a real taxpayer, so it is confirmed rather
     * than assumed. A mistaken report has to be annulled by a human at NAV, which is a far worse afternoon
     * than one extra dialog.
     */
    private boolean confirmProduction() {
        return JOptionPane.showConfirmDialog(this, t("nav.confirm_production"),
                t("nav.title"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                == JOptionPane.OK_OPTION;
    }

    private void validate(EnumMap<Field, String> values) {
        boolean switchingOn = Boolean.parseBoolean(values.get(Field.ENABLED));
        for (var field : List.of(Field.SOFTWARE_ID, Field.SOFTWARE_NAME, Field.SOFTWARE_VERSION,
                Field.DEVELOPER, Field.DEVELOPER_CONTACT)) {
            if (values.get(field).isBlank()) {
                throw new IllegalArgumentException(t("nav.required", t("nav." + field.key())));
            }
        }
        if (switchingOn) {
            for (var field : List.of(Field.TAX_NUMBER, Field.LOGIN)) {
                if (values.get(field).isBlank()) {
                    throw new IllegalArgumentException(t("nav.required", t("nav." + field.key())));
                }
            }
        }
        check(values.get(Field.TAX_NUMBER).matches("[0-9]{8}|"), "nav.tax_format");
        check(values.get(Field.SOFTWARE_ID).matches("[A-Z0-9-]{18}"), "nav.software_format");
        check(values.get(Field.DEVELOPER_COUNTRY).matches("[A-Z]{2}|"), "nav.country_format");
        check(values.get(Field.VAT_CODE).matches("[1-5]|"), "nav.vat_code_format");
        check(values.get(Field.COUNTY_CODE).matches("[0-9]{2}|"), "nav.county_format");
    }

    private void check(boolean valid, String key) {
        if (!valid) {
            throw new IllegalArgumentException(t(key));
        }
    }

    private void storeCredentials() {
        char[] pass = password.getPassword();
        char[] sign = signingKey.getPassword();
        char[] exchange = exchangeKey.getPassword();
        try {
            var credentials = new NavCredentials(new String(pass), new String(sign), new String(exchange));
            if (!credentials.isComplete()) {
                throw new IllegalArgumentException(t("nav.credentials_required"));
            }
            if (!credentials.hasUsableExchangeKey()) {
                throw new IllegalArgumentException(t("nav.exchange_key_length"));
            }
            ctx.getNavCredentialStore().save(credentials);
            password.setText("");
            signingKey.setText("");
            exchangeKey.setText("");
            status.setText(t("nav.credentials_saved", ctx.getNavCredentialStore().file().toString()));
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), t("common.error"), JOptionPane.ERROR_MESSAGE);
        } finally {
            // The characters are wiped rather than left for the garbage collector to get round to.
            Arrays.fill(pass, '\0');
            Arrays.fill(sign, '\0');
            Arrays.fill(exchange, '\0');
        }
    }

    private void runQueueNow() {
        AsyncUiRunner.run(this,
                () -> ctx.getNavReportingService().runQueue(),
                result -> {
                    status.setText(t("nav.queue_run", result.submitted(), result.confirmed(),
                            result.failed(), result.deferred()));
                    renderQueue();
                },
                error -> JOptionPane.showMessageDialog(this, String.valueOf(error.getMessage()),
                        t("common.error"), JOptionPane.ERROR_MESSAGE));
    }

    private void retrySelected() {
        NavReport selected = selected();
        if (selected == null) {
            return;
        }
        try {
            ctx.getNavReportingService().retry(selected.id(), ctx.getSession().requireCurrentUser().id());
            refreshData();
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void annulSelected() {
        NavReport selected = selected();
        if (selected == null) {
            return;
        }
        String reason = JOptionPane.showInputDialog(this, t("nav.annul_reason"), t("nav.annul"),
                JOptionPane.QUESTION_MESSAGE);
        if (reason == null) {
            return;
        }
        try {
            ctx.getNavReportingService().requestAnnulment(selected.id(), reason,
                    ctx.getSession().requireCurrentUser().id());
            JOptionPane.showMessageDialog(this, t("nav.annul_recorded"), t("nav.annul"),
                    JOptionPane.INFORMATION_MESSAGE);
            refreshData();
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showHistory() {
        NavReport selected = selected();
        if (selected == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (var event : ctx.getNavReportDao().findEvents(selected.id())) {
            text.append(event.createdAt()).append("  ")
                .append(event.fromState() == null ? "—" : event.fromState()).append(" → ")
                .append(event.toState())
                .append(event.createdByName() == null ? "" : "  (" + event.createdByName() + ")")
                .append(event.detail() == null || event.detail().isBlank() ? "" : "\n    " + event.detail())
                .append('\n');
        }
        var area = new JTextArea(text.toString(), 14, 60);
        area.setEditable(false);
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(area),
                t("nav.history") + " — " + selected.invoiceNumber(), JOptionPane.PLAIN_MESSAGE);
    }

    private NavReport selected() {
        int index = queue.getSelectedRow();
        if (index < 0 || index >= shown.size()) {
            JOptionPane.showMessageDialog(this, t("nav.select_row"), t("nav.title"),
                    JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return shown.get(queue.convertRowIndexToModel(index));
    }

    private void renderQueue() {
        shown = ctx.getNavReportDao().findAll();
        queueModel.setRowCount(0);
        for (NavReport report : shown) {
            queueModel.addRow(new Object[]{
                    report.invoiceNumber(),
                    report.partnerName(),
                    report.operation().name(),
                    stateText(report.state()),
                    report.transactionId() == null ? "—" : report.transactionId(),
                    report.message() == null ? "" : report.message()});
        }
    }

    private String stateText(NavReportState state) {
        return t("nav.state." + state.name().toLowerCase(java.util.Locale.ROOT));
    }

    private String t(String key, Object... args) {
        return ctx.getI18n().t(key, args);
    }
}
