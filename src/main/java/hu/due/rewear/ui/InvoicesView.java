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
import hu.due.rewear.model.SmartFolder;
import hu.due.rewear.model.SmartFolder.Field;
import hu.due.rewear.service.DocumentPageSource;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.KeyStroke;
import javax.swing.Box;
import javax.swing.JCheckBox;
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
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Finance workspace with a currency ledger, invoice folders, and per-invoice actions. */
public final class InvoicesView extends JPanel implements NavigableView {

    private static final String ALL_INVOICES = "all";

    private final AppContext ctx;
    private final WorkspaceFolderRail folders;
    private final JPanel listPanel = new JPanel(new BorderLayout(0, 10)) {
        @Override public void doLayout() {
            configureInvoiceColumns(getWidth());
            super.doLayout();
        }
    };
    private final JButton smartFoldersButton = new JButton(SidebarIcons.smartFolder(16));

    private final JButton toggleFilters = new JButton(SidebarIcons.filter(17)) {
        @Override protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (!filterCard.isVisible() && hasFilters()) {
                var dot = (java.awt.Graphics2D) g.create();
                dot.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                dot.setColor(new java.awt.Color(0xDC2626));
                dot.fillOval(getWidth() - 12, 4, 8, 8);
                dot.dispose();
            }
        }
    };
    private boolean filtersOpen;
    private String selectedFolder = ALL_INVOICES;
    private List<SmartFolder> smartFolders = List.of();
    private List<Invoice> ledgerInvoices = List.of();

    private final JPanel filterCard = new JPanel(new BorderLayout(0, 8));
    private final JPanel filterOptions = new JPanel(new GridBagLayout());
    private final JTextField searchField = new JTextField(16);
    private final FilterChoices<String> partnerFilter = new FilterChoices<>();
    private final JTextField partnerSearch = new JTextField(14);
    private final FilterChoices<String> currencyFilter = new FilterChoices<>();
    private final FilterChoices<InvoiceStatus> statusFilter = new FilterChoices<>();
    private final FilterChoices<InvoiceCategory> categoryFilter = new FilterChoices<>();
    private final FilterChoices<InvoiceDirection> directionFilter = new FilterChoices<>();
    private final JLabel directionLabel = new JLabel();
    private final JLabel searchLabel = new JLabel();
    private final JLabel partnerLabel = new JLabel();
    private final JLabel currencyLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JLabel categoryLabel = new JLabel();
    private final JButton clearFilters = new JButton();
    private final JButton saveFilterFolder = new JButton();
    private final JPanel saveFilterActions = new JPanel(new BorderLayout());
    private final JPanel filterActionDivider = new JPanel();
    private Map<String, InvoicePartnerOptions> partnerOptions = Map.of();
    private final JButton moreActions = new JButton(SidebarIcons.moreVertical(17));
    private final javax.swing.JPopupMenu exportMenu = new javax.swing.JPopupMenu();
    private final javax.swing.JMenuItem pnlReportItem = new javax.swing.JMenuItem();
    private final javax.swing.JMenuItem exportCsvItem = new javax.swing.JMenuItem();
    private final JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

    private final DefaultTableModel tableModel = BrutalistStyle.nonEditableTableModel();
    private final JTable table = new JTable(tableModel);
    private List<Integer> visibleColumns = List.of();

    private List<Invoice> allInvoices = List.of();
    private List<Invoice> filteredInvoices = List.of();
    private boolean updatingControls = false;

    private final class FilterChoices<T> extends JPanel {
        private final Map<T, javax.swing.JCheckBox> options = new LinkedHashMap<>();
        private final JCheckBox all = new JCheckBox();
        private final JPanel choices = new JPanel();
        private JLabel empty;

        void addSearch(JTextField input) {
            all.setText(ctx.getI18n().t("common.all"));
            var header = new JPanel(new BorderLayout(0, 4));
            header.add(all, BorderLayout.NORTH);
            header.add(input, BorderLayout.CENTER);
            add(header, BorderLayout.NORTH);
            var scroller = new JScrollPane(choices, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroller.setPreferredSize(new Dimension(0, 8 * all.getPreferredSize().height + 2));
            scroller.getVerticalScrollBar().setUnitIncrement(18);
            add(scroller, BorderLayout.CENTER);
            empty = new JLabel();
            empty.setVisible(false);
        }

        FilterChoices() {
            super(new BorderLayout(0, 4));
            setOpaque(false);
            choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
            add(all, BorderLayout.NORTH);
            add(choices, BorderLayout.CENTER);
            all.addActionListener(e -> {
                clear();
                filtersChanged();
            });
        }

        void rebuild(List<T> values, Function<T, String> label) {
            var previous = selected();
            options.clear();
            choices.removeAll();
            all.setText(ctx.getI18n().t("common.all"));
            for (T value : values) {
                var box = new javax.swing.JCheckBox(label.apply(value), previous.contains(value));
                box.setToolTipText(box.getText());
                var size = box.getPreferredSize();
                box.setPreferredSize(new Dimension(Math.min(190, size.width), size.height));
                box.setMaximumSize(new Dimension(Integer.MAX_VALUE, size.height));
                options.put(value, box);
                choices.add(box);
                box.addActionListener(e -> {
                    all.setSelected(selected().isEmpty());
                    filtersChanged();
                });
            }
            if (empty != null) {
                empty.setText(ctx.getI18n().t("smart.no_results"));
                choices.add(empty);
            }
            all.setSelected(selected().isEmpty());
            revalidate();
            repaint();
        }

        void search(String query, Function<T, String> details) {
            String term = InvoiceLookupField.normalize(query.strip());
            options.forEach((key, box) -> box.setVisible(
                    InvoiceLookupField.normalize(box.getText() + " " + details.apply(key)).contains(term)));
            empty.setVisible(options.values().stream().noneMatch(Component::isVisible));
            if (choices.getParent() instanceof javax.swing.JViewport viewport) {
                viewport.setViewPosition(new java.awt.Point());
            }
            revalidate();
            repaint();
        }

        java.util.Set<T> selected() {
            var selected = new java.util.LinkedHashSet<T>();
            options.forEach((value, box) -> {
                if (box.isSelected()) selected.add(value);
            });
            return selected;
        }

        void clear() {
            options.values().forEach(box -> box.setSelected(false));
            all.setSelected(true);
        }
    }

    private record StatusChoice(InvoiceStatus status, String label) {
        @Override public String toString() { return label; }
    }

    public InvoicesView(AppContext ctx) {
        this.ctx = ctx;
        this.folders = new WorkspaceFolderRail(ctx.getI18n(), "invoice.new", 236,
                this::createInvoice, this::selectFolder);

        setLayout(new BorderLayout(12, 0));
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        folders.addFooterButton(smartFoldersButton);
        add(folders, BorderLayout.WEST);

        buildInvoiceList();
        add(listPanel, BorderLayout.CENTER);
        headerActions.setOpaque(false);
        headerActions.add(moreActions);
        headerActions.add(toggleFilters);
        exportMenu.add(pnlReportItem);
        exportMenu.add(exportCsvItem);

        wireEvents();
        applyTranslations();
        applyTheme();
    }

    private void buildInvoiceList() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateColumnsFromModel(false);
        table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        BrutalistStyle.styleTable(table);
        listPanel.add(new JScrollPane(table), BorderLayout.CENTER);

        GridBagConstraints row = new GridBagConstraints();
        row.gridx = 0;
        row.gridy = 0;
        row.weightx = 1;
        row.fill = GridBagConstraints.HORIZONTAL;
        row.insets = new Insets(0, 0, 4, 0);
        addFilter(row, searchLabel, searchField);
        addFilter(row, currencyLabel, currencyFilter);
        partnerFilter.addSearch(partnerSearch);
        addFilter(row, partnerLabel, partnerFilter);
        addFilter(row, statusLabel, statusFilter);
        addFilter(row, directionLabel, directionFilter);
        addFilter(row, categoryLabel, categoryFilter);
        row.gridy++;
        row.weighty = 1;
        row.fill = GridBagConstraints.BOTH;
        filterOptions.add(Box.createVerticalGlue(), row);
        var filterScroll = new JScrollPane(filterOptions, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        filterScroll.setBorder(BorderFactory.createEmptyBorder());
        filterScroll.getVerticalScrollBar().setUnitIncrement(18);
        filterCard.add(filterScroll, BorderLayout.CENTER);
        var filterActions = new JPanel(new BorderLayout(0, 8));
        filterActions.setOpaque(false);
        filterActions.add(clearFilters, BorderLayout.NORTH);
        saveFilterActions.setOpaque(false);
        saveFilterActions.add(filterActionDivider, BorderLayout.NORTH);
        saveFilterActions.add(saveFilterFolder, BorderLayout.CENTER);
        filterActions.add(saveFilterActions, BorderLayout.CENTER);
        filterCard.add(filterActions, BorderLayout.SOUTH);
        filterCard.setPreferredSize(new Dimension(272, 0));
        filterCard.setVisible(false);
        add(filterCard, BorderLayout.EAST);
    }

    private void sizeFilterCard() {
        Insets padding = filterCard.getInsets();
        int buttonWidth = saveFilterFolder.getPreferredSize().width + padding.left + padding.right;
        filterCard.setPreferredSize(new Dimension(Math.max(272, buttonWidth), 0));
    }

    private void addFilter(GridBagConstraints row, JLabel label, JComponent field) {
        label.setLabelFor(field);
        filterOptions.add(label, row);
        row.gridy++;
        row.insets = new Insets(0, 0, 10, 0);
        filterOptions.add(field, row);
        row.gridy++;
        row.insets = new Insets(0, 0, 4, 0);
    }

    private void configureInvoiceColumns(int width) {
        if (tableModel.getColumnCount() < 11) return;
        List<Integer> columns = width > 0 && width < 375 ? List.of(0, 2, 9)
                : width > 0 && width < 600 ? List.of(0, 2, 9, 10)
                : width > 0 && width < 900 ? List.of(0, 1, 2, 5, 9, 10)
                : List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        if (visibleColumns.equals(columns)) return;
        visibleColumns = columns;
        var model = table.getColumnModel();
        while (model.getColumnCount() > 0) model.removeColumn(model.getColumn(0));
        int[] widths = {156, 113, 175, 120, 141, 107, 68, 128, 102, 111, 104};
        for (int index : columns) {
            var column = new TableColumn(index, widths[index]);
            column.setHeaderValue(tableModel.getColumnName(index));
            if (index == 1) column.setCellRenderer(new DirectionCellRenderer());
            if (index == 10) column.setCellRenderer(new InvoiceStatusCellRenderer());
            model.addColumn(column);
        }
    }

    private void wireEvents() {
        SmartFolderEditor.onTextChange(searchField, this::filtersChanged);
        SmartFolderEditor.onTextChange(partnerSearch, this::updatePartnerSearch);
        saveFilterFolder.addActionListener(e -> SmartFolderEditor.createFromInvoiceFilters(
                this, ctx, allInvoices, currentFilterRules(), id -> selectFolder(WorkspaceFolderRail.smartKey(id))));
        clearFilters.addActionListener(e -> {
            resetFilters();
            applyFiltersAndRender();
        });
        toggleFilters.addActionListener(e -> {
            filtersOpen = !filtersOpen;
            updateDrawer();
            if (filtersOpen) searchField.requestFocusInWindow();
        });
        moreActions.addActionListener(e -> exportMenu.show(moreActions,
                moreActions.getWidth() - exportMenu.getPreferredSize().width, moreActions.getHeight() + 6));
        smartFoldersButton.addActionListener(e -> createSmartFolder());
        folders.setFolderEditor(folder -> SmartFolderEditor.show(this, ctx, SmartFolder.Scope.INVOICE,
                folder, allInvoices, id -> {
                    if (id == 0 && !WorkspaceFolderRail.smartKey(folder.id()).equals(selectedFolder)) {
                        refreshData();
                    } else {
                        selectFolder(id == 0 ? ALL_INVOICES : WorkspaceFolderRail.smartKey(id));
                    }
                }));
        pnlReportItem.addActionListener(e -> handlePreviewPnlReport());
        exportCsvItem.addActionListener(e -> handleExportCsv());

        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                if (row >= 0 && event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    table.setRowSelectionInterval(row, row);
                    openInvoiceDetails();
                }
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "invoiceDetails");
        table.getActionMap().put("invoiceDetails", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { openInvoiceDetails(); }
        });
    }

    private void createSmartFolder() {
        SmartFolderEditor.show(this, ctx, SmartFolder.Scope.INVOICE, null, allInvoices,
                id -> selectFolder(WorkspaceFolderRail.smartKey(id)));
    }

    private void createInvoice() {
        var i18n = ctx.getI18n();
        String[] choices = {i18n.t("invoice.direction.OUTGOING"), i18n.t("invoice.direction.INCOMING"),
                i18n.t("common.cancel")};
        int chosen = JOptionPane.showOptionDialog(this, i18n.t("invoice.choose_direction"),
                i18n.t("invoice.new"), JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
                null, choices, choices[0]);
        if (chosen == 0 || chosen == 1) {
            var created = chosen == 0 ? InvoiceFormDialogs.showNewOutgoingDialog(this, ctx, singleCurrency())
                    : InvoiceFormDialogs.showNewIncomingDialog(this, ctx, singleCurrency());
            created.ifPresent(invoice -> {
                selectFolder(ALL_INVOICES);
                refreshDataSelecting(invoice.id());
                if (invoice.orderId() != null) openInvoiceDetails(invoice);
            });
        }
    }

    private SmartFolder currentSmartFolder() {
        return smartFolders.stream().filter(folder -> WorkspaceFolderRail.smartKey(folder.id()).equals(selectedFolder))
                .findFirst().orElse(null);
    }

    private void selectFolder(String key) {
        selectedFolder = key;
        loadFolders();
        resetFilters();
        refreshFolderRail();
        applyFiltersAndRender();
    }

    private void loadFolders() {
        smartFolders = ctx.getSession().getCurrentUser().map(user ->
                ctx.getSmartFolderDao().find(user.id(), SmartFolder.Scope.INVOICE)).orElse(List.of());
        if (selectedFolder.startsWith("smart:") && currentSmartFolder() == null) {
            selectedFolder = ALL_INVOICES;
        }
    }

    private void refreshFolderRail() {
        var i18n = ctx.getI18n();
        folders.setEntries(List.of(new WorkspaceFolderRail.Entry(ALL_INVOICES,
                i18n.t("invoice.folder.all").toUpperCase(Locale.ROOT), SidebarIcons.forView(MainFrame.VIEW_INVOICES, 16))),
                smartFolders, selectedFolder);
    }

    private void rebuildCurrencyOptions() {
        var currencies = java.util.stream.Stream.concat(
                ctx.getFinanceConfigDao().findCurrencies(true).stream().map(CurrencyDef::code),
                allInvoices.stream().map(Invoice::effectiveCurrencyCode)).distinct().toList();
        currencyFilter.rebuild(currencies, Function.identity());
    }

    private void rebuildPartnerOptions() {
        partnerOptions = InvoicePartnerOptions.load(ctx, allInvoices);
        partnerFilter.rebuild(List.copyOf(partnerOptions.keySet()), key -> partnerOptions.get(key).name());
        updatePartnerSearch();
    }

    private void updatePartnerSearch() {
        partnerFilter.search(partnerSearch.getText(), key -> partnerOptions.get(key).details());
    }

    private Map<Field, List<String>> currentFilterRules() {
        var rules = new EnumMap<Field, List<String>>(Field.class);
        rules.put(Field.SEARCH, SmartFolder.parseSearchTerms(searchField.getText()));
        rules.put(Field.CURRENCY, List.copyOf(currencyFilter.selected()));
        rules.put(Field.PARTNER, InvoicePartnerOptions.ruleKeys(partnerOptions, partnerFilter.selected()));
        rules.put(Field.DIRECTION, directionFilter.selected().stream().map(Enum::name).toList());
        rules.put(Field.STATUS, statusFilter.selected().stream().map(Enum::name).toList());
        rules.put(Field.CATEGORY, categoryFilter.selected().stream().map(Enum::name).toList());
        rules.values().removeIf(List::isEmpty);
        return rules;
    }

    private String singleCurrency() {
        var codes = currencyFilter.selected();
        return codes.size() == 1 ? codes.iterator().next() : null;
    }

    private void updateDrawer() {
        filterCard.setVisible(filtersOpen);
        styleFilterToggle();
        revalidate();
        repaint();
    }

    private Invoice getSelectedInvoice() {
        int row = table.getSelectedRow();
        return row < 0 || row >= table.getRowCount() || table.convertRowIndexToModel(row) >= filteredInvoices.size()
                ? null : filteredInvoices.get(table.convertRowIndexToModel(row));
    }

    private void openInvoiceDetails() {
        Invoice selected = getSelectedInvoice();
        if (selected != null) openInvoiceDetails(selected);
    }

    private void openInvoiceDetails(Invoice selected) {
        var i18n = ctx.getI18n();
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                i18n.t("invoice.details") + " — " + selected.invoiceNumber(), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        var body = new JPanel(new BorderLayout(0, 12));
        body.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        var overview = new JPanel(new GridLayout(0, 1, 0, 4));
        var partner = new JLabel(selected.partnerName());
        partner.setFont(ThemeManager.monoFont(Font.BOLD, 15));
        overview.add(partner);
        overview.add(new JLabel(i18n.t("invoice.direction." + selected.direction().name()) + "  |  "
                + i18n.t("invoice.category." + selected.category().name())));
        overview.add(new JLabel(i18n.t("invoice.issue_date") + "  " + selected.issueDate() + "  |  "
                + i18n.t("invoice.gross_amount") + "  " + selected.grossAmount() + " " + selected.effectiveCurrencyCode()));
        overview.add(new JLabel(i18n.t("invoice.due_date") + "  "
                + (selected.dueDate() == null ? "—" : selected.dueDate())));
        overview.add(new JLabel(i18n.t("invoice.net_amount") + "  " + selected.netAmount() + " "
                + selected.effectiveCurrencyCode() + "  |  " + i18n.t("invoice.vat") + "  "
                + selected.effectiveTaxTypeCode() + " (" + selected.vatRate().stripTrailingZeros().toPlainString() + "%)"));
        if (selected.orderNumber() != null) {
            overview.add(new JLabel(i18n.t("orders.order_number") + "  " + selected.orderNumber()));
        }
        body.add(overview, BorderLayout.NORTH);
        var items = BrutalistStyle.nonEditableTableModel();
        items.setColumnIdentifiers(new Object[]{i18n.t("invoice.item.description"), i18n.t("invoice.item.qty"),
                i18n.t("invoice.item.unit_price"), i18n.t("invoice.item.line_net")});
        String currency = selected.effectiveCurrencyCode();
        int decimals = ctx.getFinanceConfigDao().decimalPlacesFor(currency);
        for (InvoiceItem item : ctx.getInvoiceService().findItems(selected.id())) {
            items.addRow(new Object[]{item.description(), item.quantity() + " " + item.unit(),
                    CurrencyDef.formatAmount(item.unitPrice(), currency, decimals),
                    CurrencyDef.formatAmount(item.lineNet(), currency, decimals)});
        }
        JTable itemsTable = new JTable(items);
        BrutalistStyle.styleTable(itemsTable);
        itemsTable.setPreferredScrollableViewportSize(new Dimension(550, 145));
        itemsTable.getColumnModel().getColumn(0).setPreferredWidth(220);
        body.add(new JScrollPane(itemsTable), BorderLayout.CENTER);

        var status = new JComboBox<StatusChoice>();
        for (InvoiceStatus value : InvoiceStatus.values()) {
            status.addItem(new StatusChoice(value, i18n.t("status.invoice." + value.name())));
        }
        var apply = new JButton(i18n.t("invoice.status_update"));
        var preview = new JButton(i18n.t("invoice.preview_btn"));
        var reminder = new JButton(i18n.t("invoice.reminder.btn"));
        var receipt = new JButton();
        var credit = new JButton(i18n.t("invoice.credit_note.btn"));
        var close = new JButton(i18n.t("common.close"));
        var actionPanel = new JPanel(new GridBagLayout());
        GridBagConstraints pos = new GridBagConstraints();
        pos.gridx = 0;
        pos.gridy = 0;
        pos.weightx = 1;
        pos.fill = GridBagConstraints.HORIZONTAL;
        pos.insets = new Insets(3, 0, 3, 6);
        actionPanel.add(status, pos);
        pos.gridx = 1;
        actionPanel.add(apply, pos);
        pos.gridx = 0; pos.gridy++; actionPanel.add(preview, pos);
        pos.gridx = 1; actionPanel.add(receipt, pos);
        pos.gridx = 0; pos.gridy++; actionPanel.add(reminder, pos);
        pos.gridx = 1; actionPanel.add(credit, pos);
        var actions = new JPanel(new BorderLayout(0, 8));
        actions.add(actionPanel, BorderLayout.CENTER);
        var dialogFooter = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        dialogFooter.add(close);
        actions.add(dialogFooter, BorderLayout.SOUTH);
        body.add(actions, BorderLayout.SOUTH);

        java.util.function.Supplier<Invoice> find = () -> ctx.getInvoiceDao().findById(selected.id()).orElse(selected);
        Runnable refreshActions = () -> {
            Invoice invoice = find.get();
            status.setSelectedIndex(invoice.status().ordinal());
            reminder.setEnabled(invoice.direction() == InvoiceDirection.OUTGOING
                    && (invoice.status() == InvoiceStatus.ISSUED || invoice.status() == InvoiceStatus.OVERDUE));
            credit.setEnabled(invoice.category() != InvoiceCategory.CREDIT_NOTE
                    && invoice.status() != InvoiceStatus.CANCELLED);
            receipt.setText(i18n.t(invoice.hasAttachment() ? "invoice.receipt.view" : "invoice.receipt.attach"));
        };
        apply.addActionListener(e -> {
            if (status.getSelectedItem() instanceof StatusChoice choice) {
                handleStatusUpdate(dialog, find.get(), choice.status());
                refreshActions.run();
            }
        });
        preview.addActionListener(e -> openInvoicePreview(dialog, find.get()));
        reminder.addActionListener(e -> handleSendPaymentReminder(dialog, find.get()));
        receipt.addActionListener(e -> {
            handleReceiptVault(dialog, find.get());
            refreshActions.run();
        });
        credit.addActionListener(e -> {
            handleIssueCreditNote(dialog, find.get());
            refreshActions.run();
        });
        close.addActionListener(e -> dialog.dispose());
        BrutalistStyle.stylePrimaryButton(preview);
        BrutalistStyle.styleSecondaryButtons(apply, reminder, receipt, credit, close);
        refreshActions.run();
        dialog.setContentPane(body);
        BrutalistStyle.installEscapeToClose(dialog);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void handleSendPaymentReminder(Window owner, Invoice inv) {
        I18nManager i18n = ctx.getI18n();
        if (inv.partnerEmail() == null || inv.partnerEmail().isBlank()) {
            JOptionPane.showMessageDialog(owner, i18n.t("invoice.reminder.no_email"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            var company = ctx.getSettingsDao().getCompanyProfile();
            String mailto = ctx.getInvoiceService().buildPaymentReminderMailtoUri(inv, company, i18n);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MAIL)) {
                Desktop.getDesktop().mail(URI.create(mailto));
                JOptionPane.showMessageDialog(owner, i18n.t("invoice.reminder.opened"),
                        i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(owner, mailto,
                        i18n.t("invoice.reminder.btn"), JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, ex.getMessage(),
                    i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleReceiptVault(Window owner, Invoice inv) {
        I18nManager i18n = ctx.getI18n();
        var existingPath = ctx.getReceiptVaultService().resolveAttachment(inv);
        if (existingPath.isPresent()) {
            String[] choices = {
                    i18n.t("invoice.receipt.open"),
                    i18n.t("invoice.receipt.replace"),
                    i18n.t("invoice.receipt.remove"),
                    i18n.t("common.cancel")
            };
            int picked = JOptionPane.showOptionDialog(owner,
                    inv.invoiceNumber() + " — " + existingPath.get().getFileName(),
                    i18n.t("invoice.receipt.view"),
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]);
            try {
                if (picked == 0 && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(existingPath.get().toFile());
                } else if (picked == 1) {
                    chooseAndAttachReceipt(owner, inv);
                } else if (picked == 2) {
                    ctx.getReceiptVaultService().removeAttachment(inv.id());
                    refreshDataSelecting(inv.id());
                    JOptionPane.showMessageDialog(owner, i18n.t("invoice.receipt.removed"),
                            i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(owner, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        } else {
            chooseAndAttachReceipt(owner, inv);
        }
    }

    private void chooseAndAttachReceipt(Window owner, Invoice inv) {
        I18nManager i18n = ctx.getI18n();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n.t("invoice.receipt.attach"));
        chooser.setFileFilter(new FileNameExtensionFilter("Receipt (PDF, PNG, JPG)", "pdf", "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Invoice updated = ctx.getReceiptVaultService().attachReceipt(inv.id(), chooser.getSelectedFile().toPath());
            refreshDataSelecting(updated.id());
            JOptionPane.showMessageDialog(owner, i18n.t("invoice.receipt.attached", updated.attachmentPath()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleIssueCreditNote(Window owner, Invoice inv) {
        I18nManager i18n = ctx.getI18n();
        if (JOptionPane.showConfirmDialog(owner, i18n.t("invoice.credit_note.confirm", inv.invoiceNumber()),
                i18n.t("common.confirm"), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            long userId = ctx.getSession().requireCurrentUser().id();
            Invoice cn = ctx.getInvoiceService().issueCreditNote(inv.id(), userId);
            refreshDataSelecting(cn.id());
            JOptionPane.showMessageDialog(owner,
                    i18n.t("invoice.credit_note.created", cn.invoiceNumber(), inv.invoiceNumber()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, ex.getMessage(),
                    i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handlePreviewPnlReport() {
        I18nManager i18n = ctx.getI18n();
        FinancialSummary summary = currencySummary();
        var valuation = ctx.getPackageDao().sumInStockValuation();
        Map<String, Integer> decByCur = ctx.getFinanceConfigDao().decimalPlacesMap();
        var currencies = currencyFilter.selected();
        String scope = !currencies.isEmpty()
                ? String.join(", ", currencies)
                : i18n.t("common.all").toUpperCase(Locale.ROOT);
        DocumentPageSource report = ctx.getFinancialReportPdfExporter().createPageSource(
                scope, summary, valuation, ledgerInvoices, decByCur);
        Window owner = SwingUtilities.getWindowAncestor(this);
        DocumentPreviewDialog.showDocuments(owner, ctx, List.of(report),
                ctx.getSettingsDao().getCompanyProfile().email(),
                i18n.t("invoice.report.pdf_title"));
    }

    private void handleStatusUpdate(Window owner, Invoice inv, InvoiceStatus status) {
        if (inv.status() == status) return;
        try {
            ctx.getInvoiceService().changeStatus(inv.id(), status);
            refreshDataSelecting(inv.id());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, ex.getMessage(),
                    ctx.getI18n().t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openInvoicePreview(Window owner, Invoice inv) {
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
            Path target = file.getName().toLowerCase(Locale.ROOT).endsWith(".csv")
                    ? file.toPath() : Path.of(file.getAbsolutePath() + ".csv");
            List<Invoice> snapshot = List.copyOf(filteredInvoices);
            AsyncUiRunner.run(this,
                    () -> {
                        ctx.getCsvExportService().exportInvoices(snapshot, target);
                        return target;
                    },
                    saved -> JOptionPane.showMessageDialog(this, i18n.t("print.exported", saved.toAbsolutePath()),
                            i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE),
                    ex -> JOptionPane.showMessageDialog(this, ex.getMessage(),
                            i18n.t("common.error"), JOptionPane.ERROR_MESSAGE));
        }
    }

    private void resetFilters() {
        updatingControls = true;
        try {
            searchField.setText("");
            currencyFilter.clear();
            partnerFilter.clear();
            partnerSearch.setText("");
            statusFilter.clear();
            directionFilter.clear();
            categoryFilter.clear();
        } finally {
            updatingControls = false;
        }
    }

    private void filtersChanged() {
        if (!updatingControls) applyFiltersAndRender();
    }

    private boolean hasFilters() {
        return !currentFilterRules().isEmpty();
    }

    private void styleFilterToggle() {
        var i18n = ctx.getI18n();
        WorkspaceFolderRail.styleIconButton(toggleFilters);
        if (filterCard.isVisible()) {
            BrutalistStyle.stylePrimaryButton(toggleFilters);
            toggleFilters.setBorder(BorderFactory.createLineBorder(ThemeManager.border()));
        }
        WorkspaceFolderRail.labelIconButton(toggleFilters, i18n.t(filterCard.isVisible() ? "invoice.filters.hide"
                : hasFilters() ? "invoice.filters.active" : "invoice.filters.show"));
        toggleFilters.repaint();
    }

    private void applyFiltersAndRender() {
        var i18n = ctx.getI18n();
        Invoice oldSelection = getSelectedInvoice();
        var terms = SmartFolder.parseSearchTerms(searchField.getText()).stream()
                .map(term -> term.toLowerCase(Locale.ROOT)).toList();
        var partners = InvoicePartnerOptions.ruleKeys(partnerOptions, partnerFilter.selected());
        var directions = directionFilter.selected();
        var statuses = statusFilter.selected();
        var categories = categoryFilter.selected();
        var currencies = currencyFilter.selected();
        SmartFolder smart = currentSmartFolder();
        ledgerInvoices = allInvoices.stream()
                .filter(inv -> currencies.isEmpty() || currencies.contains(inv.effectiveCurrencyCode())).toList();
        List<Invoice> result = new ArrayList<>();
        for (Invoice invoice : ledgerInvoices) {
            if (!directions.isEmpty() && !directions.contains(invoice.direction())) continue;
            if (!statuses.isEmpty() && !statuses.contains(invoice.status())) continue;
            if (!categories.isEmpty() && !categories.contains(invoice.category())) continue;
            if (!partners.isEmpty() && !partners.contains(SmartFolder.invoicePartnerKey(invoice))) continue;
            if (smart != null && !smart.matches(invoice)) continue;
            String text = SmartFolder.invoiceSearchText(invoice).toLowerCase(Locale.ROOT);
            if (!terms.stream().allMatch(text::contains)) continue;
            result.add(invoice);
        }
        filteredInvoices = List.copyOf(result);
        renderTable(i18n);
        if (oldSelection != null) selectInvoiceRow(oldSelection.id());
        saveFilterActions.setVisible(hasFilters());
        styleFilterToggle();
    }

    private FinancialSummary currencySummary() {
        FinancialSummary summary = ctx.getInvoiceService().computeSummary(ledgerInvoices);
        var currencies = currencyFilter.selected();
        if (currencies.isEmpty()) return summary;
        var totals = new LinkedHashMap<String, FinancialSummary.CurrencyTotals>();
        for (String code : currencies) {
            totals.put(code, summary.forCurrency(code, CurrencyDef.defaultDecimalsFor(code)));
        }
        return new FinancialSummary(totals);
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
    }

    private void refreshDataSelecting(long invoiceId) {
        allInvoices = ctx.getInvoiceDao().findAll();
        applyFiltersAndRender();
        selectInvoiceRow(invoiceId);
    }

    private void selectInvoiceRow(long invoiceId) {
        for (int i = 0; i < filteredInvoices.size(); i++) {
            if (filteredInvoices.get(i).id() == invoiceId) {
                int row = table.convertRowIndexToView(i);
                if (row >= 0) table.setRowSelectionInterval(row, row);
                break;
            }
        }
    }

    @Override
    public JComponent getHeaderActions() {
        return headerActions;
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
        allInvoices = ctx.getInvoiceDao().findAll();
        loadFolders();
        rebuildCurrencyOptions();
        rebuildPartnerOptions();
        refreshFolderRail();
        applyFiltersAndRender();
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        WorkspaceFolderRail.labelIconButton(smartFoldersButton, i18n.t("smart.manage"));
        searchLabel.setText(i18n.t("common.search"));
        partnerLabel.setText(i18n.t("invoice.filters.partners"));
        partnerSearch.putClientProperty("JTextField.placeholderText", i18n.t("invoice.entry.search_partner"));
        partnerSearch.getAccessibleContext().setAccessibleName(i18n.t("invoice.entry.search_partner"));
        saveFilterFolder.setText(i18n.t("invoice.filters.save_folder"));
        sizeFilterCard();
        currencyLabel.setText(i18n.t("currency.label"));
        statusLabel.setText(i18n.t("common.status"));
        categoryLabel.setText(i18n.t("invoice.category"));
        directionLabel.setText(i18n.t("invoice.filter.direction"));
        clearFilters.setText(i18n.t("common.clear"));
        WorkspaceFolderRail.labelIconButton(moreActions, i18n.t("invoice.export_menu"));
        pnlReportItem.setText(i18n.t("invoice.report.btn"));
        exportCsvItem.setText(i18n.t("common.export.csv"));

        statusFilter.rebuild(List.of(InvoiceStatus.values()), status -> i18n.t("status.invoice." + status.name()));
        directionFilter.rebuild(List.of(InvoiceDirection.values()), direction -> i18n.t("invoice.direction." + direction.name()));
        categoryFilter.rebuild(List.of(InvoiceCategory.values()), category -> i18n.t("invoice.category." + category.name()));

        tableModel.setColumnIdentifiers(new Object[]{
                i18n.t("invoice.number").toUpperCase(Locale.ROOT),
                i18n.t("invoice.filter.direction").toUpperCase(Locale.ROOT),
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
        visibleColumns = List.of();
        configureInvoiceColumns(listPanel.getWidth());

        refreshData();
    }

    @Override
    public void applyTheme() {
        folders.render();
        setBackground(ThemeManager.bg());

        styleFilterToggle();

        BrutalistStyle.applyBoxCard(filterCard, 10, 10);
        BrutalistStyle.styleSecondaryButtons(clearFilters, saveFilterFolder);
        sizeFilterCard();
        filterActionDivider.setBorder(BrutalistStyle.hairlineTopRule(4, 0));
        WorkspaceFolderRail.styleIconButton(moreActions);
        exportMenu.setBorder(BrutalistStyle.boxBorder(4, 4));
        exportMenu.setBackground(ThemeManager.bg());
        for (var item : List.of(pnlReportItem, exportCsvItem)) {
            item.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 16));
        }
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
