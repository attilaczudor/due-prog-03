package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.FinancialSummary;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceItem;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.TaxType;
import hu.due.rewear.service.DocumentPageSource;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Brutalist Monochrome Invoices & Financial Overview workspace with two tabs:
 * 1) Invoices: strictly separated per-currency ledgers (HUF, EUR, USD, ...) with zero forced FX conversion,
 *    country-aware VAT auto-selection (HU 27%, EU 0%, Non-EU 0%), order & supplier auto-fill, A4 PDF Preview/Print/Email.
 * 2) Invoice Settings: full CRUD management for Tax Types (VAT rates & country scopes) and operating Currencies.
 */
public final class InvoicesView extends JPanel implements NavigableView {

    private final AppContext ctx;

    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel invoicesTab = new JPanel(new BorderLayout(0, 10));
    private final JPanel settingsTab = new JPanel(new BorderLayout(0, 10));

    // --- Tab 1: Separate Currency Ledger Bar (NO FX conversion) ---
    private final JPanel currencyLedgerBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 5));
    private final JLabel currencyLedgerLabel = new JLabel();
    private final Map<String, JButton> ledgerButtons = new LinkedHashMap<>();
    private String selectedLedgerCurrency = null; // null == ALL (shown separately per currency)

    // 4 Financial KPI Cards
    private final JPanel kpiCardIncome = new JPanel();
    private final JPanel kpiCardExpense = new JPanel();
    private final JPanel kpiCardBalance = new JPanel();
    private final JPanel kpiCardUnpaid = new JPanel();

    private final JLabel kpiTitleIncome = new JLabel();
    private final JLabel kpiTitleExpense = new JLabel();
    private final JLabel kpiTitleBalance = new JLabel();
    private final JLabel kpiTitleUnpaid = new JLabel();

    private final JLabel kpiValIncome = new JLabel("—");
    private final JLabel kpiValExpense = new JLabel("—");
    private final JLabel kpiValBalance = new JLabel("—");
    private final JLabel kpiValUnpaid = new JLabel("—");

    private final JLabel kpiSubIncome = new JLabel(" ");
    private final JLabel kpiSubExpense = new JLabel(" ");
    private final JLabel kpiSubBalance = new JLabel(" ");
    private final JLabel kpiSubUnpaid = new JLabel(" ");

    // Filters & Actions
    private final JPanel filterCard = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 5));
    private final JTextField searchField = new JTextField(16);
    private final JComboBox<FilterItem<InvoiceDirection>> directionFilter = new JComboBox<>();
    private final JComboBox<FilterItem<InvoiceStatus>> statusFilter = new JComboBox<>();
    private final JComboBox<FilterItem<InvoiceCategory>> categoryFilter = new JComboBox<>();
    private final JButton refreshBtn = new JButton();

    private final JPanel actionBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 5));
    private final JButton newOutgoingBtn = new JButton();
    private final JButton newIncomingBtn = new JButton();
    private final JComboBox<StatusChoice> statusCombo = new JComboBox<>();
    private final JButton applyStatusBtn = new JButton();
    private final JButton previewDocBtn = new JButton();
    private final JButton exportCsvBtn = new JButton();

    private final DefaultTableModel tableModel = nonEditableModel();
    private final JTable table = new JTable(tableModel);

    // --- Tab 2: Invoice Settings (Tax Types + Currencies) ---
    private final JPanel taxCard = new JPanel(new BorderLayout(0, 8));
    private final JLabel taxSectionTitle = new JLabel();
    private final JButton addTaxBtn = new JButton();
    private final JButton editTaxBtn = new JButton();
    private final JButton toggleTaxBtn = new JButton();
    private final JButton deleteTaxBtn = new JButton();
    private final DefaultTableModel taxModel = nonEditableModel();
    private final JTable taxTable = new JTable(taxModel);

    private final JPanel currencyCard = new JPanel(new BorderLayout(0, 8));
    private final JLabel currencySectionTitle = new JLabel();
    private final JButton addCurrencyBtn = new JButton();
    private final JButton editCurrencyBtn = new JButton();
    private final JButton toggleCurrencyBtn = new JButton();
    private final JButton deleteCurrencyBtn = new JButton();
    private final DefaultTableModel currencyModel = nonEditableModel();
    private final JTable currencyTable = new JTable(currencyModel);

    private List<Invoice> allInvoices = List.of();
    private List<Invoice> filteredInvoices = List.of();
    private List<TaxType> allTaxTypes = List.of();
    private List<CurrencyDef> allCurrencies = List.of();
    private boolean updatingControls = false;

    private record FilterItem<T>(T value, String label) {
        @Override public String toString() { return label; }
    }

    private record StatusChoice(InvoiceStatus status, String label) {
        @Override public String toString() { return label; }
    }

    private record TaxChoice(TaxType tax, String label) {
        @Override public String toString() { return label; }
    }

    private record ScopeOption(String code, String label) {
        @Override public String toString() { return label; }
    }

    public InvoicesView(AppContext ctx) {
        this.ctx = ctx;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        buildInvoicesTab();
        buildSettingsTab();
        tabs.addTab("", invoicesTab);
        tabs.addTab("", settingsTab);
        add(tabs, BorderLayout.CENTER);

        wireEvents();
        applyTranslations();
        applyTheme();
        refreshData();
    }

    private static DefaultTableModel nonEditableModel() {
        return new DefaultTableModel() {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private void buildInvoicesTab() {
        invoicesTab.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JPanel northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.setOpaque(false);

        // 0. Separate Currency Ledger Selector Bar
        currencyLedgerBar.setAlignmentX(LEFT_ALIGNMENT);
        currencyLedgerLabel.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        northStack.add(currencyLedgerBar);
        northStack.add(Box.createVerticalStrut(8));

        // 1. Financial KPI Cards
        JPanel kpiGrid = new JPanel(new GridLayout(1, 4, 12, 0));
        kpiGrid.setOpaque(false);
        configureKpiCard(kpiCardIncome, kpiTitleIncome, kpiValIncome, kpiSubIncome);
        configureKpiCard(kpiCardExpense, kpiTitleExpense, kpiValExpense, kpiSubExpense);
        configureKpiCard(kpiCardBalance, kpiTitleBalance, kpiValBalance, kpiSubBalance);
        configureKpiCard(kpiCardUnpaid, kpiTitleUnpaid, kpiValUnpaid, kpiSubUnpaid);
        kpiGrid.add(kpiCardIncome);
        kpiGrid.add(kpiCardExpense);
        kpiGrid.add(kpiCardBalance);
        kpiGrid.add(kpiCardUnpaid);
        kpiGrid.setAlignmentX(LEFT_ALIGNMENT);
        northStack.add(kpiGrid);
        northStack.add(Box.createVerticalStrut(10));

        // 2. Filter Bar
        filterCard.setAlignmentX(LEFT_ALIGNMENT);
        filterCard.add(searchField);
        filterCard.add(directionFilter);
        filterCard.add(statusFilter);
        filterCard.add(categoryFilter);
        filterCard.add(refreshBtn);
        northStack.add(filterCard);
        northStack.add(Box.createVerticalStrut(8));

        // 3. Action Bar
        actionBar.setAlignmentX(LEFT_ALIGNMENT);
        actionBar.setOpaque(false);
        actionBar.add(newOutgoingBtn);
        actionBar.add(newIncomingBtn);
        actionBar.add(statusCombo);
        actionBar.add(applyStatusBtn);
        actionBar.add(previewDocBtn);
        actionBar.add(exportCsvBtn);
        northStack.add(actionBar);

        invoicesTab.add(northStack, BorderLayout.NORTH);

        // 4. Invoices Table
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        BrutalistStyle.styleTable(table);
        invoicesTab.add(new JScrollPane(table), BorderLayout.CENTER);

        invoicesTab.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                currencyLedgerBar.revalidate();
                filterCard.revalidate();
                actionBar.revalidate();
                northStack.revalidate();
            }
        });
    }

    private void buildSettingsTab() {
        settingsTab.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

        // Top Card: Tax Types
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

        // Bottom Card: Currencies
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
        split.setDividerLocation(310);
        settingsTab.add(split, BorderLayout.CENTER);
    }

    private static void configureKpiCard(JPanel card, JLabel title, JLabel val, JLabel sub) {
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        title.setFont(ThemeManager.monoFont(Font.BOLD, 10.5f));
        val.setFont(ThemeManager.monoFont(Font.BOLD, 17f));
        sub.setFont(ThemeManager.monoFont(Font.PLAIN, 10.5f));
        card.add(title);
        card.add(Box.createVerticalStrut(4));
        card.add(val);
        card.add(Box.createVerticalStrut(3));
        card.add(sub);
    }

    private void wireEvents() {
        searchField.addActionListener(e -> applyFiltersAndRender());
        directionFilter.addActionListener(e -> { if (!updatingControls) applyFiltersAndRender(); });
        statusFilter.addActionListener(e -> { if (!updatingControls) applyFiltersAndRender(); });
        categoryFilter.addActionListener(e -> { if (!updatingControls) applyFiltersAndRender(); });
        refreshBtn.addActionListener(e -> refreshData());

        newOutgoingBtn.addActionListener(e -> openNewOutgoingDialog());
        newIncomingBtn.addActionListener(e -> openNewIncomingDialog());
        applyStatusBtn.addActionListener(e -> handleStatusUpdate());
        previewDocBtn.addActionListener(e -> openSelectedInvoicePreview());
        exportCsvBtn.addActionListener(e -> handleExportCsv());

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                syncSelectedInvoiceControls();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openSelectedInvoicePreview();
                }
            }
        });

        // Settings tab events
        addTaxBtn.addActionListener(e -> openTaxDialog(null));
        editTaxBtn.addActionListener(e -> {
            int r = taxTable.getSelectedRow();
            if (r >= 0 && r < allTaxTypes.size()) openTaxDialog(allTaxTypes.get(r));
        });
        toggleTaxBtn.addActionListener(e -> handleToggleTax());
        deleteTaxBtn.addActionListener(e -> handleDeleteTax());

        addCurrencyBtn.addActionListener(e -> openCurrencyDialog(null));
        editCurrencyBtn.addActionListener(e -> {
            int r = currencyTable.getSelectedRow();
            if (r >= 0 && r < allCurrencies.size()) openCurrencyDialog(allCurrencies.get(r));
        });
        toggleCurrencyBtn.addActionListener(e -> handleToggleCurrency());
        deleteCurrencyBtn.addActionListener(e -> handleDeleteCurrency());
    }

    private void rebuildCurrencyLedgerBar() {
        I18nManager i18n = ctx.getI18n();
        currencyLedgerBar.removeAll();
        ledgerButtons.clear();

        currencyLedgerBar.add(currencyLedgerLabel);

        JButton allBtn = new JButton(i18n.t("common.all").toUpperCase(Locale.ROOT));
        allBtn.addActionListener(e -> {
            selectedLedgerCurrency = null;
            styleCurrencyLedgerButtons();
            applyFiltersAndRender();
        });
        ledgerButtons.put("__ALL__", allBtn);
        currencyLedgerBar.add(allBtn);

        List<CurrencyDef> activeCurrencies = ctx.getFinanceConfigDao().findCurrencies(true);
        boolean stillValid = selectedLedgerCurrency == null;
        for (CurrencyDef cur : activeCurrencies) {
            String code = cur.code();
            if (code.equalsIgnoreCase(selectedLedgerCurrency)) {
                stillValid = true;
            }
            JButton btn = new JButton(code);
            btn.addActionListener(e -> {
                selectedLedgerCurrency = code;
                styleCurrencyLedgerButtons();
                applyFiltersAndRender();
            });
            ledgerButtons.put(code, btn);
            currencyLedgerBar.add(btn);
        }
        if (!stillValid) {
            selectedLedgerCurrency = null;
        }
        styleCurrencyLedgerButtons();
        currencyLedgerBar.revalidate();
        currencyLedgerBar.repaint();
    }

    private void styleCurrencyLedgerButtons() {
        for (Map.Entry<String, JButton> e : ledgerButtons.entrySet()) {
            boolean active = ("__ALL__".equals(e.getKey()) && selectedLedgerCurrency == null)
                    || e.getKey().equalsIgnoreCase(selectedLedgerCurrency);
            if (active) {
                BrutalistStyle.stylePrimaryButton(e.getValue());
            } else {
                BrutalistStyle.styleSecondaryButton(e.getValue());
            }
        }
    }

    private Invoice getSelectedInvoice() {
        int r = table.getSelectedRow();
        return (r >= 0 && r < filteredInvoices.size()) ? filteredInvoices.get(r) : null;
    }

    private void syncSelectedInvoiceControls() {
        Invoice inv = getSelectedInvoice();
        boolean hasSel = inv != null;
        statusCombo.setEnabled(hasSel);
        applyStatusBtn.setEnabled(hasSel);
        previewDocBtn.setEnabled(hasSel);
        if (inv != null) {
            for (int i = 0; i < statusCombo.getItemCount(); i++) {
                if (statusCombo.getItemAt(i).status() == inv.status()) {
                    statusCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private void handleStatusUpdate() {
        Invoice inv = getSelectedInvoice();
        if (inv == null || !(statusCombo.getSelectedItem() instanceof StatusChoice choice)) {
            return;
        }
        if (inv.status() == choice.status()) {
            return;
        }
        try {
            ctx.getInvoiceService().changeStatus(inv.id(), choice.status());
            refreshDataSelecting(inv.id());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    ctx.getI18n().t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openSelectedInvoicePreview() {
        Invoice inv = getSelectedInvoice();
        if (inv == null) {
            return;
        }
        List<InvoiceItem> items = ctx.getInvoiceService().findItems(inv.id());
        List<DocumentPageSource> docs = new ArrayList<>();
        docs.add(ctx.getInvoicePdfExporter().createPageSource(inv, items));

        if (inv.orderId() != null) {
            ctx.getOrderDao().findById(inv.orderId()).ifPresent(ord -> {
                Customer cust = ctx.getCustomerDao().findById(ord.customerId()).orElse(null);
                var ordItems = ctx.getOrderItemDao().findByOrderId(ord.id());
                docs.add(ctx.getDeliveryNotePdfExporter().createPageSource(ord, cust, ordItems));
            });
        }

        Window owner = SwingUtilities.getWindowAncestor(this);
        DocumentPreviewDialog.showDocuments(owner, ctx, docs, inv.partnerEmail(),
                ctx.getI18n().t("invoice.pdf.title") + " — " + inv.invoiceNumber());
    }

    private JComboBox<TaxChoice> buildTaxTypeCombo(String lang) {
        JComboBox<TaxChoice> combo = new JComboBox<>();
        for (TaxType t : ctx.getFinanceConfigDao().findTaxTypes(true)) {
            combo.addItem(new TaxChoice(t, t.displayLabel(lang)));
        }
        return combo;
    }

    private static void selectTaxInCombo(JComboBox<TaxChoice> combo, TaxType target) {
        if (target == null) return;
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).tax().code().equalsIgnoreCase(target.code())) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    private JComboBox<String> buildCurrencyCombo(String preferred) {
        JComboBox<String> combo = new JComboBox<>();
        for (CurrencyDef c : ctx.getFinanceConfigDao().findCurrencies(true)) {
            combo.addItem(c.code());
        }
        if (preferred != null) {
            combo.setSelectedItem(preferred);
        }
        return combo;
    }

    private void openNewOutgoingDialog() {
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();
        List<Order> orders = ctx.getOrderDao().findAll();
        JComboBox<Object> orderPicker = new JComboBox<>();
        orderPicker.addItem(i18n.t("invoice.manual_entry"));
        for (Order o : orders) {
            orderPicker.addItem(o);
        }

        String defaultCur = selectedLedgerCurrency != null ? selectedLedgerCurrency : "HUF";
        JComboBox<String> curCombo = buildCurrencyCombo(defaultCur);
        JComboBox<TaxChoice> taxCombo = buildTaxTypeCombo(lang);
        selectTaxInCombo(taxCombo, ctx.getFinanceConfigDao().resolveDefaultTaxTypeForCountry("HU"));

        JTextField partnerF = new JTextField(24);
        JTextField taxF = new JTextField(24);
        JTextField addressF = new JTextField(24);
        JTextField emailF = new JTextField(24);
        JTextField descF = new JTextField(24);
        JTextField qtyF = new JTextField("1", 10);
        JTextField unitF = new JTextField("kg", 8);
        JTextField priceF = new JTextField("0", 10);
        JLabel priceLabel = new JLabel(i18n.t("invoice.item.unit_price") + " (" + curCombo.getSelectedItem() + ") *:");

        curCombo.addActionListener(e -> priceLabel.setText(
                i18n.t("invoice.item.unit_price") + " (" + curCombo.getSelectedItem() + ") *:"));

        JTextField[] autoFields = {partnerF, taxF, addressF, emailF, descF, qtyF, unitF, priceF};

        orderPicker.addActionListener(e -> {
            if (orderPicker.getSelectedItem() instanceof Order ord) {
                String ordCur = ord.effectiveCurrencyCode();
                int dec = ctx.getFinanceConfigDao().decimalPlacesFor(ordCur);
                curCombo.setSelectedItem(ordCur);
                curCombo.setEnabled(false);

                Customer cust = ctx.getCustomerDao().findById(ord.customerId()).orElse(null);
                partnerF.setText(cust != null ? cust.name() : ord.customerName());
                taxF.setText(cust != null && cust.taxNumber() != null ? cust.taxNumber() : "");
                addressF.setText(cust != null && cust.address() != null ? cust.address() : "");
                emailF.setText(cust != null && cust.email() != null ? cust.email() : "");

                var ordItems = ctx.getOrderItemDao().findByOrderId(ord.id());
                if (ordItems.size() == 1) {
                    var item = ordItems.get(0);
                    descF.setText(item.packageQrCode() + " (" + item.localizedSummary(lang) + ")");
                } else {
                    descF.setText(ord.orderNumber() + " — " + ordItems.size() + " "
                            + ("hu".equalsIgnoreCase(lang) ? "csomag" : "package(s)")
                            + " (" + CurrencyDef.formatAmount(ord.totalPrice(), ordCur, dec) + ")");
                }

                BigDecimal weight = ord.totalWeightKg() != null ? ord.totalWeightKg() : BigDecimal.ZERO;
                qtyF.setText(weight.stripTrailingZeros().toPlainString());
                unitF.setText("kg");

                if (weight.compareTo(BigDecimal.ZERO) > 0 && ord.totalPrice() != null) {
                    BigDecimal avgPerKg = ord.totalPrice().divide(weight, dec, RoundingMode.HALF_UP);
                    priceF.setText(CurrencyDef.roundForScale(avgPerKg, dec).toPlainString());
                } else {
                    priceF.setText(CurrencyDef.roundForScale(BigDecimal.ZERO, dec).toPlainString());
                }

                // Auto-select VAT type from customer's country (HU -> 27%, AT/SK/DE/EU -> 0%, Non-EU -> 0%)
                TaxType autoTax = ctx.getInvoiceDao().findByOrderId(ord.id())
                        .flatMap(inv -> ctx.getFinanceConfigDao().findTaxTypeByCode(inv.effectiveTaxTypeCode()))
                        .orElseGet(() -> ctx.getInvoiceService().resolveDefaultTaxForCustomer(cust));
                selectTaxInCombo(taxCombo, autoTax);

                for (JTextField f : autoFields) {
                    f.setEditable(false);
                    f.setBackground(ThemeManager.surface());
                    f.setForeground(ThemeManager.fg());
                }
            } else {
                curCombo.setEnabled(true);
                curCombo.setSelectedItem(defaultCur);
                selectTaxInCombo(taxCombo, ctx.getFinanceConfigDao().resolveDefaultTaxTypeForCountry("HU"));
                partnerF.setText("");
                taxF.setText("");
                addressF.setText("");
                emailF.setText("");
                descF.setText("");
                qtyF.setText("1");
                unitF.setText("kg");
                priceF.setText("0");
                for (JTextField f : autoFields) {
                    f.setEditable(true);
                    f.setBackground(ThemeManager.bg());
                    f.setForeground(ThemeManager.fg());
                }
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        addFormRow(form, gbc, r++, i18n.t("invoice.from_order"), orderPicker);
        addFormRow(form, gbc, r++, i18n.t("currency.label") + " *", curCombo);
        addFormRow(form, gbc, r++, i18n.t("invoice.partner") + " *", partnerF);
        addFormRow(form, gbc, r++, i18n.t("invoice.tax_number"), taxF);
        addFormRow(form, gbc, r++, i18n.t("invoice.address"), addressF);
        addFormRow(form, gbc, r++, i18n.t("invoice.email"), emailF);
        addFormRow(form, gbc, r++, i18n.t("invoice.item.description") + " *", descF);
        addFormRow(form, gbc, r++, i18n.t("invoice.item.qty") + " *", qtyF);
        addFormRow(form, gbc, r++, i18n.t("invoice.item.unit"), unitF);
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0; form.add(priceLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1; form.add(priceF, gbc);
        r++;
        addFormRow(form, gbc, r, i18n.t("invoice.tax_type") + " *", taxCombo);

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("invoice.new_outgoing"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            TaxType chosenTax = taxCombo.getSelectedItem() instanceof TaxChoice tc ? tc.tax() : null;
            long userId = ctx.getSession().requireCurrentUser().id();
            Invoice created;
            if (orderPicker.getSelectedItem() instanceof Order chosenOrder) {
                created = ctx.getInvoiceService().ensureInvoiceForOrder(chosenOrder.id(), chosenTax, userId);
            } else {
                String chosenCur = curCombo.getSelectedItem() != null ? curCombo.getSelectedItem().toString() : "EUR";
                BigDecimal qty = CurrencyCode.parseAmount(qtyF.getText());
                BigDecimal unitPrice = CurrencyCode.parseAmount(priceF.getText());
                created = ctx.getInvoiceService().createManualInvoice(
                        InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                        partnerF.getText(), taxF.getText(), addressF.getText(), emailF.getText(),
                        descF.getText(), qty, unitF.getText(), unitPrice, chosenTax, chosenCur,
                        LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.ISSUED, userId);
            }
            refreshDataSelecting(created.id());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openNewIncomingDialog() {
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();

        List<Customer> suppliers = ctx.getCustomerDao().findSuppliers(true);
        JComboBox<Object> supplierPicker = new JComboBox<>();
        supplierPicker.addItem(i18n.t("invoice.manual_entry"));
        for (Customer sup : suppliers) {
            supplierPicker.addItem(sup);
        }

        JComboBox<FilterItem<InvoiceCategory>> catCombo = new JComboBox<>();
        for (InvoiceCategory cat : InvoiceCategory.values()) {
            if (cat.defaultDirection() == InvoiceDirection.INCOMING) {
                catCombo.addItem(new FilterItem<>(cat, i18n.t("invoice.category." + cat.name())));
            }
        }
        String defaultCur = selectedLedgerCurrency != null ? selectedLedgerCurrency : "HUF";
        JComboBox<String> curCombo = buildCurrencyCombo(defaultCur);
        JComboBox<TaxChoice> taxCombo = buildTaxTypeCombo(lang);
        selectTaxInCombo(taxCombo, ctx.getFinanceConfigDao().resolveDefaultTaxTypeForCountry("HU"));

        JTextField invNoF = new JTextField(24);
        JTextField partnerF = new JTextField(24);
        JTextField taxF = new JTextField(24);
        JTextField addressF = new JTextField(24);
        JTextField emailF = new JTextField(24);
        JTextField descF = new JTextField(24);
        JTextField qtyF = new JTextField("1", 10);
        JTextField unitF = new JTextField("db", 8);
        JTextField priceF = new JTextField("0", 10);
        JLabel priceLabel = new JLabel(i18n.t("invoice.item.unit_price") + " (" + curCombo.getSelectedItem() + ") *:");
        curCombo.addActionListener(e -> priceLabel.setText(
                i18n.t("invoice.item.unit_price") + " (" + curCombo.getSelectedItem() + ") *:"));

        supplierPicker.addActionListener(e -> {
            if (supplierPicker.getSelectedItem() instanceof Customer sup) {
                partnerF.setText(sup.name());
                taxF.setText(sup.taxNumber() != null ? sup.taxNumber() : "");
                addressF.setText(sup.address() != null ? sup.address() : "");
                emailF.setText(sup.email() != null ? sup.email() : "");
                curCombo.setSelectedItem(sup.effectiveCurrencyCode());
                selectTaxInCombo(taxCombo, ctx.getInvoiceService().resolveDefaultTaxForCustomer(sup));
            }
        });

        JComboBox<StatusChoice> initStatusCombo = new JComboBox<>();
        for (InvoiceStatus st : List.of(InvoiceStatus.ISSUED, InvoiceStatus.PAID, InvoiceStatus.DRAFT)) {
            initStatusCombo.addItem(new StatusChoice(st, i18n.t("status.invoice." + st.name())));
        }

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        addFormRow(form, gbc, r++, i18n.t("invoice.from_supplier"), supplierPicker);
        addFormRow(form, gbc, r++, i18n.t("invoice.category") + " *", catCombo);
        addFormRow(form, gbc, r++, i18n.t("currency.label") + " *", curCombo);
        addFormRow(form, gbc, r++, i18n.t("invoice.number_optional"), invNoF);
        addFormRow(form, gbc, r++, i18n.t("invoice.partner") + " *", partnerF);
        addFormRow(form, gbc, r++, i18n.t("invoice.tax_number"), taxF);
        addFormRow(form, gbc, r++, i18n.t("invoice.address"), addressF);
        addFormRow(form, gbc, r++, i18n.t("invoice.email"), emailF);
        addFormRow(form, gbc, r++, i18n.t("invoice.item.description") + " *", descF);
        addFormRow(form, gbc, r++, i18n.t("invoice.item.qty") + " *", qtyF);
        addFormRow(form, gbc, r++, i18n.t("invoice.item.unit"), unitF);
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0; form.add(priceLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1; form.add(priceF, gbc);
        r++;
        addFormRow(form, gbc, r++, i18n.t("invoice.tax_type") + " *", taxCombo);
        addFormRow(form, gbc, r, i18n.t("common.status"), initStatusCombo);

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("invoice.new_incoming"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            FilterItem<InvoiceCategory> catItem = (FilterItem<InvoiceCategory>) catCombo.getSelectedItem();
            StatusChoice stChoice = (StatusChoice) initStatusCombo.getSelectedItem();
            TaxType chosenTax = taxCombo.getSelectedItem() instanceof TaxChoice tc ? tc.tax() : null;
            String chosenCur = curCombo.getSelectedItem() != null ? curCombo.getSelectedItem().toString() : "EUR";
            Long partnerId = supplierPicker.getSelectedItem() instanceof Customer sup ? sup.id() : null;

            BigDecimal qty = CurrencyCode.parseAmount(qtyF.getText());
            BigDecimal price = CurrencyCode.parseAmount(priceF.getText());
            Invoice created = ctx.getInvoiceService().createManualInvoice(
                    InvoiceDirection.INCOMING,
                    catItem != null ? catItem.value() : InvoiceCategory.OTHER_EXPENSE,
                    invNoF.getText(),
                    partnerId,
                    partnerF.getText(), taxF.getText(), addressF.getText(), emailF.getText(),
                    descF.getText(), qty, unitF.getText(), price, chosenTax, chosenCur,
                    LocalDate.now(), LocalDate.now().plusDays(14),
                    stChoice != null ? stChoice.status() : InvoiceStatus.ISSUED,
                    ctx.getSession().requireCurrentUser().id());
            refreshDataSelecting(created.id());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    // --- Tab 2: Tax Types & Currencies Settings CRUD ---

    private void openTaxDialog(TaxType existing) {
        I18nManager i18n = ctx.getI18n();
        JTextField codeF = new JTextField(existing != null ? existing.code() : "", 14);
        JTextField enF = new JTextField(existing != null ? existing.nameEn() : "", 22);
        JTextField huF = new JTextField(existing != null ? existing.nameHu() : "", 22);
        JTextField rateF = new JTextField(existing != null ? existing.ratePercent().stripTrailingZeros().toPlainString() : "0", 10);

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
        addFormRow(form, gbc, r++, i18n.t("common.code") + " *", codeF);
        addFormRow(form, gbc, r++, i18n.t("common.name.en") + " *", enF);
        addFormRow(form, gbc, r++, i18n.t("common.name.hu") + " *", huF);
        addFormRow(form, gbc, r++, i18n.t("invoice.settings.tax_rate") + " *", rateF);
        addFormRow(form, gbc, r++, i18n.t("invoice.settings.country_scope"), scopeCombo);
        if (existing != null) {
            gbc.gridx = 1; gbc.gridy = r; form.add(activeBox, gbc);
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
                throw new IllegalArgumentException("English and Hungarian names are required.");
            }
            if (existing == null) {
                ctx.getFinanceConfigDao().insertTaxType(codeF.getText(), enF.getText(), huF.getText(), rate, scope);
            } else {
                ctx.getFinanceConfigDao().updateTaxType(existing.id(), codeF.getText(), enF.getText(), huF.getText(),
                        rate, scope, activeBox.isSelected());
            }
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleToggleTax() {
        int r = taxTable.getSelectedRow();
        if (r < 0 || r >= allTaxTypes.size()) return;
        TaxType t = allTaxTypes.get(r);
        ctx.getFinanceConfigDao().updateTaxType(t.id(), t.code(), t.nameEn(), t.nameHu(),
                t.ratePercent(), t.countryScope(), !t.active());
        refreshData();
    }

    private void handleDeleteTax() {
        int r = taxTable.getSelectedRow();
        if (r < 0 || r >= allTaxTypes.size()) return;
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
            refreshData();
        }
    }

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
        addFormRow(form, gbc, r++, i18n.t("common.code") + " (ISO, e.g. USD) *", codeF);
        addFormRow(form, gbc, r++, i18n.t("common.name.en") + " *", enF);
        addFormRow(form, gbc, r++, i18n.t("common.name.hu") + " *", huF);
        addFormRow(form, gbc, r++, i18n.t("invoice.settings.symbol") + " *", symF);
        addFormRow(form, gbc, r++, i18n.t("invoice.settings.decimals"), decCombo);
        if (existing != null) {
            gbc.gridx = 1; gbc.gridy = r; form.add(activeBox, gbc);
        }

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("invoice.settings.currencies"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            if (enF.getText().trim().isEmpty() || huF.getText().trim().isEmpty()) {
                throw new IllegalArgumentException("English and Hungarian names are required.");
            }
            int dec = decCombo.getSelectedItem() instanceof Integer d ? d : 2;
            if (existing == null) {
                ctx.getFinanceConfigDao().insertCurrency(codeF.getText(), enF.getText(), huF.getText(), symF.getText(), dec);
            } else {
                ctx.getFinanceConfigDao().updateCurrency(existing.id(), codeF.getText(), enF.getText(), huF.getText(),
                        symF.getText(), dec, activeBox.isSelected());
            }
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleToggleCurrency() {
        int r = currencyTable.getSelectedRow();
        if (r < 0 || r >= allCurrencies.size()) return;
        CurrencyDef c = allCurrencies.get(r);
        ctx.getFinanceConfigDao().updateCurrency(c.id(), c.code(), c.nameEn(), c.nameHu(),
                c.symbol(), c.decimalPlaces(), !c.active());
        refreshData();
    }

    private void handleDeleteCurrency() {
        int r = currencyTable.getSelectedRow();
        if (r < 0 || r >= allCurrencies.size()) return;
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
            refreshData();
        }
    }

    private void handleExportCsv() {
        I18nManager i18n = ctx.getI18n();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n.t("common.export.csv"));
        chooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        chooser.setSelectedFile(new File("rewear-invoices.csv"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            Path target = file.getName().toLowerCase().endsWith(".csv")
                    ? file.toPath() : Path.of(file.getAbsolutePath() + ".csv");
            try {
                ctx.getCsvExportService().exportInvoices(filteredInvoices, target);
                JOptionPane.showMessageDialog(this, i18n.t("print.exported", target.toAbsolutePath()),
                        i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, String label, Component comp) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel(label + ":"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(comp, gbc);
    }

    private void populateFilterCombos() {
        updatingControls = true;
        try {
            I18nManager i18n = ctx.getI18n();
            directionFilter.removeAllItems();
            directionFilter.addItem(new FilterItem<>(null, i18n.t("invoice.direction") + ": " + i18n.t("common.all")));
            for (InvoiceDirection d : InvoiceDirection.values()) {
                directionFilter.addItem(new FilterItem<>(d, i18n.t("invoice.direction." + d.name())));
            }

            statusFilter.removeAllItems();
            statusFilter.addItem(new FilterItem<>(null, i18n.t("common.status") + ": " + i18n.t("common.all")));
            for (InvoiceStatus s : InvoiceStatus.values()) {
                statusFilter.addItem(new FilterItem<>(s, i18n.t("status.invoice." + s.name())));
            }

            categoryFilter.removeAllItems();
            categoryFilter.addItem(new FilterItem<>(null, i18n.t("invoice.category") + ": " + i18n.t("common.all")));
            for (InvoiceCategory c : InvoiceCategory.values()) {
                categoryFilter.addItem(new FilterItem<>(c, i18n.t("invoice.category." + c.name())));
            }

            statusCombo.removeAllItems();
            for (InvoiceStatus s : InvoiceStatus.values()) {
                statusCombo.addItem(new StatusChoice(s, i18n.t("status.invoice." + s.name())));
            }
        } finally {
            updatingControls = false;
        }
    }

    private void applyFiltersAndRender() {
        I18nManager i18n = ctx.getI18n();
        String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
        @SuppressWarnings("unchecked")
        InvoiceDirection selDir = directionFilter.getSelectedItem() instanceof FilterItem<?> fi
                ? (InvoiceDirection) fi.value() : null;
        @SuppressWarnings("unchecked")
        InvoiceStatus selStatus = statusFilter.getSelectedItem() instanceof FilterItem<?> fi
                ? (InvoiceStatus) fi.value() : null;
        @SuppressWarnings("unchecked")
        InvoiceCategory selCat = categoryFilter.getSelectedItem() instanceof FilterItem<?> fi
                ? (InvoiceCategory) fi.value() : null;

        List<Invoice> result = new ArrayList<>();
        for (Invoice inv : allInvoices) {
            if (selectedLedgerCurrency != null && !inv.effectiveCurrencyCode().equalsIgnoreCase(selectedLedgerCurrency)) {
                continue;
            }
            if (selDir != null && inv.direction() != selDir) continue;
            if (selStatus != null && inv.status() != selStatus) continue;
            if (selCat != null && inv.category() != selCat) continue;
            if (!q.isEmpty()) {
                String hay = (inv.invoiceNumber() + " " + inv.partnerName() + " " + inv.effectiveCurrencyCode() + " "
                        + inv.effectiveTaxTypeCode() + " "
                        + (inv.orderNumber() != null ? inv.orderNumber() : "")).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            result.add(inv);
        }
        filteredInvoices = result;

        // Pure per-currency P&L KPIs (NO cross-currency FX conversion!)
        FinancialSummary summary = ctx.getInvoiceService().computeSummary(filteredInvoices);
        if (selectedLedgerCurrency != null) {
            int dec = ctx.getFinanceConfigDao().decimalPlacesFor(selectedLedgerCurrency);
            FinancialSummary.CurrencyTotals t = summary.forCurrency(selectedLedgerCurrency, dec);
            kpiValIncome.setText("+" + CurrencyDef.formatAmount(t.totalIncome(), t.currency(), t.decimalPlaces()));
            kpiSubIncome.setText(i18n.t("invoice.kpi.paid_sub",
                    CurrencyDef.formatAmount(t.paidIncome(), t.currency(), t.decimalPlaces())));

            kpiValExpense.setText("-" + CurrencyDef.formatAmount(t.totalExpenses(), t.currency(), t.decimalPlaces()));
            kpiSubExpense.setText(i18n.t("invoice.kpi.paid_sub",
                    CurrencyDef.formatAmount(t.paidExpenses(), t.currency(), t.decimalPlaces())));

            kpiValBalance.setText(CurrencyDef.formatSignedAmount(t.netBalance(), t.currency(), t.decimalPlaces()));
            kpiSubBalance.setText(t.currency() + "  |  " + i18n.t("invoice.direction.short.OUTGOING")
                    + " / " + i18n.t("invoice.direction.short.INCOMING"));

            kpiValUnpaid.setText(CurrencyDef.formatAmount(t.unpaidAmount(), t.currency(), t.decimalPlaces()));
            kpiSubUnpaid.setText(i18n.t("invoice.kpi.unpaid_sub", t.unpaidCount()));
        } else {
            // Show separate clean lines per currency that has non-zero activity (or first 2 active)
            List<FinancialSummary.CurrencyTotals> visible = summary.byCurrency().values().stream()
                    .filter(t -> t.totalIncome().signum() != 0 || t.totalExpenses().signum() != 0 || t.unpaidCount() > 0)
                    .toList();
            if (visible.isEmpty()) {
                visible = summary.byCurrency().values().stream().limit(2).toList();
            }
            kpiValIncome.setText(buildMultiCurrencyHtml(visible, t ->
                    "+" + CurrencyDef.formatAmount(t.totalIncome(), t.currency(), t.decimalPlaces())));
            kpiSubIncome.setText(buildMultiCurrencyInline(visible, t ->
                    CurrencyDef.formatAmount(t.paidIncome(), t.currency(), t.decimalPlaces()), i18n.t("status.invoice.PAID")));

            kpiValExpense.setText(buildMultiCurrencyHtml(visible, t ->
                    "-" + CurrencyDef.formatAmount(t.totalExpenses(), t.currency(), t.decimalPlaces())));
            kpiSubExpense.setText(buildMultiCurrencyInline(visible, t ->
                    CurrencyDef.formatAmount(t.paidExpenses(), t.currency(), t.decimalPlaces()), i18n.t("status.invoice.PAID")));

            kpiValBalance.setText(buildMultiCurrencyHtml(visible, t ->
                    CurrencyDef.formatSignedAmount(t.netBalance(), t.currency(), t.decimalPlaces())));
            kpiSubBalance.setText(i18n.t("invoice.direction.short.OUTGOING") + " / "
                    + i18n.t("invoice.direction.short.INCOMING"));

            int totalUnpaidCount = visible.stream().mapToInt(FinancialSummary.CurrencyTotals::unpaidCount).sum();
            kpiValUnpaid.setText(buildMultiCurrencyHtml(visible, t ->
                    CurrencyDef.formatAmount(t.unpaidAmount(), t.currency(), t.decimalPlaces())));
            kpiSubUnpaid.setText(i18n.t("invoice.kpi.unpaid_sub", totalUnpaidCount));
        }

        tableModel.setRowCount(0);
        for (Invoice inv : filteredInvoices) {
            String cur = inv.effectiveCurrencyCode();
            int dec = ctx.getFinanceConfigDao().decimalPlacesFor(cur);
            String vatLabel = inv.effectiveTaxTypeCode() + " (" + inv.vatRate().stripTrailingZeros().toPlainString() + "%)";
            tableModel.addRow(new Object[]{
                    inv.invoiceNumber(),
                    inv.direction(),
                    inv.partnerName(),
                    i18n.t("invoice.category." + inv.category().name()),
                    inv.orderNumber() != null ? inv.orderNumber() : "—",
                    inv.issueDate().toString(),
                    cur,
                    vatLabel,
                    CurrencyDef.formatAmount(inv.netAmount(), cur, dec),
                    CurrencyDef.formatAmount(inv.grossAmount(), cur, dec),
                    inv.status()
            });
        }
        if (table.getColumnCount() >= 11) {
            table.getColumnModel().getColumn(1).setCellRenderer(new DirectionCellRenderer());
            table.getColumnModel().getColumn(10).setCellRenderer(new InvoiceStatusCellRenderer());
        }
        syncSelectedInvoiceControls();
    }

    private static String buildMultiCurrencyHtml(List<FinancialSummary.CurrencyTotals> list,
                                                 java.util.function.Function<FinancialSummary.CurrencyTotals, String> fmt) {
        if (list.isEmpty()) return "—";
        if (list.size() == 1) return fmt.apply(list.get(0));
        StringBuilder sb = new StringBuilder("<html>");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append("<br>");
            sb.append(fmt.apply(list.get(i)));
        }
        sb.append("</html>");
        return sb.toString();
    }

    private static String buildMultiCurrencyInline(List<FinancialSummary.CurrencyTotals> list,
                                                   java.util.function.Function<FinancialSummary.CurrencyTotals, String> fmt,
                                                   String prefix) {
        if (list.isEmpty()) return " ";
        StringBuilder sb = new StringBuilder(prefix).append(": ");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(fmt.apply(list.get(i)));
        }
        return sb.toString();
    }

    private void refreshSettingsTables() {
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
    }

    private void refreshDataSelecting(long invoiceId) {
        allInvoices = ctx.getInvoiceDao().findAll();
        rebuildCurrencyLedgerBar();
        applyFiltersAndRender();
        for (int i = 0; i < filteredInvoices.size(); i++) {
            if (filteredInvoices.get(i).id() == invoiceId) {
                table.setRowSelectionInterval(i, i);
                break;
            }
        }
        syncSelectedInvoiceControls();
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_INVOICES;
    }

    @Override
    public String getTitleI18nKey() {
        return "invoice.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        refreshSettingsTables();
        rebuildCurrencyLedgerBar();
        allInvoices = ctx.getInvoiceDao().findAll();
        applyFiltersAndRender();
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        tabs.setTitleAt(0, i18n.t("invoice.tab.list").toUpperCase(Locale.ROOT));
        tabs.setTitleAt(1, i18n.t("invoice.tab.settings").toUpperCase(Locale.ROOT));

        currencyLedgerLabel.setText(i18n.t("invoice.currency_view").toUpperCase(Locale.ROOT) + ":");
        kpiTitleIncome.setText(i18n.t("invoice.kpi.income").toUpperCase(Locale.ROOT));
        kpiTitleExpense.setText(i18n.t("invoice.kpi.expenses").toUpperCase(Locale.ROOT));
        kpiTitleBalance.setText(i18n.t("invoice.kpi.balance").toUpperCase(Locale.ROOT));
        kpiTitleUnpaid.setText(i18n.t("invoice.kpi.unpaid").toUpperCase(Locale.ROOT));

        refreshBtn.setText(i18n.t("common.refresh").toUpperCase(Locale.ROOT));
        newOutgoingBtn.setText("+ " + i18n.t("invoice.new_outgoing").toUpperCase(Locale.ROOT));
        newIncomingBtn.setText("+ " + i18n.t("invoice.new_incoming").toUpperCase(Locale.ROOT));
        applyStatusBtn.setText(i18n.t("invoice.status_update").toUpperCase(Locale.ROOT));
        previewDocBtn.setText(i18n.t("invoice.preview_btn").toUpperCase(Locale.ROOT));
        exportCsvBtn.setText(i18n.t("common.export.csv").toUpperCase(Locale.ROOT));

        taxSectionTitle.setText(i18n.t("invoice.settings.taxes").toUpperCase(Locale.ROOT));
        addTaxBtn.setText("+ " + i18n.t("common.add").toUpperCase(Locale.ROOT));
        editTaxBtn.setText(i18n.t("common.edit").toUpperCase(Locale.ROOT));
        toggleTaxBtn.setText(i18n.t("common.activate").toUpperCase(Locale.ROOT) + " / " + i18n.t("common.deactivate").toUpperCase(Locale.ROOT));
        deleteTaxBtn.setText(i18n.t("common.delete").toUpperCase(Locale.ROOT));

        currencySectionTitle.setText(i18n.t("invoice.settings.currencies").toUpperCase(Locale.ROOT));
        addCurrencyBtn.setText("+ " + i18n.t("common.add").toUpperCase(Locale.ROOT));
        editCurrencyBtn.setText(i18n.t("common.edit").toUpperCase(Locale.ROOT));
        toggleCurrencyBtn.setText(i18n.t("common.activate").toUpperCase(Locale.ROOT) + " / " + i18n.t("common.deactivate").toUpperCase(Locale.ROOT));
        deleteCurrencyBtn.setText(i18n.t("common.delete").toUpperCase(Locale.ROOT));

        populateFilterCombos();

        tableModel.setColumnIdentifiers(new Object[]{
                i18n.t("invoice.number").toUpperCase(Locale.ROOT),
                i18n.t("invoice.direction").toUpperCase(Locale.ROOT),
                i18n.t("invoice.partner").toUpperCase(Locale.ROOT),
                i18n.t("invoice.category").toUpperCase(Locale.ROOT),
                i18n.t("orders.number").toUpperCase(Locale.ROOT),
                i18n.t("invoice.issue_date").toUpperCase(Locale.ROOT),
                i18n.t("currency.label").toUpperCase(Locale.ROOT),
                i18n.t("invoice.vat").toUpperCase(Locale.ROOT),
                i18n.t("invoice.net_amount").toUpperCase(Locale.ROOT),
                i18n.t("invoice.gross_amount").toUpperCase(Locale.ROOT),
                i18n.t("common.status").toUpperCase(Locale.ROOT)
        });

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

        refreshData();
    }

    @Override
    public void applyTheme() {
        setBackground(ThemeManager.bg());
        invoicesTab.setBackground(ThemeManager.bg());
        settingsTab.setBackground(ThemeManager.bg());

        BrutalistStyle.applyBoxCard(currencyLedgerBar, 6, 10);
        currencyLedgerLabel.setForeground(ThemeManager.fg());
        styleCurrencyLedgerButtons();

        for (JPanel card : List.of(kpiCardIncome, kpiCardExpense, kpiCardBalance, kpiCardUnpaid)) {
            BrutalistStyle.applyBoxCard(card, 12, 14);
        }
        for (JLabel t : List.of(kpiTitleIncome, kpiTitleExpense, kpiTitleBalance, kpiTitleUnpaid,
                kpiSubIncome, kpiSubExpense, kpiSubBalance, kpiSubUnpaid)) {
            t.setForeground(ThemeManager.muted());
        }
        for (JLabel v : List.of(kpiValIncome, kpiValExpense, kpiValBalance, kpiValUnpaid)) {
            v.setForeground(ThemeManager.fg());
        }
        BrutalistStyle.applyBoxCard(filterCard, 6, 8);
        BrutalistStyle.stylePrimaryButton(newOutgoingBtn);
        BrutalistStyle.styleSecondaryButton(newIncomingBtn);
        BrutalistStyle.styleSecondaryButton(applyStatusBtn);
        BrutalistStyle.stylePrimaryButton(previewDocBtn);
        BrutalistStyle.styleSecondaryButton(exportCsvBtn);
        BrutalistStyle.styleSecondaryButton(refreshBtn);
        BrutalistStyle.styleTable(table);

        BrutalistStyle.applyBoxCard(taxCard, 10, 12);
        BrutalistStyle.applyBoxCard(currencyCard, 10, 12);
        taxSectionTitle.setForeground(ThemeManager.fg());
        currencySectionTitle.setForeground(ThemeManager.fg());
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

        table.repaint();
        taxTable.repaint();
        currencyTable.repaint();
    }

    private final class DirectionCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int col) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, col);
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            lbl.setFont(ThemeManager.monoFont(Font.BOLD, 10.5f));
            if (value instanceof InvoiceDirection dir) {
                lbl.setText("[ " + ctx.getI18n().t("invoice.direction.short." + dir.name()) + " ]");
                if (!isSelected) {
                    boolean out = dir == InvoiceDirection.OUTGOING;
                    lbl.setBackground(out ? ThemeManager.fg() : ThemeManager.surface());
                    lbl.setForeground(out ? ThemeManager.bg() : ThemeManager.fg());
                }
            }
            return lbl;
        }
    }

    private final class InvoiceStatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int col) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, col);
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            lbl.setFont(ThemeManager.monoFont(Font.BOLD, 10.5f));
            if (value instanceof InvoiceStatus st) {
                lbl.setText("[ " + ctx.getI18n().t("status.invoice." + st.name()).toUpperCase(Locale.ROOT) + " ]");
                if (!isSelected) {
                    boolean solid = st == InvoiceStatus.PAID;
                    lbl.setBackground(solid ? ThemeManager.fg() : ThemeManager.surface());
                    lbl.setForeground(solid ? ThemeManager.bg() : ThemeManager.fg());
                }
            }
            return lbl;
        }
    }
}
