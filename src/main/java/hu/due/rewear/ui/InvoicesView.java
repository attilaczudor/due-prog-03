package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.FinancialSummary;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceItem;
import hu.due.rewear.model.InvoiceStatus;
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
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Brutalist Monochrome Invoices & Financial Overview workspace with two tabs:
 * <ul>
 *   <li><b>Tab 1 (Invoices):</b> strictly separated per-currency ledgers (HUF, EUR, USD, ...) with zero forced
 *       FX conversion, 4 KPI cards, filters, reversible status updates, A4 PDF Preview/Print/Email, and CSV export.</li>
 *   <li><b>Tab 2 (Invoice Settings):</b> delegated to {@link InvoiceSettingsPanel} for Tax Types and Currencies CRUD.</li>
 * </ul>
 * Creation dialogs are delegated to {@link InvoiceFormDialogs} to keep each UI unit focused and maintainable.
 */
public final class InvoicesView extends JPanel implements NavigableView {

    private final AppContext ctx;

    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel invoicesTab = new JPanel(new BorderLayout(0, 10));
    private final InvoiceSettingsPanel settingsPanel;

    // Separate per-currency ledger selector bar (no cross-currency conversion)
    private final JPanel currencyLedgerBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 5));
    private final JLabel currencyLedgerLabel = new JLabel();
    private final Map<String, JButton> ledgerButtons = new LinkedHashMap<>();
    private String selectedLedgerCurrency = null; // null == ALL (displayed as separate lines per currency)

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
    private final JButton sendReminderBtn = new JButton();
    private final JButton exportCsvBtn = new JButton();

    private final DefaultTableModel tableModel = BrutalistStyle.nonEditableTableModel();
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
        this.settingsPanel = new InvoiceSettingsPanel(ctx, this::refreshData);

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        buildInvoicesTab();
        tabs.addTab("", invoicesTab);
        tabs.addTab("", settingsPanel);
        add(tabs, BorderLayout.CENTER);

        wireEvents();
        applyTranslations();
        applyTheme();
    }

    private void buildInvoicesTab() {
        invoicesTab.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JPanel northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.setOpaque(false);

        // 0. Per-currency ledger switcher bar
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
        actionBar.add(sendReminderBtn);
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

        newOutgoingBtn.addActionListener(e -> InvoiceFormDialogs
                .showNewOutgoingDialog(this, ctx, selectedLedgerCurrency)
                .ifPresent(inv -> refreshDataSelecting(inv.id())));
        newIncomingBtn.addActionListener(e -> InvoiceFormDialogs
                .showNewIncomingDialog(this, ctx, selectedLedgerCurrency)
                .ifPresent(inv -> refreshDataSelecting(inv.id())));

        applyStatusBtn.addActionListener(e -> handleStatusUpdate());
        previewDocBtn.addActionListener(e -> openSelectedInvoicePreview());
        sendReminderBtn.addActionListener(e -> handleSendPaymentReminder());
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

        boolean stillValid = selectedLedgerCurrency == null;
        for (CurrencyDef cur : ctx.getFinanceConfigDao().findCurrencies(true)) {
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
        boolean canRemind = inv != null
                && inv.direction() == InvoiceDirection.OUTGOING
                && (inv.status() == InvoiceStatus.ISSUED || inv.status() == InvoiceStatus.OVERDUE);
        sendReminderBtn.setEnabled(canRemind);
        if (inv != null) {
            for (int i = 0; i < statusCombo.getItemCount(); i++) {
                if (statusCombo.getItemAt(i).status() == inv.status()) {
                    statusCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private void handleSendPaymentReminder() {
        Invoice inv = getSelectedInvoice();
        if (inv == null) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        if (inv.partnerEmail() == null || inv.partnerEmail().isBlank()) {
            JOptionPane.showMessageDialog(this, i18n.t("invoice.reminder.no_email"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            var company = ctx.getSettingsDao().getCompanyProfile();
            String mailto = ctx.getInvoiceService().buildPaymentReminderMailtoUri(inv, company, i18n);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MAIL)) {
                Desktop.getDesktop().mail(URI.create(mailto));
                JOptionPane.showMessageDialog(this, i18n.t("invoice.reminder.opened"),
                        i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, mailto,
                        i18n.t("invoice.reminder.btn"), JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleStatusUpdate() {
        Invoice inv = getSelectedInvoice();
        if (inv == null || !(statusCombo.getSelectedItem() instanceof StatusChoice choice) || inv.status() == choice.status()) {
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

        // When linked to an Order, also include its Delivery Note in the preview selection checkboxes
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

        renderKpis(ctx.getInvoiceService().computeSummary(filteredInvoices), i18n);
        renderTable(i18n);
        syncSelectedInvoiceControls();
    }

    private void renderKpis(FinancialSummary summary, I18nManager i18n) {
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
    }

    private void renderTable(I18nManager i18n) {
        tableModel.setRowCount(0);
        Map<String, Integer> decByCur = ctx.getFinanceConfigDao().decimalPlacesMap();
        for (Invoice inv : filteredInvoices) {
            String cur = inv.effectiveCurrencyCode();
            int dec = decByCur.getOrDefault(cur, CurrencyDef.defaultDecimalsFor(cur));
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
    }

    private static String buildMultiCurrencyHtml(List<FinancialSummary.CurrencyTotals> list,
                                                 Function<FinancialSummary.CurrencyTotals, String> fmt) {
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
                                                   Function<FinancialSummary.CurrencyTotals, String> fmt,
                                                   String prefix) {
        if (list.isEmpty()) return " ";
        StringBuilder sb = new StringBuilder(prefix).append(": ");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(fmt.apply(list.get(i)));
        }
        return sb.toString();
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
        ctx.getInvoiceService().syncOverdueStatuses();
        settingsPanel.refreshTables();
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
        sendReminderBtn.setText(i18n.t("invoice.reminder.btn").toUpperCase(Locale.ROOT));
        exportCsvBtn.setText(i18n.t("common.export.csv").toUpperCase(Locale.ROOT));

        populateFilterCombos();

        tableModel.setColumnIdentifiers(new Object[]{
                i18n.t("invoice.number").toUpperCase(Locale.ROOT),
                i18n.t("invoice.direction").toUpperCase(Locale.ROOT),
                i18n.t("invoice.partner").toUpperCase(Locale.ROOT),
                i18n.t("invoice.category").toUpperCase(Locale.ROOT),
                i18n.t("orders.order_number").toUpperCase(Locale.ROOT),
                i18n.t("invoice.issue_date").toUpperCase(Locale.ROOT),
                i18n.t("currency.label").toUpperCase(Locale.ROOT),
                i18n.t("invoice.vat").toUpperCase(Locale.ROOT),
                i18n.t("invoice.net_amount").toUpperCase(Locale.ROOT),
                i18n.t("invoice.gross_amount").toUpperCase(Locale.ROOT),
                i18n.t("common.status").toUpperCase(Locale.ROOT)
        });

        settingsPanel.applyTranslations();
        refreshData();
    }

    @Override
    public void applyTheme() {
        setBackground(ThemeManager.bg());
        invoicesTab.setBackground(ThemeManager.bg());

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
        BrutalistStyle.stylePrimaryButton(previewDocBtn);
        BrutalistStyle.styleSecondaryButtons(newIncomingBtn, applyStatusBtn, sendReminderBtn, exportCsvBtn, refreshBtn);
        BrutalistStyle.styleTable(table);

        settingsPanel.applyTheme();
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
                String label = ctx.getI18n().t("status.invoice." + st.name()).toUpperCase(Locale.ROOT);
                lbl.setText(st == InvoiceStatus.OVERDUE ? "[ ! " + label + " ]" : "[ " + label + " ]");
                if (!isSelected) {
                    boolean solid = st == InvoiceStatus.PAID || st == InvoiceStatus.OVERDUE;
                    lbl.setBackground(solid ? ThemeManager.fg() : ThemeManager.surface());
                    lbl.setForeground(solid ? ThemeManager.bg() : ThemeManager.fg());
                }
            }
            return lbl;
        }
    }
}
