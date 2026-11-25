package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.NavDemoSettings;
import hu.due.rewear.model.NavDemoSettings.Environment;
import hu.due.rewear.model.NavDemoSettings.Field;

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

/** NAV V3 configuration and local invoice workflow demonstration. */
final class NavIntegrationPanel extends JPanel {
    private final AppContext ctx;
    private final JPanel configCard = new JPanel(new BorderLayout(0, 8));
    private final JPanel resultsCard = new JPanel(new BorderLayout(0, 8));
    private final JPanel form = new JPanel(new GridBagLayout());
    private final java.util.Map<JLabel, String> labels = new java.util.LinkedHashMap<>();
    private final EnumMap<Field, JTextField> fields = new EnumMap<>(Field.class);
    private final JComboBox<Environment> environment = new JComboBox<>(Environment.values());
    private final JComboBox<String> softwareOperation = new JComboBox<>(new String[]{"LOCAL_SOFTWARE", "ONLINE_SERVICE"});
    private final JTextField endpoint = new JTextField(25);
    private final JPasswordField password = new JPasswordField(20);
    private final JPasswordField signingKey = new JPasswordField(20);
    private final JPasswordField exchangeKey = new JPasswordField(20);
    private final JCheckBox outgoing = new JCheckBox();
    private final JCheckBox incoming = new JCheckBox();
    private final JLabel configTitle = new JLabel();
    private final JLabel resultsTitle = new JLabel();
    private final JLabel status = new JLabel(" ");
    private final JTextArea notes = new JTextArea(4, 28);
    private final JButton loadDemo = new JButton();
    private final JButton save = new JButton();
    private final JButton runDemo = new JButton();
    private final javax.swing.table.DefaultTableModel resultsModel = BrutalistStyle.nonEditableTableModel();
    private final JTable results = new JTable(resultsModel) {
        @Override public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            int column = columnAtPoint(event.getPoint());
            return row < 0 || column < 0 ? null : String.valueOf(getValueAt(row, column));
        }
    };
    private int row;

    NavIntegrationPanel(AppContext ctx) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        endpoint.setEditable(false);
        addField("navdemo.environment", environment);
        addField("navdemo.endpoint", endpoint);
        addText(Field.TAX_NUMBER);
        addText(Field.LOGIN);
        addField("navdemo.password", password);
        addField("navdemo.signing_key", signingKey);
        addField("navdemo.exchange_key", exchangeKey);
        addText(Field.SOFTWARE_ID);
        addText(Field.SOFTWARE_NAME);
        addText(Field.SOFTWARE_VERSION);
        addField("navdemo.software_operation", softwareOperation);
        addText(Field.DEVELOPER);
        addText(Field.DEVELOPER_CONTACT);
        addText(Field.DEVELOPER_COUNTRY);
        addText(Field.DEVELOPER_TAX);
        addField("navdemo.outbound", outgoing);
        addField("navdemo.inbound", incoming);
        outgoing.setOpaque(false);
        incoming.setOpaque(false);
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
        var scroll = new JScrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        configCard.add(scroll, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actions.setOpaque(false);
        for (var button : List.of(loadDemo, save, runDemo)) actions.add(button);
        var footer = new JPanel(new BorderLayout(0, 6));
        footer.setOpaque(false);
        footer.add(actions, BorderLayout.NORTH);
        footer.add(status, BorderLayout.SOUTH);
        configCard.add(footer, BorderLayout.SOUTH);
        resultsCard.add(resultsTitle, BorderLayout.NORTH);
        var tableScroll = new JScrollPane(results);
        tableScroll.setPreferredSize(new Dimension(250, 160));
        resultsCard.add(tableScroll, BorderLayout.CENTER);
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, configCard, resultsCard);
        split.setResizeWeight(0.7);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);

        environment.addActionListener(e -> updateEndpoint());
        loadDemo.addActionListener(e -> {
            load(new NavDemoSettings(NavDemoSettings.defaults()));
            status.setText(t("navdemo.defaults_loaded"));
            renderInvoices(false);
        });
        save.addActionListener(e -> run(true));
        runDemo.addActionListener(e -> run(false));
        load(ctx.getSettingsDao().getNavDemoSettings());
    }

    private void addText(Field field) {
        var input = new JTextField(20);
        fields.put(field, input);
        addField("navdemo." + field.key(), input);
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

    private void load(NavDemoSettings saved) {
        fields.forEach((key, field) -> field.setText(saved.value(key)));
        environment.setSelectedItem("PRODUCTION".equals(saved.value(Field.ENVIRONMENT)) ? Environment.PRODUCTION : Environment.TEST);
        softwareOperation.setSelectedItem(saved.value(Field.SOFTWARE_OPERATION));
        outgoing.setSelected(Boolean.parseBoolean(saved.value(Field.OUTBOUND)));
        incoming.setSelected(Boolean.parseBoolean(saved.value(Field.INBOUND)));
        boolean sample = saved.value(Field.LOGIN).equals(NavDemoSettings.defaults().get(Field.LOGIN));
        password.setText(sample ? "DemoPassword2026" : "");
        signingKey.setText(sample ? "DEMO-SIGN-KEY-00000000000000" : "");
        exchangeKey.setText(sample ? "DEMOEXCHANGEKEY0" : "");
        updateEndpoint();
    }

    private void updateEndpoint() {
        endpoint.setText(((Environment) environment.getSelectedItem()).endpoint());
        endpoint.setToolTipText(endpoint.getText());
        endpoint.setCaretPosition(0);
    }

    void applyTranslations() {
        configTitle.setText(t("navdemo.title"));
        notes.setText(t("navdemo.note"));
        notes.setCaretPosition(0);
        resultsTitle.setText(t("navdemo.results"));
        labels.forEach((label, key) -> label.setText(t(key)));
        loadDemo.setText(t("navdemo.load_demo"));
        save.setText(t("common.save"));
        runDemo.setText(t("navdemo.run"));
        resultsModel.setColumnIdentifiers(new String[]{t("invoice.number"), t("invoice.direction"),
                t("invoice.partner"), t("navdemo.operation"), t("common.status")});
    }

    void applyTheme() {
        BrutalistStyle.applyBoxCard(configCard, 10, 12);
        BrutalistStyle.applyBoxCard(resultsCard, 10, 12);
        BrutalistStyle.stylePrimaryButton(runDemo);
        BrutalistStyle.styleSecondaryButtons(loadDemo, save);
        BrutalistStyle.styleTable(results);
        notes.setBackground(ThemeManager.bg());
        notes.setForeground(ThemeManager.muted());
        status.setForeground(ThemeManager.fg());
    }

    void refreshData() {
        status.setText(" ");
        renderInvoices(false);
    }

    private void run(boolean saveChanges) {
        try {
            var values = new EnumMap<Field, String>(Field.class);
            fields.forEach((key, field) -> values.put(key, field.getText().strip()));
            values.put(Field.ENVIRONMENT, ((Environment) environment.getSelectedItem()).name());
            values.put(Field.SOFTWARE_OPERATION, (String) softwareOperation.getSelectedItem());
            values.put(Field.OUTBOUND, Boolean.toString(outgoing.isSelected()));
            values.put(Field.INBOUND, Boolean.toString(incoming.isSelected()));
            validate(values, !saveChanges);
            if (saveChanges) ctx.getSettingsDao().saveNavDemoSettings(new NavDemoSettings(values));
            status.setText(t(saveChanges ? "navdemo.saved" : "navdemo.simulated"));
            renderInvoices(!saveChanges);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void validate(EnumMap<Field, String> values, boolean checkCredentials) {
        for (var field : List.of(Field.TAX_NUMBER, Field.LOGIN, Field.SOFTWARE_ID, Field.SOFTWARE_NAME,
                Field.SOFTWARE_VERSION, Field.DEVELOPER, Field.DEVELOPER_CONTACT)) {
            if (values.get(field).isBlank()) throw new IllegalArgumentException(t("navdemo.required", t("navdemo." + field.key())));
        }
        check(values.get(Field.TAX_NUMBER).matches("[0-9]{8}"), "navdemo.tax_format");
        check(values.get(Field.SOFTWARE_ID).matches("[A-Z0-9-]{18}"), "navdemo.software_format");
        check(values.get(Field.DEVELOPER_COUNTRY).matches("[A-Z]{2}|"), "navdemo.country_format");
        if (checkCredentials) {
            check(hasSecret(password) && hasSecret(signingKey) && hasSecret(exchangeKey), "navdemo.credentials_required");
            check(outgoing.isSelected() || incoming.isSelected(), "navdemo.workflow_required");
        }
    }

    private void check(boolean valid, String key) {
        if (!valid) throw new IllegalArgumentException(t(key));
    }

    private static boolean hasSecret(JPasswordField field) {
        char[] value = field.getPassword();
        try {
            for (char ch : value) if (!Character.isWhitespace(ch)) return true;
            return false;
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    private void renderInvoices(boolean simulated) {
        resultsModel.setRowCount(0);
        var invoices = ctx.getInvoiceDao().findAll();
        var directions = new java.util.HashMap<String, InvoiceDirection>();
        invoices.forEach(invoice -> directions.put(invoice.invoiceNumber(), invoice.direction()));
        for (var invoice : invoices) {
            boolean credit = invoice.category() == InvoiceCategory.CREDIT_NOTE;
            InvoiceDirection direction = credit ? findCreditDirection(invoice.id(), directions) : invoice.direction();
            boolean outbound = direction == InvoiceDirection.OUTGOING;
            boolean selected = outbound ? outgoing.isSelected() : incoming.isSelected();
            String state = direction == null ? "navdemo.credit_unlinked" : invoice.status() == InvoiceStatus.DRAFT ? "navdemo.draft"
                    : simulated && selected ? (outbound ? "navdemo.demo_report" : "navdemo.demo_lookup") : "navdemo.pending";
            String operation = direction == null ? "—" : outbound
                    ? "tokenExchange / " + (credit ? "manageInvoice (STORNO)" : "manageInvoice (CREATE)") + " / queryTransactionStatus"
                    : "queryInvoiceDigest / queryInvoiceData";
            resultsModel.addRow(new Object[]{invoice.invoiceNumber(), direction == null ? "—" : t("invoice.direction." + direction),
                    invoice.partnerName(), operation, t(state)});
        }
    }

    private InvoiceDirection findCreditDirection(long invoiceId, java.util.Map<String, InvoiceDirection> originals) {
        String prefix = "CREDIT NOTE [Ref: ";
        for (var line : ctx.getInvoiceDao().findItemsByInvoiceId(invoiceId)) {
            String description = line.description();
            if (description != null && description.startsWith(prefix)) {
                int end = description.indexOf(']', prefix.length());
                if (end > prefix.length()) return originals.get(description.substring(prefix.length(), end));
            }
        }
        return null;
    }

    private String t(String key, Object... args) { return ctx.getI18n().t(key, args); }
}
