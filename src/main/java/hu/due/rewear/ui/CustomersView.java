package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CountryDirectory;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.PartnerAgingStatement;
import hu.due.rewear.model.PartnerType;
import hu.due.rewear.service.DocumentPageSource;
import hu.due.rewear.util.FormatUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * B2B Business Partners management view supporting Buyers (Customers), Providers (Suppliers we pay),
 * or Both, with country-based VAT classification, preferred currency, and Orders + Invoices history drill-down.
 */
public final class CustomersView extends JPanel implements NavigableView {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private enum FilterMode { ALL, BUYERS, SUPPLIERS }

    private final AppContext ctx;

    private final JButton addBtn = new JButton();
    private final JButton editBtn = new JButton();
    private final JButton statementBtn = new JButton();

    private final JButton filterAllBtn = new JButton();
    private final JButton filterBuyersBtn = new JButton();
    private final JButton filterSuppliersBtn = new JButton();
    private FilterMode filterMode = FilterMode.ALL;

    private final DefaultTableModel customerModel = BrutalistStyle.nonEditableTableModel();
    private final JTable customerTable = new JTable(customerModel);

    private final JLabel historyTitleLabel = new JLabel();
    private final DefaultTableModel historyModel = BrutalistStyle.nonEditableTableModel();
    private final JTable historyTable = new JTable(historyModel);

    private List<Customer> customers = List.of();

    private record TypeOption(PartnerType type, String label) {
        @Override public String toString() { return label; }
    }

    private record CountryItem(String code, String label) {
        @Override public String toString() { return label; }
    }

    public CustomersView(AppContext ctx) {
        this.ctx = ctx;

        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        buildUi();
        wireEvents();
        applyTranslations();
    }

