package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.model.CompanyProfile;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.TaxType;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Seller details, payment accounts and invoice reference data in Admin. */
final class InvoiceSettingsPanel extends JPanel {
    private final AppContext ctx;
    private final SettingsStack stack = new SettingsStack();
    private final JLabel title = new JLabel();
    private final JPanel companyCard = new JPanel(new BorderLayout(0, 8));
    private final JLabel companyTitle = new JLabel();
    private final JLabel ibanTitle = new JLabel();
    private final Map<JLabel, String> companyLabels = new LinkedHashMap<>();
    private final JTextField companyName = new JTextField(24);
    private final JTextField companyTax = new JTextField(24);
    private final JTextField street = new JTextField(24);
    private final JTextField city = new JTextField(24);
    private final JTextField postalCode = new JTextField(24);
    private final JTextField companyEmail = new JTextField(24);
    private final JPanel ibanRows = new JPanel(new GridBagLayout());
    private final Map<String, JTextField> ibanFields = new LinkedHashMap<>();
    private final JButton saveCompany = new JButton();

    private final JLabel taxTitle = new JLabel();
    private final JLabel currencyTitle = new JLabel();
    private final JButton addTax = new JButton();
    private final JButton addCurrency = new JButton();
    private final DefaultTableModel taxModel = BrutalistStyle.nonEditableTableModel();
    private final DefaultTableModel currencyModel = BrutalistStyle.nonEditableTableModel();
    private final JTable taxTable = new JTable(taxModel);
    private final JTable currencyTable = new JTable(currencyModel);
    private final JPanel taxCard = tableCard(taxTitle, taxTable, addTax);
    private final JPanel currencyCard = tableCard(currencyTitle, currencyTable, addCurrency);
    private List<TaxType> taxTypes = List.of();
    private List<CurrencyDef> currencies = List.of();

