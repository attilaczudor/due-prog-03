package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CompanyProfile;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.TaxType;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tab 2 of {@link InvoicesView}: dedicated Invoice Settings panel for managing
 * the editable Seller Company Profile &amp; per-currency Bank IBANs ({@code app_settings}),
 * configurable VAT / Tax Types ({@code tax_types}), and operating Currencies ({@code currencies}).
 * Notifies the host view whenever configuration changes so ledger buttons and dropdowns stay in sync.
 */
final class InvoiceSettingsPanel extends JPanel {

    private final AppContext ctx;
    private final Runnable onConfigChanged;

    // Company Profile & Per-Currency Bank IBANs section
    private final JPanel companyCard = new JPanel(new BorderLayout(0, 8));
    private final JLabel companySectionTitle = new JLabel();
    private final JLabel companyNameLbl = new JLabel();
    private final JLabel companyTaxLbl = new JLabel();
    private final JLabel companyAddressLbl = new JLabel();
    private final JLabel companyEmailLbl = new JLabel();
    private final JLabel ibanCurrencyLbl = new JLabel();
    private final JLabel ibanValueLbl = new JLabel();
    private final JTextField companyNameField = new JTextField(22);
    private final JTextField companyTaxField = new JTextField(18);
    private final JTextField companyAddressField = new JTextField(24);
    private final JTextField companyEmailField = new JTextField(20);
    private final JComboBox<String> ibanCurrencyCombo = new JComboBox<>();
    private final JTextField ibanField = new JTextField(26);
    private final JButton saveCompanyBtn = new JButton();
    private final Map<String, String> draftIbans = new LinkedHashMap<>();
    private String activeIbanCurrency = "HUF";
    private boolean loadingCompany = false;

    // Tax Types section
    private final JPanel taxCard = new JPanel(new BorderLayout(0, 8));
    private final JLabel taxSectionTitle = new JLabel();
    private final JButton addTaxBtn = new JButton();
    private final JButton editTaxBtn = new JButton();
    private final JButton toggleTaxBtn = new JButton();
    private final JButton deleteTaxBtn = new JButton();
    private final DefaultTableModel taxModel = BrutalistStyle.nonEditableTableModel();
    private final JTable taxTable = new JTable(taxModel);

    // Currencies section
    private final JPanel currencyCard = new JPanel(new BorderLayout(0, 8));
    private final JLabel currencySectionTitle = new JLabel();
    private final JButton addCurrencyBtn = new JButton();
    private final JButton editCurrencyBtn = new JButton();
    private final JButton toggleCurrencyBtn = new JButton();
    private final JButton deleteCurrencyBtn = new JButton();
    private final DefaultTableModel currencyModel = BrutalistStyle.nonEditableTableModel();
    private final JTable currencyTable = new JTable(currencyModel);

    private List<TaxType> allTaxTypes = List.of();
    private List<CurrencyDef> allCurrencies = List.of();