    private void buildUi() {
        JPanel toolbar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        toolbar.add(addBtn);
        toolbar.add(editBtn);
        toolbar.add(statementBtn);
        toolbar.add(new JLabel("   |   "));
        toolbar.add(filterAllBtn);
        toolbar.add(filterBuyersBtn);
        toolbar.add(filterSuppliersBtn);
        add(toolbar, BorderLayout.NORTH);

        BrutalistStyle.styleTable(customerTable);
        customerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JPanel historyPanel = new JPanel(new BorderLayout(0, 6));
        historyTitleLabel.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        historyPanel.add(historyTitleLabel, BorderLayout.NORTH);

        BrutalistStyle.styleTable(historyTable);
        historyPanel.add(new JScrollPane(historyTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(customerTable), historyPanel);
        split.setResizeWeight(0.58);
        split.setDividerLocation(320);
        add(split, BorderLayout.CENTER);
    }

    private void wireEvents() {
        addBtn.addActionListener(e -> openCustomerDialog(null));
        editBtn.addActionListener(e -> {
            Customer c = getSelectedCustomer();
            if (c != null) openCustomerDialog(c);
        });
        statementBtn.addActionListener(e -> handlePreviewPartnerStatement());

        filterAllBtn.addActionListener(e -> { filterMode = FilterMode.ALL; styleFilterButtons(); refreshData(); });
        filterBuyersBtn.addActionListener(e -> { filterMode = FilterMode.BUYERS; styleFilterButtons(); refreshData(); });
        filterSuppliersBtn.addActionListener(e -> { filterMode = FilterMode.SUPPLIERS; styleFilterButtons(); refreshData(); });

        customerTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadHistoryForSelectedPartner();
            }
        });
    }

    private Customer getSelectedCustomer() {
        int r = customerTable.getSelectedRow();
        return (r >= 0 && r < customers.size()) ? customers.get(r) : null;
    }

    private void openCustomerDialog(Customer existing) {
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();

        JTextField nameF = new JTextField(existing != null ? existing.name() : "", 24);
        JComboBox<TypeOption> typeCombo = new JComboBox<>();
        for (PartnerType pt : PartnerType.values()) {
            typeCombo.addItem(new TypeOption(pt, i18n.t("partners.type." + pt.name())));
        }
        PartnerType initialType = existing != null ? existing.effectivePartnerType()
                : (filterMode == FilterMode.SUPPLIERS ? PartnerType.SUPPLIER : PartnerType.CUSTOMER);
        for (int i = 0; i < typeCombo.getItemCount(); i++) {
            if (typeCombo.getItemAt(i).type() == initialType) {
                typeCombo.setSelectedIndex(i);
                break;
            }
        }

        JComboBox<CountryItem> countryCombo = new JComboBox<>();
        String initCountry = existing != null ? existing.effectiveCountryCode() : "HU";
        int selCountryIdx = 0;
        for (int i = 0; i < CountryDirectory.COUNTRIES.size(); i++) {
            var opt = CountryDirectory.COUNTRIES.get(i);
            countryCombo.addItem(new CountryItem(opt.code(), opt.localizedLabel(lang)));
            if (opt.code().equalsIgnoreCase(initCountry)) {
                selCountryIdx = i;
            }
        }
        countryCombo.setSelectedIndex(selCountryIdx);

        List<CurrencyDef> currencies = ctx.getFinanceConfigDao().findCurrencies(true);
        JComboBox<String> curCombo = new JComboBox<>();
        for (CurrencyDef c : currencies) {
            curCombo.addItem(c.code());
        }
        String initCur = existing != null ? existing.effectiveCurrencyCode()
                : ("HU".equals(initCountry) ? "HUF" : "EUR");
        curCombo.setSelectedItem(initCur);

        // When adding a new partner and switching country, auto-suggest HUF for HU and EUR for international
        if (existing == null) {
            countryCombo.addActionListener(e -> {
                if (countryCombo.getSelectedItem() instanceof CountryItem ci) {
                    curCombo.setSelectedItem("HU".equals(ci.code()) ? "HUF" : "EUR");
                }
            });
        }

        JTextField contactF = new JTextField(existing != null ? FormatUtil.safe(existing.contactName()) : "", 24);
        JTextField emailF = new JTextField(existing != null ? FormatUtil.safe(existing.email()) : "", 24);
        JTextField phoneF = new JTextField(existing != null ? FormatUtil.safe(existing.phone()) : "", 24);
        JTextField addrF = new JTextField(existing != null ? FormatUtil.safe(existing.address()) : "", 24);
        JTextField taxF = new JTextField(existing != null ? FormatUtil.safe(existing.taxNumber()) : "", 24);
        JCheckBox activeBox = new JCheckBox(i18n.t("common.activate"), existing == null || existing.active());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("customers.company") + " *", nameF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("partners.type") + " *", typeCombo);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("partners.country") + " *", countryCombo);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("customers.preferred_currency") + " *", curCombo);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("customers.contact"), contactF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("customers.email"), emailF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("customers.phone"), phoneF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("customers.address"), addrF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("customers.tax_number"), taxF);
        if (existing != null) {
            gbc.gridx = 1; gbc.gridy = r; form.add(activeBox, gbc);
        }

        String title = i18n.t(existing == null ? "customers.add_title" : "customers.edit_title");
        if (JOptionPane.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            String company = nameF.getText().trim();
            if (company.isEmpty()) {
                throw new IllegalArgumentException(i18n.t("validation.company_required"));
            }
            PartnerType chosenType = typeCombo.getSelectedItem() instanceof TypeOption to ? to.type() : PartnerType.CUSTOMER;
            String chosenCountry = countryCombo.getSelectedItem() instanceof CountryItem ci ? ci.code() : "HU";
            String chosenCur = curCombo.getSelectedItem() != null ? curCombo.getSelectedItem().toString() : "EUR";

            if (existing == null) {
                ctx.getCustomerDao().insert(company, chosenType, contactF.getText().trim(), emailF.getText().trim(),
                        phoneF.getText().trim(), addrF.getText().trim(), taxF.getText().trim(), chosenCountry, chosenCur);
            } else {
                ctx.getCustomerDao().update(existing.id(), company, chosenType, contactF.getText().trim(), emailF.getText().trim(),
                        phoneF.getText().trim(), addrF.getText().trim(), taxF.getText().trim(), chosenCountry, chosenCur,
                        activeBox.isSelected());
            }
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handlePreviewPartnerStatement() {
        Customer sel = getSelectedCustomer();
        if (sel == null) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        ctx.getInvoiceService().syncOverdueStatuses();
        List<Invoice> invs = ctx.getInvoiceDao().findByPartner(sel.id(), sel.name());
        Map<String, Integer> decByCur = ctx.getFinanceConfigDao().decimalPlacesMap();
        PartnerAgingStatement statement = ctx.getPartnerStatementService()
                .computeStatement(sel, invs, decByCur, LocalDate.now());
        DocumentPageSource doc = ctx.getPartnerStatementService().createPageSource(statement, invs, decByCur);
        Window owner = SwingUtilities.getWindowAncestor(this);
        DocumentPreviewDialog.showDocuments(owner, ctx, List.of(doc), sel.email(),
                i18n.t("partners.statement.pdf_title") + " — " + sel.name());
    }

    private void loadHistoryForSelectedPartner() {
        historyModel.setRowCount(0);
        Customer sel = getSelectedCustomer();
        if (sel == null) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        for (Order o : ctx.getOrderDao().findByCustomerId(sel.id())) {
            historyModel.addRow(new Object[]{
                    i18n.t("nav.orders").toUpperCase(Locale.ROOT),
                    o.orderNumber(),
                    i18n.t("status.order." + o.status().name()).toUpperCase(Locale.ROOT),
                    FormatUtil.stripZeros(o.totalWeightKg()) + " kg",
                    CurrencyDef.formatAmount(o.totalPrice(), o.effectiveCurrencyCode(), o.currencyDecimals()),
                    o.createdAt() != null ? DT_FMT.format(o.createdAt()) : ""
            });
        }
        for (Invoice inv : ctx.getInvoiceDao().findByPartner(sel.id(), sel.name())) {
            historyModel.addRow(new Object[]{
                    i18n.t("invoice.direction.short." + inv.direction().name()),
                    inv.invoiceNumber(),
                    i18n.t("status.invoice." + inv.status().name()).toUpperCase(Locale.ROOT),
                    inv.effectiveTaxTypeCode() + " (" + FormatUtil.stripZeros(inv.vatRate()) + "%)",
                    CurrencyDef.formatAmount(inv.grossAmount(), inv.effectiveCurrencyCode(), inv.currencyDecimals()),
                    inv.issueDate().toString()
            });
        }
    }

    private void styleFilterButtons() {
        if (filterMode == FilterMode.ALL) BrutalistStyle.stylePrimaryButton(filterAllBtn);
        else BrutalistStyle.styleSecondaryButton(filterAllBtn);

        if (filterMode == FilterMode.BUYERS) BrutalistStyle.stylePrimaryButton(filterBuyersBtn);
        else BrutalistStyle.styleSecondaryButton(filterBuyersBtn);

        if (filterMode == FilterMode.SUPPLIERS) BrutalistStyle.stylePrimaryButton(filterSuppliersBtn);
        else BrutalistStyle.styleSecondaryButton(filterSuppliersBtn);
    }

    @Override
    public void applyTheme() {
        applyBrutalistTheme();
    }

    public void applyBrutalistTheme() {
        BrutalistStyle.stylePrimaryButton(addBtn);
        BrutalistStyle.styleSecondaryButtons(editBtn, statementBtn);
        styleFilterButtons();
        BrutalistStyle.styleTable(customerTable);
        BrutalistStyle.styleTable(historyTable);
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_CUSTOMERS;
    }

    @Override
    public String getTitleI18nKey() {
        return "customers.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();
        customers = switch (filterMode) {
            case ALL -> ctx.getCustomerDao().findAll(false);
            case BUYERS -> ctx.getCustomerDao().findBuyers(false);
            case SUPPLIERS -> ctx.getCustomerDao().findSuppliers(false);
        };
        customerModel.setRowCount(0);
        for (Customer c : customers) {
            customerModel.addRow(new Object[]{
                    c.name(),
                    i18n.t("partners.type." + c.effectivePartnerType().name()).toUpperCase(Locale.ROOT),
                    CountryDirectory.localizedCountryName(c.effectiveCountryCode(), lang),
                    c.effectiveCurrencyCode(),
                    FormatUtil.safe(c.contactName()),
                    FormatUtil.safe(c.email()),
                    FormatUtil.safe(c.taxNumber()),
                    c.active() ? i18n.t("common.yes").toUpperCase(Locale.ROOT) : i18n.t("common.no").toUpperCase(Locale.ROOT)
            });
        }
        if (!customers.isEmpty() && customerTable.getSelectedRow() < 0) {
            customerTable.setRowSelectionInterval(0, 0);
        }
        loadHistoryForSelectedPartner();
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        addBtn.setText("+ " + i18n.t("common.add").toUpperCase(Locale.ROOT));
        editBtn.setText(i18n.t("common.edit").toUpperCase(Locale.ROOT));
        statementBtn.setText(i18n.t("partners.statement.btn").toUpperCase(Locale.ROOT));
        filterAllBtn.setText(i18n.t("partners.filter.all").toUpperCase(Locale.ROOT));
        filterBuyersBtn.setText(i18n.t("partners.filter.buyers").toUpperCase(Locale.ROOT));
        filterSuppliersBtn.setText(i18n.t("partners.filter.suppliers").toUpperCase(Locale.ROOT));
        historyTitleLabel.setText(i18n.t("partners.history").toUpperCase(Locale.ROOT));

        customerModel.setColumnIdentifiers(new Object[]{
                i18n.t("customers.company").toUpperCase(Locale.ROOT),
                i18n.t("partners.type").toUpperCase(Locale.ROOT),
                i18n.t("partners.country").toUpperCase(Locale.ROOT),
                i18n.t("currency.label").toUpperCase(Locale.ROOT),
                i18n.t("customers.contact").toUpperCase(Locale.ROOT),
                i18n.t("customers.email").toUpperCase(Locale.ROOT),
                i18n.t("customers.tax_number").toUpperCase(Locale.ROOT),
                i18n.t("common.status").toUpperCase(Locale.ROOT)
        });
        historyModel.setColumnIdentifiers(new Object[]{
                i18n.t("invoice.direction").toUpperCase(Locale.ROOT),
                i18n.t("orders.order_number").toUpperCase(Locale.ROOT) + " / " + i18n.t("invoice.number").toUpperCase(Locale.ROOT),
                i18n.t("common.status").toUpperCase(Locale.ROOT),
                "KG / " + i18n.t("invoice.vat").toUpperCase(Locale.ROOT),
                i18n.t("orders.total_price").toUpperCase(Locale.ROOT),
                i18n.t("common.created_at").toUpperCase(Locale.ROOT)
        });

        applyBrutalistTheme();
        refreshData();
    }
}