    InvoiceSettingsPanel(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        title.setFont(ThemeManager.monoFont(Font.BOLD, 15f));
        title.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        companyTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        ibanTitle.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        ibanRows.setOpaque(false);
        companyEmail.setEditable(ctx.getSession().isSuperAdmin());

        var companyForm = new JPanel(new GridBagLayout());
        companyForm.setOpaque(false);
        addCompanyField(companyForm, "customers.company", companyName);
        addCompanyField(companyForm, "invoice.tax_number", companyTax);
        addCompanyField(companyForm, "invoice.settings.street", street);
        addCompanyField(companyForm, "invoice.settings.city", city);
        addCompanyField(companyForm, "invoice.settings.postal_code", postalCode);
        addCompanyField(companyForm, "invoice.email", companyEmail);

        var banking = new JPanel(new BorderLayout(0, 4));
        banking.setOpaque(false);
        banking.add(ibanTitle, BorderLayout.NORTH);
        banking.add(ibanRows, BorderLayout.CENTER);
        var body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(companyForm, BorderLayout.NORTH);
        body.add(banking, BorderLayout.CENTER);
        companyCard.add(companyTitle, BorderLayout.NORTH);
        companyCard.add(body, BorderLayout.CENTER);
        companyCard.add(actions(saveCompany), BorderLayout.SOUTH);

        for (JComponent part : List.of(title, companyCard, taxCard, currencyCard)) {
            part.setAlignmentX(LEFT_ALIGNMENT);
            stack.add(part);
            stack.add(Box.createVerticalStrut(10));
        }
        var scroll = new JScrollPane(stack, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        saveCompany.addActionListener(e -> saveCompany());
        addTax.addActionListener(e -> openTax(null));
        addCurrency.addActionListener(e -> openCurrency(null));
        activateRow(taxTable, () -> {
            int row = taxTable.getSelectedRow();
            if (row >= 0) openTax(taxTypes.get(taxTable.convertRowIndexToModel(row)));
        });
        activateRow(currencyTable, () -> {
            int row = currencyTable.getSelectedRow();
            if (row >= 0) openCurrency(currencies.get(currencyTable.convertRowIndexToModel(row)));
        });
    }

    private static final class SettingsStack extends JPanel implements Scrollable {
        SettingsStack() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(24, visible.height - 24);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static JPanel tableCard(JLabel heading, JTable table, JButton add) {
        var card = new JPanel(new BorderLayout(0, 8));
        heading.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        var scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(380, 194));
        card.add(heading, BorderLayout.NORTH);
        card.add(scroll, BorderLayout.CENTER);
        card.add(actions(add), BorderLayout.SOUTH);
        return card;
    }

    private static JPanel actions(JButton button) {
        var row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        row.setOpaque(false);
        row.add(button);
        return row;
    }

    private static void activateRow(JTable table, Runnable open) {
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    table.setRowSelectionInterval(row, row);
                    open.run();
                }
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ENTER"), "openRow");
        table.getActionMap().put("openRow", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { open.run(); }
        });
    }

    private void addCompanyField(JPanel form, String key, JTextField field) {
        var label = new JLabel();
        addRow(form, companyLabels.size(), label, field);
        companyLabels.put(label, key);
    }

    private static void addRow(JPanel form, int row, JLabel label, JComponent field) {
        var grid = new GridBagConstraints();
        grid.gridy = row;
        grid.insets = new Insets(3, 0, 3, 10);
        grid.anchor = GridBagConstraints.LINE_START;
        label.setLabelFor(field);
        form.add(label, grid);
        grid.gridx = 1;
        grid.weightx = 1;
        grid.fill = GridBagConstraints.HORIZONTAL;
        grid.insets = new Insets(3, 0, 3, 0);
        form.add(field, grid);
    }

    void refreshData() {
        var profile = ctx.getSettingsDao().getCompanyProfile();
        setField(companyName, profile.name());
        setField(companyTax, profile.taxNumber());
        setField(street, profile.addressParts().street());
        setField(city, profile.addressParts().city());
        setField(postalCode, profile.addressParts().postalCode());
        setField(companyEmail, profile.email());
        refreshLists();
        rebuildIbans(profile.ibansByCurrency());
    }

    private static void setField(JTextField field, String value) {
        field.setText(value);
        field.setCaretPosition(0);
    }

    private void refreshLists() {
        taxTypes = ctx.getFinanceConfigDao().findTaxTypes(false);
        currencies = ctx.getFinanceConfigDao().findCurrencies(false);
        taxModel.setRowCount(0);
        for (TaxType type : taxTypes) {
            taxModel.addRow(new Object[]{type.code(), type.nameEn(), type.nameHu(), type.rateDisplay(),
                    t("invoice.settings.scope." + type.countryScope()), yesNo(type.active())});
        }
        currencyModel.setRowCount(0);
        for (CurrencyDef currency : currencies) {
            currencyModel.addRow(new Object[]{currency.code(), currency.nameEn(), currency.nameHu(), currency.symbol(),
                    currency.decimalPlaces(), yesNo(currency.active())});
        }
    }

    private void rebuildIbans(Map<String, String> values) {
        ibanFields.clear();
        ibanRows.removeAll();
        int row = 0;
        for (CurrencyDef currency : currencies) {
            var field = new JTextField(24);
            setField(field, values.getOrDefault(currency.code(), ""));
            addRow(ibanRows, row++, new JLabel(currency.code()), field);
            ibanFields.put(currency.code(), field);
        }
        ibanRows.revalidate();
        ibanRows.repaint();
    }

    private Map<String, String> readIbans() {
        Map<String, String> values = new LinkedHashMap<>();
        ibanFields.forEach((code, field) -> values.put(code, field.getText().strip()));
        return values;
    }

    private void saveCompany() {
        try {
            if (companyName.getText().isBlank()) throw new IllegalArgumentException(t("validation.company_required"));
            var address = new CompanyProfile.Address(street.getText(), city.getText(), postalCode.getText());
            String email = ctx.getSession().isSuperAdmin() ? companyEmail.getText().strip() : null;
            if (email != null && !email.matches("[^\\s,;@]+@[^\\s,;@]+\\.[^\\s,;@]+")) {
                throw new IllegalArgumentException(t("invoice.settings.invalid_email"));
            }
            ctx.getSettingsDao().saveInvoiceCompanyProfile(companyName.getText(), address, companyTax.getText(), email, readIbans());
            JOptionPane.showMessageDialog(this, t("invoice.settings.company_saved"), t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException ex) {
            showError(ex);
        }
    }

    private void openTax(TaxType existing) {
        var form = new JPanel(new GridBagLayout());
        var code = editorField(form, 0, "common.code", existing == null ? "" : existing.code());
        var en = editorField(form, 1, "common.name.en", existing == null ? "" : existing.nameEn());
        var hu = editorField(form, 2, "common.name.hu", existing == null ? "" : existing.nameHu());
        var rate = editorField(form, 3, "invoice.settings.tax_rate", existing == null ? "0" : existing.ratePercent().stripTrailingZeros().toPlainString());
        var scopeCodes = List.of("HU", "EU", "NON_EU", "ANY");
        var scopes = new JComboBox<>(scopeCodes.stream().map(key -> t("invoice.settings.scope." + key)).toArray(String[]::new));
        if (existing != null && scopeCodes.contains(existing.countryScope())) scopes.setSelectedIndex(scopeCodes.indexOf(existing.countryScope()));
        addRow(form, 4, new JLabel(t("invoice.settings.country_scope")), scopes);
        JCheckBox active = activeControl(form, 5, existing == null || existing.active(), existing != null);
        if (existing != null && ctx.getFinanceConfigDao().isTaxTypeReferenced(existing.code())) code.setEditable(false);
        editRecord(form, t("invoice.settings.taxes"), () -> {
            requireNames(en, hu);
            var value = CurrencyCode.parseAmount(rate.getText());
            var scope = scopeCodes.get(scopes.getSelectedIndex());
            if (existing == null) ctx.getFinanceConfigDao().insertTaxType(code.getText(), en.getText(), hu.getText(), value, scope);
            else ctx.getFinanceConfigDao().updateTaxType(existing.id(), code.getText(), en.getText(), hu.getText(), value, scope, active.isSelected());
        }, existing == null ? null : () -> deleteRecord(existing.code(), () -> ctx.getFinanceConfigDao().isTaxTypeReferenced(existing.code()),
                () -> ctx.getFinanceConfigDao().deleteTaxType(existing.id())));
    }

    private void openCurrency(CurrencyDef existing) {
        var form = new JPanel(new GridBagLayout());
        var code = editorField(form, 0, "common.code", existing == null ? "" : existing.code());
        var en = editorField(form, 1, "common.name.en", existing == null ? "" : existing.nameEn());
        var hu = editorField(form, 2, "common.name.hu", existing == null ? "" : existing.nameHu());
        var symbol = editorField(form, 3, "invoice.settings.symbol", existing == null ? "" : existing.symbol());
        var decimals = new JComboBox<>(new Integer[]{2, 0});
        decimals.setSelectedItem(existing == null ? 2 : existing.decimalPlaces());
        addRow(form, 4, new JLabel(t("invoice.settings.decimals")), decimals);
        JCheckBox active = activeControl(form, 5, existing == null || existing.active(), existing != null);
        if (existing != null && ctx.getFinanceConfigDao().isCurrencyReferenced(existing.code())) code.setEditable(false);
        editRecord(form, t("invoice.settings.currencies"), () -> {
            requireNames(en, hu);
            int places = (Integer) decimals.getSelectedItem();
            if (existing == null) ctx.getFinanceConfigDao().insertCurrency(code.getText(), en.getText(), hu.getText(), symbol.getText(), places);
            else ctx.getFinanceConfigDao().updateCurrency(existing.id(), code.getText(), en.getText(), hu.getText(), symbol.getText(), places, active.isSelected());
        }, existing == null ? null : () -> deleteRecord(existing.code(), () -> ctx.getFinanceConfigDao().isCurrencyReferenced(existing.code()),
                () -> ctx.getFinanceConfigDao().deleteCurrency(existing.id())));
    }

    private JTextField editorField(JPanel form, int row, String key, String value) {
        var field = new JTextField(23);
        setField(field, value);
        addRow(form, row, new JLabel(t(key)), field);
        return field;
    }

    private JCheckBox activeControl(JPanel form, int row, boolean active, boolean editing) {
        var input = new JCheckBox(t("invoice.settings.active"), active);
        if (editing) addRow(form, row, new JLabel(t("common.status")), input);
        return input;
    }

    private void editRecord(JPanel form, String heading, Runnable save, BooleanSupplier delete) {
        Object[] buttons = delete == null ? new Object[]{t("common.save"), t("common.cancel")}
                : new Object[]{t("common.save"), t("common.cancel"), t("common.delete")};
        while (true) {
            int action = JOptionPane.showOptionDialog(this, form, heading, JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE, null, buttons, buttons[0]);
            try {
                if (action == 0) save.run();
                else if (action == 2 && delete != null) {
                    if (!delete.getAsBoolean()) continue;
                } else return;
                var pendingIbans = new LinkedHashMap<>(ctx.getSettingsDao().getCompanyProfile().ibansByCurrency());
                pendingIbans.putAll(readIbans());
                refreshLists();
                rebuildIbans(pendingIbans);
                return;
            } catch (RuntimeException ex) {
                showError(ex);
            }
        }
    }

    private boolean deleteRecord(String code, BooleanSupplier referenced, Runnable delete) {
        if (referenced.getAsBoolean()) {
            JOptionPane.showMessageDialog(this, t("invoice.settings.in_use", code), t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (JOptionPane.showConfirmDialog(this, t("dictionaries.delete_confirm", code), t("common.confirm"),
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return false;
        delete.run();
        return true;
    }

    private void requireNames(JTextField en, JTextField hu) {
        if (en.getText().isBlank() || hu.getText().isBlank()) throw new IllegalArgumentException(t("validation.names_bilingual_required"));
    }

    void applyTranslations() {
        title.setText(t("invoice.tab.settings").toUpperCase(Locale.ROOT));
        companyTitle.setText(t("invoice.settings.company").toUpperCase(Locale.ROOT));
        ibanTitle.setText(t("invoice.settings.iban"));
        companyLabels.forEach((label, key) -> label.setText(t(key)));
        saveCompany.setText(t("common.save").toUpperCase(Locale.ROOT));
        taxTitle.setText(t("invoice.settings.taxes").toUpperCase(Locale.ROOT));
        currencyTitle.setText(t("invoice.settings.currencies").toUpperCase(Locale.ROOT));
        addTax.setText("+ " + t("common.add").toUpperCase(Locale.ROOT));
        addCurrency.setText(addTax.getText());
        taxModel.setColumnIdentifiers(columns("common.code", "common.name.en", "common.name.hu", "invoice.settings.tax_rate",
                "invoice.settings.country_scope", "common.status"));
        currencyModel.setColumnIdentifiers(columns("common.code", "common.name.en", "common.name.hu", "invoice.settings.symbol",
                "invoice.settings.decimals", "common.status"));
    }

    void applyTheme() {
        stack.setBackground(ThemeManager.bg());
        for (JPanel card : List.of(companyCard, taxCard, currencyCard)) BrutalistStyle.applyBoxCard(card, 10, 12);
        for (JLabel label : List.of(title, companyTitle, ibanTitle, taxTitle, currencyTitle)) label.setForeground(ThemeManager.fg());
        BrutalistStyle.stylePrimaryButton(saveCompany);
        BrutalistStyle.stylePrimaryButton(addTax);
        BrutalistStyle.stylePrimaryButton(addCurrency);
        BrutalistStyle.styleTable(taxTable);
        BrutalistStyle.styleTable(currencyTable);
    }

    private String[] columns(String... keys) {
        return java.util.Arrays.stream(keys).map(key -> t(key).toUpperCase(Locale.ROOT)).toArray(String[]::new);
    }

    private String yesNo(boolean value) { return t(value ? "common.yes" : "common.no").toUpperCase(Locale.ROOT); }
    private String t(String key, Object... args) { return ctx.getI18n().t(key, args); }
    private void showError(RuntimeException ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), t("common.error"), JOptionPane.ERROR_MESSAGE); }
}
