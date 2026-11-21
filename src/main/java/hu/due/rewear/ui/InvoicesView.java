package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.FinancialSummary;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceItem;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.Order;
import hu.due.rewear.service.DocumentPageSource;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
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
import java.awt.Dimension;
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
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Brutalist Monochrome Invoices & Financial Overview screen supporting both
 * Outgoing Invoices (revenue, linked to orders or manual) and Incoming Invoices (warehouse expenses),
 * with live Income / Expenses / Net Balance P&L KPIs, reversible status changes, A4 PDF Preview/Print/Email,
 * and CSV export.
 */
public final class InvoicesView extends JPanel implements NavigableView {

    private final AppContext ctx;

    // Currency & Exchange Rate Control Bar
    private final JPanel fxBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 5));
    private final JLabel reportingCurLabel = new JLabel();
    private final JButton reportHufBtn = new JButton("HUF");
    private final JButton reportEurBtn = new JButton("EUR");
    private final JLabel fxRateLabel = new JLabel();
    private final JTextField fxRateField = new JTextField("400.00", 7);
    private final JLabel fxSuffixLabel = new JLabel("HUF");
    private final JButton saveFxBtn = new JButton();

    // 4 Financial KPI Cards
    private final JPanel kpiCardIncome = new JPanel();
    private final JPanel kpiCardExpense = new JPanel();
    private final JPanel kpiCardBalance = new JPanel();
    private final JPanel kpiCardUnpaid = new JPanel();

    private final JLabel kpiTitleIncome = new JLabel();
    private final JLabel kpiTitleExpense = new JLabel();
    private final JLabel kpiTitleBalance = new JLabel();
    private final JLabel kpiTitleUnpaid = new JLabel();

    private final JLabel kpiValIncome = new JLabel("0 HUF");
    private final JLabel kpiValExpense = new JLabel("0 HUF");
    private final JLabel kpiValBalance = new JLabel("0 HUF");
    private final JLabel kpiValUnpaid = new JLabel("0 HUF");

    private final JLabel kpiSubIncome = new JLabel(" ");
    private final JLabel kpiSubExpense = new JLabel(" ");
    private final JLabel kpiSubBalance = new JLabel(" ");
    private final JLabel kpiSubUnpaid = new JLabel(" ");

    // Filters & Actions
    private final JPanel filterCard = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 5));
    private final JTextField searchField = new JTextField(16);
    private final JComboBox<FilterItem<InvoiceDirection>> directionFilter = new JComboBox<>();
    private final JComboBox<FilterItem<CurrencyCode>> currencyFilter = new JComboBox<>();
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

    private final DefaultTableModel tableModel = new DefaultTableModel() {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(tableModel);

    private List<Invoice> allInvoices = List.of();
    private List<Invoice> filteredInvoices = List.of();
    private boolean updatingControls = false;

    private record FilterItem<T>(T value, String label) {
        @Override public String toString() { return label; }
    }

    private record StatusChoice(InvoiceStatus status, String label) {
        @Override public String toString() { return label; }
    }

    public InvoicesView(AppContext ctx) {
        this.ctx = ctx;

        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        buildUi();
        wireEvents();
        applyTranslations();
        applyTheme();
        refreshData();
    }

    private void buildUi() {
        JPanel northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.setOpaque(false);

        // 0. Reporting Currency & Exchange Rate Strip
        fxBar.setAlignmentX(LEFT_ALIGNMENT);
        reportingCurLabel.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        fxRateLabel.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        fxSuffixLabel.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        fxRateField.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        fxBar.add(reportingCurLabel);
        fxBar.add(reportHufBtn);
        fxBar.add(reportEurBtn);
        fxBar.add(Box.createHorizontalStrut(12));
        fxBar.add(fxRateLabel);
        fxBar.add(fxRateField);
        fxBar.add(fxSuffixLabel);
        fxBar.add(saveFxBtn);
        northStack.add(fxBar);
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
        filterCard.add(currencyFilter);
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

        add(northStack, BorderLayout.NORTH);

        // 4. Invoices Table
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        BrutalistStyle.styleTable(table);
        add(new JScrollPane(table), BorderLayout.CENTER);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                fxBar.revalidate();
                filterCard.revalidate();
                actionBar.revalidate();
                northStack.revalidate();
            }
        });
    }

    private static void configureKpiCard(JPanel card, JLabel title, JLabel val, JLabel sub) {
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        title.setFont(ThemeManager.monoFont(Font.BOLD, 10.5f));
        val.setFont(ThemeManager.monoFont(Font.BOLD, 20f));
        sub.setFont(ThemeManager.monoFont(Font.PLAIN, 10.5f));
        card.add(title);
        card.add(Box.createVerticalStrut(4));
        card.add(val);
        card.add(Box.createVerticalStrut(3));
        card.add(sub);
    }

    private void wireEvents() {
        reportHufBtn.addActionListener(e -> switchReportingCurrency(CurrencyCode.HUF));
        reportEurBtn.addActionListener(e -> switchReportingCurrency(CurrencyCode.EUR));
        saveFxBtn.addActionListener(e -> handleSaveFxRate());
        fxRateField.addActionListener(e -> handleSaveFxRate());

        searchField.addActionListener(e -> applyFiltersAndRender());
        directionFilter.addActionListener(e -> { if (!updatingControls) applyFiltersAndRender(); });
        currencyFilter.addActionListener(e -> { if (!updatingControls) applyFiltersAndRender(); });
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
    }

    private void switchReportingCurrency(CurrencyCode target) {
        ctx.getInvoiceService().setReportingCurrency(target);
        styleReportingButtons();
        applyFiltersAndRender();
    }

    private void handleSaveFxRate() {
        I18nManager i18n = ctx.getI18n();
        try {
            BigDecimal rate = CurrencyCode.parseAmount(fxRateField.getText());
            ctx.getInvoiceService().updateEurToHufRate(rate);
            BigDecimal saved = ctx.getInvoiceService().getCurrentEurToHufRate();
            fxRateField.setText(CurrencyCode.EUR.formatPlain(saved));
            applyFiltersAndRender();
            JOptionPane.showMessageDialog(this, i18n.t("currency.fx_saved", CurrencyCode.EUR.formatPlain(saved)),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void styleReportingButtons() {
        CurrencyCode active = ctx.getInvoiceService().getReportingCurrency();
        if (active == CurrencyCode.HUF) {
            BrutalistStyle.stylePrimaryButton(reportHufBtn);
            BrutalistStyle.styleSecondaryButton(reportEurBtn);
        } else {
            BrutalistStyle.styleSecondaryButton(reportHufBtn);
            BrutalistStyle.stylePrimaryButton(reportEurBtn);
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

        // If this invoice is linked to an Order, also offer its Delivery Note right in the preview checkboxes!
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

    private void openNewOutgoingDialog() {
        I18nManager i18n = ctx.getI18n();
        List<Order> orders = ctx.getOrderDao().findAll();
        JComboBox<Object> orderPicker = new JComboBox<>();
        orderPicker.addItem(i18n.t("invoice.manual_entry"));
        for (Order o : orders) {
            orderPicker.addItem(o);
        }

        JComboBox<CurrencyCode> curCombo = new JComboBox<>(CurrencyCode.values());
        curCombo.setSelectedItem(ctx.getInvoiceService().getReportingCurrency());
        JTextField partnerF = new JTextField(22);
        JTextField taxF = new JTextField(22);
        JTextField addressF = new JTextField(22);
        JTextField emailF = new JTextField(22);
        JTextField descF = new JTextField(22);
        JTextField qtyF = new JTextField("1", 10);
        JTextField unitF = new JTextField("kg", 8);
        JTextField priceF = new JTextField("0", 10);
        JTextField vatF = new JTextField("27", 8);
        JLabel priceLabel = new JLabel(i18n.t("invoice.item.unit_price") + " (" + curCombo.getSelectedItem() + ") *:");

        curCombo.addActionListener(e -> priceLabel.setText(
                i18n.t("invoice.item.unit_price") + " (" + curCombo.getSelectedItem() + ") *:"));

        JTextField[] autoFields = {partnerF, taxF, addressF, emailF, descF, qtyF, unitF, priceF};

        orderPicker.addActionListener(e -> {
            if (orderPicker.getSelectedItem() instanceof Order ord) {
                CurrencyCode ordCur = ord.effectiveCurrency();
                curCombo.setSelectedItem(ordCur);
                curCombo.setEnabled(false);

                Customer cust = ctx.getCustomerDao().findById(ord.customerId()).orElse(null);
                partnerF.setText(cust != null ? cust.name() : ord.customerName());
                taxF.setText(cust != null && cust.taxNumber() != null ? cust.taxNumber() : "");
                addressF.setText(cust != null && cust.address() != null ? cust.address() : "");
                emailF.setText(cust != null && cust.email() != null ? cust.email() : "");

                var ordItems = ctx.getOrderItemDao().findByOrderId(ord.id());
                String lang = i18n.getActiveLanguage();
                if (ordItems.size() == 1) {
                    var item = ordItems.get(0);
                    descF.setText(item.packageQrCode() + " (" + item.localizedSummary(lang) + ")");
                } else {
                    descF.setText(ord.orderNumber() + " — " + ordItems.size() + " "
                            + ("hu".equalsIgnoreCase(lang) ? "csomag" : "package(s)")
                            + " (" + ordCur.format(ord.totalPrice()) + ")");
                }

                BigDecimal weight = ord.totalWeightKg() != null ? ord.totalWeightKg() : BigDecimal.ZERO;
                qtyF.setText(weight.stripTrailingZeros().toPlainString());
                unitF.setText("kg");

                if (weight.compareTo(BigDecimal.ZERO) > 0 && ord.totalPrice() != null) {
                    BigDecimal avgPerKg = ord.totalPrice().divide(weight, ordCur.scale(), java.math.RoundingMode.HALF_UP);
                    priceF.setText(ordCur.formatPlain(avgPerKg));
                } else {
                    priceF.setText(ordCur.formatPlain(BigDecimal.ZERO));
                }

                ctx.getInvoiceDao().findByOrderId(ord.id()).ifPresentOrElse(
                        existingInv -> vatF.setText(existingInv.vatRate().stripTrailingZeros().toPlainString()),
                        () -> vatF.setText("27")
                );

                for (JTextField f : autoFields) {
                    f.setEditable(false);
                    f.setBackground(ThemeManager.surface());
                    f.setForeground(ThemeManager.fg());
                }
            } else {
                curCombo.setEnabled(true);
                curCombo.setSelectedItem(ctx.getInvoiceService().getReportingCurrency());
                partnerF.setText("");
                taxF.setText("");
                addressF.setText("");
                emailF.setText("");
                descF.setText("");
                qtyF.setText("1");
                unitF.setText("kg");
                priceF.setText("0");
                vatF.setText("27");
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
        addFormRow(form, gbc, r, i18n.t("invoice.vat_rate"), vatF);

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("invoice.new_outgoing"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            BigDecimal vatRate = CurrencyCode.parseAmount(vatF.getText());
            long userId = ctx.getSession().requireCurrentUser().id();
            Invoice created;
            if (orderPicker.getSelectedItem() instanceof Order chosenOrder) {
                created = ctx.getInvoiceService().ensureInvoiceForOrder(chosenOrder.id(), vatRate, userId);
            } else {
                CurrencyCode chosenCur = curCombo.getSelectedItem() instanceof CurrencyCode cc ? cc : CurrencyCode.EUR;
                BigDecimal qty = CurrencyCode.parseAmount(qtyF.getText());
                BigDecimal unitPrice = CurrencyCode.parseAmount(priceF.getText());
                created = ctx.getInvoiceService().createManualInvoice(
                        InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null,
                        partnerF.getText(), taxF.getText(), addressF.getText(), emailF.getText(),
                        descF.getText(), qty, unitF.getText(), unitPrice, vatRate, chosenCur,
                        LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.ISSUED, userId);
            }
            refreshDataSelecting(created.id());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openNewIncomingDialog() {
        I18nManager i18n = ctx.getI18n();
        JComboBox<FilterItem<InvoiceCategory>> catCombo = new JComboBox<>();
        for (InvoiceCategory cat : InvoiceCategory.values()) {
            if (cat.defaultDirection() == InvoiceDirection.INCOMING) {
                catCombo.addItem(new FilterItem<>(cat, i18n.t("invoice.category." + cat.name())));
            }
        }
        JComboBox<CurrencyCode> curCombo = new JComboBox<>(CurrencyCode.values());
        curCombo.setSelectedItem(ctx.getInvoiceService().getReportingCurrency());
        JTextField invNoF = new JTextField(22);
        JTextField partnerF = new JTextField(22);
        JTextField taxF = new JTextField(22);
        JTextField addressF = new JTextField(22);
        JTextField emailF = new JTextField(22);
        JTextField descF = new JTextField(22);
        JTextField qtyF = new JTextField("1", 10);
        JTextField unitF = new JTextField("db", 8);
        JTextField priceF = new JTextField("0", 10);
        JTextField vatF = new JTextField("27", 8);
        JLabel priceLabel = new JLabel(i18n.t("invoice.item.unit_price") + " (" + curCombo.getSelectedItem() + ") *:");
        curCombo.addActionListener(e -> priceLabel.setText(
                i18n.t("invoice.item.unit_price") + " (" + curCombo.getSelectedItem() + ") *:"));

        JComboBox<StatusChoice> initStatusCombo = new JComboBox<>();
        for (InvoiceStatus st : List.of(InvoiceStatus.ISSUED, InvoiceStatus.PAID, InvoiceStatus.DRAFT)) {
            initStatusCombo.addItem(new StatusChoice(st, i18n.t("status.invoice." + st.name())));
        }

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
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
        addFormRow(form, gbc, r++, i18n.t("invoice.vat_rate"), vatF);
        addFormRow(form, gbc, r, i18n.t("common.status"), initStatusCombo);

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("invoice.new_incoming"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            FilterItem<InvoiceCategory> catItem = (FilterItem<InvoiceCategory>) catCombo.getSelectedItem();
            StatusChoice stChoice = (StatusChoice) initStatusCombo.getSelectedItem();
            CurrencyCode chosenCur = curCombo.getSelectedItem() instanceof CurrencyCode cc ? cc : CurrencyCode.EUR;
            BigDecimal qty = CurrencyCode.parseAmount(qtyF.getText());
            BigDecimal price = CurrencyCode.parseAmount(priceF.getText());
            BigDecimal vatRate = CurrencyCode.parseAmount(vatF.getText());
            Invoice created = ctx.getInvoiceService().createManualInvoice(
                    InvoiceDirection.INCOMING,
                    catItem != null ? catItem.value() : InvoiceCategory.OTHER_EXPENSE,
                    invNoF.getText(),
                    partnerF.getText(), taxF.getText(), addressF.getText(), emailF.getText(),
                    descF.getText(), qty, unitF.getText(), price, vatRate, chosenCur,
                    LocalDate.now(), LocalDate.now().plusDays(14),
                    stChoice != null ? stChoice.status() : InvoiceStatus.ISSUED,
                    ctx.getSession().requireCurrentUser().id());
            refreshDataSelecting(created.id());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
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

            currencyFilter.removeAllItems();
            currencyFilter.addItem(new FilterItem<>(null, i18n.t("currency.label") + ": " + i18n.t("common.all")));
            for (CurrencyCode c : CurrencyCode.values()) {
                currencyFilter.addItem(new FilterItem<>(c, c.name()));
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
        CurrencyCode selCur = currencyFilter.getSelectedItem() instanceof FilterItem<?> fi
                ? (CurrencyCode) fi.value() : null;
        @SuppressWarnings("unchecked")
        InvoiceStatus selStatus = statusFilter.getSelectedItem() instanceof FilterItem<?> fi
                ? (InvoiceStatus) fi.value() : null;
        @SuppressWarnings("unchecked")
        InvoiceCategory selCat = categoryFilter.getSelectedItem() instanceof FilterItem<?> fi
                ? (InvoiceCategory) fi.value() : null;

        List<Invoice> result = new ArrayList<>();
        for (Invoice inv : allInvoices) {
            if (selDir != null && inv.direction() != selDir) continue;
            if (selCur != null && inv.currencyCode() != selCur) continue;
            if (selStatus != null && inv.status() != selStatus) continue;
            if (selCat != null && inv.category() != selCat) continue;
            if (!q.isEmpty()) {
                String hay = (inv.invoiceNumber() + " " + inv.partnerName() + " " + inv.currency() + " "
                        + (inv.orderNumber() != null ? inv.orderNumber() : "")).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            result.add(inv);
        }
        filteredInvoices = result;

        // Consolidated dual-currency P&L KPIs in active reporting currency + native EUR/HUF split
        FinancialSummary summary = ctx.getInvoiceService().computeSummary(filteredInvoices);
        CurrencyCode rep = summary.reportingCurrency();
        BigDecimal currentFx = ctx.getInvoiceService().getCurrentEurToHufRate();

        kpiValIncome.setText("+" + rep.format(summary.totalIncome()));
        kpiSubIncome.setText("EUR: " + CurrencyCode.EUR.formatNumber(summary.incomeEur())
                + "  |  HUF: " + CurrencyCode.HUF.formatNumber(summary.incomeHuf()));

        kpiValExpense.setText("-" + rep.format(summary.totalExpenses()));
        kpiSubExpense.setText("EUR: " + CurrencyCode.EUR.formatNumber(summary.expenseEur())
                + "  |  HUF: " + CurrencyCode.HUF.formatNumber(summary.expenseHuf()));

        kpiValBalance.setText(rep.formatSigned(summary.netBalance()));
        kpiSubBalance.setText(i18n.t("invoice.kpi.paid_sub",
                rep.formatSigned(summary.paidIncome().subtract(summary.paidExpenses())))
                + "  (@ " + CurrencyCode.EUR.formatPlain(currentFx) + ")");

        kpiValUnpaid.setText(rep.format(summary.unpaidAmount()));
        kpiSubUnpaid.setText(i18n.t("invoice.kpi.unpaid_sub", summary.unpaidCount())
                + "  |  EUR: " + CurrencyCode.EUR.formatNumber(summary.unpaidEur())
                + " / HUF: " + CurrencyCode.HUF.formatNumber(summary.unpaidHuf()));

        tableModel.setRowCount(0);
        for (Invoice inv : filteredInvoices) {
            CurrencyCode cur = inv.currencyCode();
            tableModel.addRow(new Object[]{
                    inv.invoiceNumber(),
                    inv.direction(),
                    inv.partnerName(),
                    i18n.t("invoice.category." + inv.category().name()),
                    inv.orderNumber() != null ? inv.orderNumber() : "—",
                    inv.issueDate().toString(),
                    inv.dueDate().toString(),
                    cur.name(),
                    cur.format(inv.netAmount()),
                    cur.format(inv.grossAmount()),
                    inv.status()
            });
        }
        if (table.getColumnCount() >= 11) {
            table.getColumnModel().getColumn(1).setCellRenderer(new DirectionCellRenderer());
            table.getColumnModel().getColumn(10).setCellRenderer(new InvoiceStatusCellRenderer());
        }
        syncSelectedInvoiceControls();
    }

    private void refreshDataSelecting(long invoiceId) {
        allInvoices = ctx.getInvoiceDao().findAll();
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
        fxRateField.setText(CurrencyCode.EUR.formatPlain(ctx.getInvoiceService().getCurrentEurToHufRate()));
        styleReportingButtons();
        allInvoices = ctx.getInvoiceDao().findAll();
        applyFiltersAndRender();
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        reportingCurLabel.setText(i18n.t("currency.reporting").toUpperCase(Locale.ROOT) + ":");
        fxRateLabel.setText(i18n.t("currency.fx_rate").toUpperCase(Locale.ROOT) + ": 1 EUR =");
        saveFxBtn.setText(i18n.t("currency.fx_save").toUpperCase(Locale.ROOT));

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

        populateFilterCombos();

        tableModel.setColumnIdentifiers(new Object[]{
                i18n.t("invoice.number").toUpperCase(Locale.ROOT),
                i18n.t("invoice.direction").toUpperCase(Locale.ROOT),
                i18n.t("invoice.partner").toUpperCase(Locale.ROOT),
                i18n.t("invoice.category").toUpperCase(Locale.ROOT),
                i18n.t("orders.number").toUpperCase(Locale.ROOT),
                i18n.t("invoice.issue_date").toUpperCase(Locale.ROOT),
                i18n.t("invoice.due_date").toUpperCase(Locale.ROOT),
                i18n.t("currency.label").toUpperCase(Locale.ROOT),
                i18n.t("invoice.net_amount").toUpperCase(Locale.ROOT),
                i18n.t("invoice.gross_amount").toUpperCase(Locale.ROOT),
                i18n.t("common.status").toUpperCase(Locale.ROOT)
        });

        refreshData();
    }

    @Override
    public void applyTheme() {
        setBackground(ThemeManager.bg());
        BrutalistStyle.applyBoxCard(fxBar, 6, 10);
        reportingCurLabel.setForeground(ThemeManager.fg());
        fxRateLabel.setForeground(ThemeManager.fg());
        fxSuffixLabel.setForeground(ThemeManager.fg());
        styleReportingButtons();
        BrutalistStyle.styleSecondaryButton(saveFxBtn);
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
        table.repaint();
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