    private record ScopeOption(String code, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    InvoiceSettingsPanel(AppContext ctx, Runnable onConfigChanged) {
        this.ctx = ctx;
        this.onConfigChanged = onConfigChanged;

        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

        buildUi();
        wireEvents();
    }

    private void buildUi() {
        // North Card: Seller Company Profile & Per-Currency Bank IBANs
        JPanel compHeader = new JPanel(new BorderLayout(8, 0));
        compHeader.setOpaque(false);
        companySectionTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        compHeader.add(companySectionTitle, BorderLayout.WEST);
        compHeader.add(saveCompanyBtn, BorderLayout.EAST);
        companyCard.add(compHeader, BorderLayout.NORTH);

        JPanel compGrid = new JPanel(new GridBagLayout());
        compGrid.setOpaque(false);
        GridBagConstraints cg = new GridBagConstraints();
        cg.insets = new Insets(3, 4, 3, 8);
        cg.fill = GridBagConstraints.HORIZONTAL;
        cg.anchor = GridBagConstraints.WEST;

        // Row 0: Company Name | Tax Number
        cg.gridx = 0; cg.gridy = 0; cg.weightx = 0; compGrid.add(companyNameLbl, cg);
        cg.gridx = 1; cg.weightx = 0.5; compGrid.add(companyNameField, cg);
        cg.gridx = 2; cg.weightx = 0; compGrid.add(companyTaxLbl, cg);
        cg.gridx = 3; cg.weightx = 0.5; compGrid.add(companyTaxField, cg);

        // Row 1: Address | Email
        cg.gridx = 0; cg.gridy = 1; cg.weightx = 0; compGrid.add(companyAddressLbl, cg);
        cg.gridx = 1; cg.weightx = 0.5; compGrid.add(companyAddressField, cg);
        cg.gridx = 2; cg.weightx = 0; compGrid.add(companyEmailLbl, cg);
        cg.gridx = 3; cg.weightx = 0.5; compGrid.add(companyEmailField, cg);

        // Row 2: Bank Account Currency selector | IBAN
        cg.gridx = 0; cg.gridy = 2; cg.weightx = 0; compGrid.add(ibanCurrencyLbl, cg);
        cg.gridx = 1; cg.weightx = 0.5; compGrid.add(ibanCurrencyCombo, cg);
        cg.gridx = 2; cg.weightx = 0; compGrid.add(ibanValueLbl, cg);
        cg.gridx = 3; cg.weightx = 0.5; compGrid.add(ibanField, cg);

        companyCard.add(compGrid, BorderLayout.CENTER);
        add(companyCard, BorderLayout.NORTH);

        // Top Card: VAT / Tax Types
        JPanel taxHeader = new JPanel(new BorderLayout(8, 0));
        taxHeader.setOpaque(false);
        taxSectionTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        taxHeader.add(taxSectionTitle, BorderLayout.WEST);

        JPanel taxBtns = new JPanel(new WrapLayout(FlowLayout.RIGHT, 6, 2));
        taxBtns.setOpaque(false);
        taxBtns.add(addTaxBtn);
        taxBtns.add(editTaxBtn);
        taxBtns.add(toggleTaxBtn);
        taxBtns.add(deleteTaxBtn);
        taxHeader.add(taxBtns, BorderLayout.CENTER);

        taxCard.add(taxHeader, BorderLayout.NORTH);
        taxTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        BrutalistStyle.styleTable(taxTable);
        taxCard.add(new JScrollPane(taxTable), BorderLayout.CENTER);

        // Bottom Card: Operating Currencies
        JPanel curHeader = new JPanel(new BorderLayout(8, 0));
        curHeader.setOpaque(false);
        currencySectionTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        curHeader.add(currencySectionTitle, BorderLayout.WEST);

        JPanel curBtns = new JPanel(new WrapLayout(FlowLayout.RIGHT, 6, 2));
        curBtns.setOpaque(false);
        curBtns.add(addCurrencyBtn);
        curBtns.add(editCurrencyBtn);
        curBtns.add(toggleCurrencyBtn);
        curBtns.add(deleteCurrencyBtn);
        curHeader.add(curBtns, BorderLayout.CENTER);

        currencyCard.add(curHeader, BorderLayout.NORTH);
        currencyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        BrutalistStyle.styleTable(currencyTable);
        currencyCard.add(new JScrollPane(currencyTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, taxCard, currencyCard);
        split.setResizeWeight(0.55);
        split.setDividerLocation(240);
        add(split, BorderLayout.CENTER);
    }

    private void wireEvents() {
        ibanCurrencyCombo.addActionListener(e -> {
            if (loadingCompany) return;
            // Save typed IBAN for previous currency into draft map before switching
            if (activeIbanCurrency != null && !activeIbanCurrency.isBlank()) {
                draftIbans.put(activeIbanCurrency, ibanField.getText().trim());
            }
            Object sel = ibanCurrencyCombo.getSelectedItem();
            if (sel != null) {
                activeIbanCurrency = sel.toString();
                ibanField.setText(draftIbans.getOrDefault(activeIbanCurrency, ""));
            }
        });
        saveCompanyBtn.addActionListener(e -> handleSaveCompanyProfile());

        addTaxBtn.addActionListener(e -> openTaxDialog(null));
        editTaxBtn.addActionListener(e -> {
            int r = taxTable.getSelectedRow();
            if (r >= 0 && r < allTaxTypes.size()) {
                openTaxDialog(allTaxTypes.get(r));
            }
        });
        toggleTaxBtn.addActionListener(e -> handleToggleTax());
        deleteTaxBtn.addActionListener(e -> handleDeleteTax());

        addCurrencyBtn.addActionListener(e -> openCurrencyDialog(null));
        editCurrencyBtn.addActionListener(e -> {
            int r = currencyTable.getSelectedRow();
            if (r >= 0 && r < allCurrencies.size()) {
                openCurrencyDialog(allCurrencies.get(r));
            }
        });
        toggleCurrencyBtn.addActionListener(e -> handleToggleCurrency());
        deleteCurrencyBtn.addActionListener(e -> handleDeleteCurrency());
    }

    // --- Tax Type CRUD ---

    private void openTaxDialog(TaxType existing) {
        I18nManager i18n = ctx.getI18n();
        JTextField codeF = new JTextField(existing != null ? existing.code() : "", 14);
        JTextField enF = new JTextField(existing != null ? existing.nameEn() : "", 22);
        JTextField huF = new JTextField(existing != null ? existing.nameHu() : "", 22);
        JTextField rateF = new JTextField(
                existing != null ? existing.ratePercent().stripTrailingZeros().toPlainString() : "0", 10);

        JComboBox<ScopeOption> scopeCombo = new JComboBox<>(new ScopeOption[]{
                new ScopeOption("HU", i18n.t("invoice.settings.scope.HU")),
                new ScopeOption("EU", i18n.t("invoice.settings.scope.EU")),
                new ScopeOption("NON_EU", i18n.t("invoice.settings.scope.NON_EU")),
                new ScopeOption("ANY", i18n.t("invoice.settings.scope.ANY"))
        });
        if (existing != null) {
            for (int i = 0; i < scopeCombo.getItemCount(); i++) {
                if (scopeCombo.getItemAt(i).code().equalsIgnoreCase(existing.countryScope())) {
                    scopeCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        JCheckBox activeBox = new JCheckBox(i18n.t("common.activate"), existing == null || existing.active());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("common.code") + " *:", codeF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("common.name.en") + " *:", enF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("common.name.hu") + " *:", huF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.settings.tax_rate") + " *:", rateF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.settings.country_scope") + ":", scopeCombo);
        if (existing != null) {
            gbc.gridx = 1;
            gbc.gridy = r;
            form.add(activeBox, gbc);
        }

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("invoice.settings.taxes"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            BigDecimal rate = CurrencyCode.parseAmount(rateF.getText());
            ScopeOption sc = (ScopeOption) scopeCombo.getSelectedItem();
            String scope = sc != null ? sc.code() : "ANY";
            if (enF.getText().trim().isEmpty() || huF.getText().trim().isEmpty()) {
                throw new IllegalArgumentException(i18n.t("validation.names_bilingual_required"));
            }
            if (existing == null) {
                ctx.getFinanceConfigDao().insertTaxType(codeF.getText(), enF.getText(), huF.getText(), rate, scope);
            } else {
                ctx.getFinanceConfigDao().updateTaxType(existing.id(), codeF.getText(), enF.getText(), huF.getText(),
                        rate, scope, activeBox.isSelected());
            }
            onConfigChanged.run();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleToggleTax() {
        int r = taxTable.getSelectedRow();
        if (r < 0 || r >= allTaxTypes.size()) {
            return;
        }
        TaxType t = allTaxTypes.get(r);
        ctx.getFinanceConfigDao().updateTaxType(t.id(), t.code(), t.nameEn(), t.nameHu(),
                t.ratePercent(), t.countryScope(), !t.active());
        onConfigChanged.run();
    }

    private void handleDeleteTax() {
        int r = taxTable.getSelectedRow();
        if (r < 0 || r >= allTaxTypes.size()) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        TaxType t = allTaxTypes.get(r);
        if (ctx.getFinanceConfigDao().isTaxTypeReferenced(t.code())) {
            JOptionPane.showMessageDialog(this, i18n.t("invoice.settings.in_use", t.code()),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this, i18n.t("dictionaries.delete_confirm", t.code()),
                i18n.t("common.confirm"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            ctx.getFinanceConfigDao().deleteTaxType(t.id());
            onConfigChanged.run();
        }
    }

    // --- Currency CRUD ---

    private void openCurrencyDialog(CurrencyDef existing) {
        I18nManager i18n = ctx.getI18n();
        JTextField codeF = new JTextField(existing != null ? existing.code() : "", 10);
        JTextField enF = new JTextField(existing != null ? existing.nameEn() : "", 22);
        JTextField huF = new JTextField(existing != null ? existing.nameHu() : "", 22);
        JTextField symF = new JTextField(existing != null ? existing.symbol() : "", 8);
        JComboBox<Integer> decCombo = new JComboBox<>(new Integer[]{2, 0});
        decCombo.setSelectedItem(existing != null ? existing.decimalPlaces() : 2);
        JCheckBox activeBox = new JCheckBox(i18n.t("common.activate"), existing == null || existing.active());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("common.code") + " (ISO, e.g. USD) *:", codeF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("common.name.en") + " *:", enF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("common.name.hu") + " *:", huF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.settings.symbol") + " *:", symF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.settings.decimals") + ":", decCombo);
        if (existing != null) {
            gbc.gridx = 1;
            gbc.gridy = r;
            form.add(activeBox, gbc);
        }

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("invoice.settings.currencies"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            if (enF.getText().trim().isEmpty() || huF.getText().trim().isEmpty()) {
                throw new IllegalArgumentException(i18n.t("validation.names_bilingual_required"));
            }
            int dec = decCombo.getSelectedItem() instanceof Integer d ? d : 2;
            if (existing == null) {
                ctx.getFinanceConfigDao().insertCurrency(codeF.getText(), enF.getText(), huF.getText(), symF.getText(), dec);
            } else {
                ctx.getFinanceConfigDao().updateCurrency(existing.id(), codeF.getText(), enF.getText(), huF.getText(),
                        symF.getText(), dec, activeBox.isSelected());
            }
            onConfigChanged.run();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleToggleCurrency() {
        int r = currencyTable.getSelectedRow();
        if (r < 0 || r >= allCurrencies.size()) {
            return;
        }
        CurrencyDef c = allCurrencies.get(r);
        ctx.getFinanceConfigDao().updateCurrency(c.id(), c.code(), c.nameEn(), c.nameHu(),
                c.symbol(), c.decimalPlaces(), !c.active());
        onConfigChanged.run();
    }

    private void handleDeleteCurrency() {
        int r = currencyTable.getSelectedRow();
        if (r < 0 || r >= allCurrencies.size()) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        CurrencyDef c = allCurrencies.get(r);
        if (ctx.getFinanceConfigDao().isCurrencyReferenced(c.code())) {
            JOptionPane.showMessageDialog(this, i18n.t("invoice.settings.in_use", c.code()),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this, i18n.t("dictionaries.delete_confirm", c.code()),
                i18n.t("common.confirm"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            ctx.getFinanceConfigDao().deleteCurrency(c.id());
            onConfigChanged.run();
        }
    }

    // --- Company Profile Persistence ---

    private void handleSaveCompanyProfile() {
        I18nManager i18n = ctx.getI18n();
        try {
            String name = companyNameField.getText().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException(i18n.t("validation.company_required"));
            }
            if (activeIbanCurrency != null && !activeIbanCurrency.isBlank()) {
                draftIbans.put(activeIbanCurrency, ibanField.getText().trim());
            }
            CompanyProfile existing = ctx.getSettingsDao().getCompanyProfile();
            CompanyProfile updated = new CompanyProfile(
                    name,
                    companyAddressField.getText().trim(),
                    companyTaxField.getText().trim(),
                    companyEmailField.getText().trim(),
                    existing.phone(),
                    draftIbans
            );
            ctx.getSettingsDao().saveCompanyProfile(updated);
            JOptionPane.showMessageDialog(this, i18n.t("invoice.settings.company_saved"),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    // --- Refresh, i18n & Theme ---

    void refreshTables() {
        I18nManager i18n = ctx.getI18n();
        String yes = i18n.t("common.yes").toUpperCase(Locale.ROOT);
        String no = i18n.t("common.no").toUpperCase(Locale.ROOT);

        allTaxTypes = ctx.getFinanceConfigDao().findTaxTypes(false);
        taxModel.setRowCount(0);
        for (TaxType t : allTaxTypes) {
            taxModel.addRow(new Object[]{
                    t.code(),
                    t.nameEn(),
                    t.nameHu(),
                    t.rateDisplay(),
                    i18n.t("invoice.settings.scope." + t.countryScope()),
                    t.active() ? yes : no
            });
        }

        allCurrencies = ctx.getFinanceConfigDao().findCurrencies(false);
        currencyModel.setRowCount(0);
        for (CurrencyDef c : allCurrencies) {
            currencyModel.addRow(new Object[]{
                    c.code(),
                    c.nameEn(),
                    c.nameHu(),
                    c.symbol(),
                    Integer.toString(c.decimalPlaces()),
                    c.active() ? yes : no
            });
        }

        // Load Seller Company Profile & per-currency IBANs
        loadingCompany = true;
        try {
            CompanyProfile profile = ctx.getSettingsDao().getCompanyProfile();
            companyNameField.setText(profile.name());
            companyTaxField.setText(profile.taxNumber());
            companyAddressField.setText(profile.address());
            companyEmailField.setText(profile.email());
            draftIbans.clear();
            draftIbans.putAll(profile.ibansByCurrency());

            String prevCur = activeIbanCurrency;
            ibanCurrencyCombo.removeAllItems();
            for (CurrencyDef c : allCurrencies) {
                if (c.active()) {
                    ibanCurrencyCombo.addItem(c.code());
                }
            }
            if (ibanCurrencyCombo.getItemCount() == 0) {
                ibanCurrencyCombo.addItem("HUF");
                ibanCurrencyCombo.addItem("EUR");
            }
            if (prevCur != null) {
                ibanCurrencyCombo.setSelectedItem(prevCur);
            }
            Object sel = ibanCurrencyCombo.getSelectedItem();
            activeIbanCurrency = sel != null ? sel.toString() : "HUF";
            ibanField.setText(draftIbans.getOrDefault(activeIbanCurrency, ""));
        } finally {
            loadingCompany = false;
        }
    }

    void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        companySectionTitle.setText(i18n.t("invoice.settings.company").toUpperCase(Locale.ROOT));
        companyNameLbl.setText(i18n.t("customers.company") + " *:");
        companyTaxLbl.setText(i18n.t("invoice.tax_number") + ":");
        companyAddressLbl.setText(i18n.t("invoice.address") + ":");
        companyEmailLbl.setText(i18n.t("invoice.email") + ":");
        ibanCurrencyLbl.setText(i18n.t("invoice.settings.iban_currency") + ":");
        ibanValueLbl.setText(i18n.t("invoice.settings.iban") + ":");
        saveCompanyBtn.setText(i18n.t("common.save").toUpperCase(Locale.ROOT));

        taxSectionTitle.setText(i18n.t("invoice.settings.taxes").toUpperCase(Locale.ROOT));
        addTaxBtn.setText("+ " + i18n.t("common.add").toUpperCase(Locale.ROOT));
        editTaxBtn.setText(i18n.t("common.edit").toUpperCase(Locale.ROOT));
        toggleTaxBtn.setText(i18n.t("common.activate").toUpperCase(Locale.ROOT)
                + " / " + i18n.t("common.deactivate").toUpperCase(Locale.ROOT));
        deleteTaxBtn.setText(i18n.t("common.delete").toUpperCase(Locale.ROOT));

        currencySectionTitle.setText(i18n.t("invoice.settings.currencies").toUpperCase(Locale.ROOT));
        addCurrencyBtn.setText("+ " + i18n.t("common.add").toUpperCase(Locale.ROOT));
        editCurrencyBtn.setText(i18n.t("common.edit").toUpperCase(Locale.ROOT));
        toggleCurrencyBtn.setText(i18n.t("common.activate").toUpperCase(Locale.ROOT)
                + " / " + i18n.t("common.deactivate").toUpperCase(Locale.ROOT));
        deleteCurrencyBtn.setText(i18n.t("common.delete").toUpperCase(Locale.ROOT));

        taxModel.setColumnIdentifiers(new Object[]{
                i18n.t("common.code").toUpperCase(Locale.ROOT),
                i18n.t("common.name.en").toUpperCase(Locale.ROOT),
                i18n.t("common.name.hu").toUpperCase(Locale.ROOT),
                i18n.t("invoice.settings.tax_rate").toUpperCase(Locale.ROOT),
                i18n.t("invoice.settings.country_scope").toUpperCase(Locale.ROOT),
                i18n.t("common.status").toUpperCase(Locale.ROOT)
        });

        currencyModel.setColumnIdentifiers(new Object[]{
                i18n.t("common.code").toUpperCase(Locale.ROOT),
                i18n.t("common.name.en").toUpperCase(Locale.ROOT),
                i18n.t("common.name.hu").toUpperCase(Locale.ROOT),
                i18n.t("invoice.settings.symbol").toUpperCase(Locale.ROOT),
                i18n.t("invoice.settings.decimals").toUpperCase(Locale.ROOT),
                i18n.t("common.status").toUpperCase(Locale.ROOT)
        });
    }

    void applyTheme() {
        setBackground(ThemeManager.bg());
        BrutalistStyle.applyBoxCard(companyCard, 10, 12);
        BrutalistStyle.applyBoxCard(taxCard, 10, 12);
        BrutalistStyle.applyBoxCard(currencyCard, 10, 12);
        companySectionTitle.setForeground(ThemeManager.fg());
        taxSectionTitle.setForeground(ThemeManager.fg());
        currencySectionTitle.setForeground(ThemeManager.fg());
        BrutalistStyle.stylePrimaryButton(saveCompanyBtn);

        BrutalistStyle.stylePrimaryButton(addTaxBtn);
        BrutalistStyle.styleSecondaryButton(editTaxBtn);
        BrutalistStyle.styleSecondaryButton(toggleTaxBtn);
        BrutalistStyle.styleSecondaryButton(deleteTaxBtn);

        BrutalistStyle.stylePrimaryButton(addCurrencyBtn);
        BrutalistStyle.styleSecondaryButton(editCurrencyBtn);
        BrutalistStyle.styleSecondaryButton(toggleCurrencyBtn);
        BrutalistStyle.styleSecondaryButton(deleteCurrencyBtn);

        BrutalistStyle.styleTable(taxTable);
        BrutalistStyle.styleTable(currencyTable);
        taxTable.repaint();
        currencyTable.repaint();
    }
}
