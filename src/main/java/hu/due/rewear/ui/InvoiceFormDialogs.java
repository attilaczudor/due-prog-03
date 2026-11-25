package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.CountryDirectory;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.TaxType;
import hu.due.rewear.service.InvoiceService;
import hu.due.rewear.util.FormatUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JFileChooser;
import javax.swing.JToggleButton;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.BoxLayout;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.nio.file.Path;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Factory for the Outgoing Invoice (revenue) and Incoming Expense creation modals used by {@link InvoicesView}.
 * Supports order auto-fill (with country-based VAT lookup), saved provider auto-fill,
 * and multi-line manual item entry with live Net / VAT / Gross total calculation.
 */
final class InvoiceFormDialogs {

    private record TaxChoice(TaxType tax, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record CategoryChoice(InvoiceCategory category, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record StatusChoice(InvoiceStatus status, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final class FormBody extends JPanel implements Scrollable {
        FormBody() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(665, 515); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(18, visible.height - 18);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Encapsulates the interactive multi-line items editor table + live totals footer. */
    private static final class LineItemsEditor {
        final JPanel panel = new JPanel(new BorderLayout(0, 6));
        final DefaultTableModel model;
        final JTable table;
        final JButton addLineBtn;
        final JButton removeLineBtn;
        private boolean locked = false;

        LineItemsEditor(I18nManager i18n, String defaultUnit, Runnable onChange) {
            this.model = new DefaultTableModel(new Object[]{
                    i18n.t("invoice.item.description") + " *",
                    i18n.t("invoice.item.qty") + " *",
                    i18n.t("invoice.item.unit"),
                    i18n.t("invoice.item.unit_price") + " *"
            }, 0) {
                @Override
                public boolean isCellEditable(int row, int col) {
                    return !locked;
                }
            };
            this.table = new JTable(model);
            BrutalistStyle.styleTable(table);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            table.getColumnModel().getColumn(0).setPreferredWidth(230);
            table.getColumnModel().getColumn(1).setPreferredWidth(65);
            table.getColumnModel().getColumn(2).setPreferredWidth(55);
            table.getColumnModel().getColumn(3).setPreferredWidth(95);

            addLineBtn = new JButton(i18n.t("invoice.lines.add").toUpperCase(Locale.ROOT));
            removeLineBtn = new JButton(i18n.t("invoice.lines.remove").toUpperCase(Locale.ROOT));
            BrutalistStyle.styleSecondaryButtons(addLineBtn, removeLineBtn);

            addLineBtn.addActionListener(e -> {
                stopEditing();
                model.addRow(new Object[]{"", "1", defaultUnit, "0"});
                int last = model.getRowCount() - 1;
                TableSorting.selectModelRow(table, last);
                onChange.run();
            });
            removeLineBtn.addActionListener(e -> {
                stopEditing();
                int r = TableSorting.selectedModelRow(table);
                if (r >= 0) {
                    model.removeRow(r);
                    onChange.run();
                }
            });
            model.addTableModelListener(e -> onChange.run());

            // Seed one starter editable row
            model.addRow(new Object[]{"", "1", defaultUnit, "0"});

            JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            topBar.setOpaque(false);
            topBar.add(addLineBtn);
            topBar.add(removeLineBtn);
            panel.add(topBar, BorderLayout.NORTH);

            JScrollPane sp = new JScrollPane(table);
            sp.setPreferredSize(new Dimension(520, 128));
            panel.add(sp, BorderLayout.CENTER);


        }

        void setLocked(boolean lock) {
            stopEditing();
            this.locked = lock;
            addLineBtn.setEnabled(!lock);
            removeLineBtn.setEnabled(!lock);
            addLineBtn.setVisible(!lock);
            removeLineBtn.setVisible(!lock);
        }

        void stopEditing() {
            if (table.isEditing() && table.getCellEditor() != null) {
                table.getCellEditor().stopCellEditing();
            }
        }

        BigDecimal previewNet(int decimals) {
            BigDecimal net = BigDecimal.ZERO;
            for (int i = 0; i < model.getRowCount(); i++) {
                try {
                    BigDecimal qty = CurrencyCode.parseAmount(strAt(i, 1));
                    BigDecimal price = CurrencyDef.roundForScale(CurrencyCode.parseAmount(strAt(i, 3)), decimals);
                    if (qty.signum() > 0 && price.signum() >= 0) {
                        net = net.add(CurrencyDef.roundForScale(qty.multiply(price), decimals));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Incomplete cell values do not contribute to the preview.
                }
            }
            return net;
        }

        List<InvoiceService.InvoiceLineInput> parseLines(I18nManager i18n) {
            stopEditing();
            List<InvoiceService.InvoiceLineInput> result = new ArrayList<>();
            for (int i = 0; i < model.getRowCount(); i++) {
                String desc = strAt(i, 0).trim();
                String qtyStr = strAt(i, 1).trim();
                String unit = strAt(i, 2).trim();
                String priceStr = strAt(i, 3).trim();
                if (desc.isEmpty() && (priceStr.isEmpty() || "0".equals(priceStr))) {
                    continue;
                }
                if (desc.isEmpty()) throw new IllegalArgumentException(i18n.t("invoice.entry.line_description"));
                BigDecimal qty = CurrencyCode.parseAmount(qtyStr);
                BigDecimal price = CurrencyCode.parseAmount(priceStr);
                if (price.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException(i18n.t("validation.prices_non_negative"));
                }
                result.add(new InvoiceService.InvoiceLineInput(desc, qty, unit.isEmpty() ? "db" : unit, price));
            }
            return result;
        }

        private String strAt(int row, int col) {
            Object v = model.getValueAt(row, col);
            return v == null ? "" : v.toString();
        }
    }

    private InvoiceFormDialogs() {
    }

    /** Opens the modal for creating a new Outgoing (revenue) invoice from an order or manually (multi-line). */
    static Optional<Invoice> showNewOutgoingDialog(Component parent, AppContext ctx, String defaultCurrency) {
        return new EntryForm(parent, ctx, defaultCurrency, true).show();
    }

    static Optional<Invoice> showNewIncomingDialog(Component parent, AppContext ctx, String defaultCurrency) {
        return new EntryForm(parent, ctx, defaultCurrency, false).show();
    }

    private static final class EntryForm {
        private final Component owner;
        private final AppContext ctx;
        private final I18nManager i18n;
        private final boolean outgoing;
        private final List<Order> orders;
        private final InvoiceLookupField<Order> orderLookup;
        private final InvoiceLookupField<Customer> partnerLookup;
        private final JComboBox<String> currencies;
        private final JComboBox<TaxChoice> taxes;
        private final JComboBox<CategoryChoice> categories = new JComboBox<>();
        private final JComboBox<StatusChoice> statuses = new JComboBox<>();
        private final JComboBox<String> countries = new JComboBox<>();
        private final JTextField invoiceNumber = new JTextField(24);
        private final JTextField partnerName = new JTextField(24);
        private final JTextField taxNumber = new JTextField(24);
        private final JTextField address = new JTextField(24);
        private final JTextField email = new JTextField(24);
        private final JTextField totalNet = new JTextField(16);
        private final JLabel totals = new JLabel(" ");
        private final JLabel orderHint = new JLabel(" ");
        private final JLabel receiptLabel = new JLabel("—");
        private final JButton removeReceipt = new JButton();
        private final JPanel receiptRow = new JPanel(new BorderLayout(6, 0));
        private final JPanel form = new JPanel(new GridBagLayout());
        private final java.util.Map<JComponent, JLabel> labels = new java.util.LinkedHashMap<>();
        private final JPanel amountMode = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        private final JPanel amountBody = new JPanel(new BorderLayout());
        private final JPanel netRow = new JPanel(new BorderLayout(8, 0));
        private final JPanel root = new JPanel(new BorderLayout(0, 10));
        private final LineItemsEditor manualLines;
        private final LineItemsEditor orderLines;
        private final java.util.Map<String, Integer> decimals;
        private final JToggleButton fromOrder = new JToggleButton();
        private final JToggleButton manual = new JToggleButton();
        private final JToggleButton itemized = new JToggleButton();
        private final JToggleButton totalOnly = new JToggleButton();

        private InvoiceService.ReviewedOrder reviewed;
        private Invoice existing;
        private Path receipt;
        private boolean updating;
        private int nextRow;

        EntryForm(Component owner, AppContext ctx, String defaultCurrency, boolean outgoing) {
            this.owner = owner;
            this.ctx = ctx;
            this.i18n = ctx.getI18n();
            this.outgoing = outgoing;
            decimals = ctx.getFinanceConfigDao().decimalPlacesMap();
            orders = outgoing ? ctx.getOrderDao().findAll().stream()
                    .filter(order -> order.status() == OrderStatus.SHIPPED).toList() : List.of();
            currencies = buildCurrencyCombo(ctx, defaultCurrency != null ? defaultCurrency : "HUF");
            taxes = buildTaxTypeCombo(ctx, i18n.getActiveLanguage());
            partnerLookup = new InvoiceLookupField<>(i18n.t("invoice.entry.search_partner"), i18n.t("invoice.entry.clear_selection"),
                    Customer::name, partner -> partner.name() + " " + FormatUtil.safe(partner.taxNumber())
                            + " " + FormatUtil.safe(partner.email()), this::partnerChanged);
            partnerLookup.setChoices(ctx.getCustomerDao().findAll(true));
            orderLookup = new InvoiceLookupField<>(i18n.t("invoice.entry.search_order"), i18n.t("invoice.entry.clear_selection"),
                    order -> order.orderNumber() + " — " + order.customerName(),
                    order -> order.orderNumber() + " " + order.customerName(), this::orderChanged);
            orderLookup.setChoices(orders);
            manualLines = new LineItemsEditor(i18n, outgoing ? "kg" : "db", this::recalculate);
            orderLines = new LineItemsEditor(i18n, "kg", this::recalculate);
            orderLines.setLocked(true);
            orderLines.model.setRowCount(0);
            build();
        }

        private void build() {
            updating = true;
            if (outgoing) {
                var mode = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                setupToggles(mode, fromOrder, "invoice.entry.from_order", manual, "invoice.manual_entry");
                fromOrder.setSelected(true);
                fromOrder.addActionListener(e -> refreshMode());
                manual.addActionListener(e -> refreshMode());
                root.add(mode, BorderLayout.NORTH);
                field("invoice.entry.order", orderLookup);
            } else {
                for (InvoiceCategory category : InvoiceCategory.values()) {
                    if (category.defaultDirection() == InvoiceDirection.INCOMING && category != InvoiceCategory.CREDIT_NOTE) {
                        categories.addItem(new CategoryChoice(category, i18n.t("invoice.category." + category.name())));
                    }
                }
                for (InvoiceStatus status : List.of(InvoiceStatus.ISSUED, InvoiceStatus.PAID, InvoiceStatus.DRAFT)) {
                    statuses.addItem(new StatusChoice(status, i18n.t("status.invoice." + status.name())));
                }
                field("invoice.category", categories);
                field("invoice.number_optional", invoiceNumber);
                field("common.status", statuses);
            }
            field(outgoing ? "invoice.entry.customer" : "invoice.entry.supplier", partnerLookup);
            field("invoice.partner", partnerName);
            field("invoice.tax_number", taxNumber);
            field("invoice.address", address);
            field("invoice.email", email);
            CountryDirectory.COUNTRIES.forEach(country -> countries.addItem(country.code()));
            countries.setEditable(true);
            countries.setRenderer(new javax.swing.DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(javax.swing.JList<?> list, Object item,
                                                                         int index, boolean selected, boolean focus) {
                    String value = item instanceof String code
                            ? CountryDirectory.localizedCountryName(code, i18n.getActiveLanguage()) : "";
                    return super.getListCellRendererComponent(list, value, index, selected, focus);
                }
            });
            field("partners.country", countries);
            field("currency.label", currencies);
            field("invoice.tax_type", taxes);

            JButton attach = new JButton(i18n.t("invoice.entry.choose_file"));
            attach.addActionListener(e -> chooseReceipt());
            removeReceipt.setText(i18n.t("invoice.entry.remove_file"));
            removeReceipt.setEnabled(false);
            removeReceipt.addActionListener(e -> setReceipt(null));
            var receiptActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            receiptActions.add(attach);
            receiptActions.add(removeReceipt);
            receiptLabel.setPreferredSize(new Dimension(105, 24));
            receiptLabel.setMinimumSize(new Dimension(0, 24));
            receiptRow.add(receiptLabel, BorderLayout.CENTER);
            receiptRow.add(receiptActions, BorderLayout.EAST);
            field("invoice.entry.attachment", receiptRow);

            setupToggles(amountMode, itemized, "invoice.entry.itemized", totalOnly, "invoice.entry.net_only");
            (outgoing ? itemized : totalOnly).setSelected(true);
            itemized.addActionListener(e -> refreshAmounts());
            totalOnly.addActionListener(e -> refreshAmounts());
            var netLabel = new JLabel(i18n.t("invoice.net_amount") + " *");
            netLabel.setLabelFor(totalNet);
            netRow.add(netLabel, BorderLayout.WEST);
            netRow.add(totalNet, BorderLayout.CENTER);
            var body = new FormBody();
            for (JComponent component : List.of(form, orderHint, amountMode, amountBody, totals)) {
                component.setAlignmentX(Component.LEFT_ALIGNMENT);
                body.add(component);
            }
            totals.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
            totals.setBorder(BorderFactory.createEmptyBorder(8, 4, 2, 4));
            var scroll = new JScrollPane(body, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            root.add(scroll, BorderLayout.CENTER);

            countries.addActionListener(e -> { if (!updating && !orderMode()) suggestTax(); });
            currencies.addActionListener(e -> recalculate());
            taxes.addActionListener(e -> recalculate());
            totalNet.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { recalculate(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { recalculate(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { recalculate(); }
            });
            updating = false;
            suggestTax();
            refreshMode();
        }

        private void field(String key, JComponent component) {
            JLabel label = new JLabel(i18n.t(key));
            label.setLabelFor(component);
            GridBagConstraints cell = new GridBagConstraints();
            cell.gridy = nextRow++;
            cell.insets = new Insets(4, 4, 4, 6);
            cell.anchor = GridBagConstraints.LINE_START;
            cell.gridx = 0;
            form.add(label, cell);
            cell.gridx = 1;
            cell.weightx = 1;
            cell.fill = GridBagConstraints.HORIZONTAL;
            form.add(component, cell);
            labels.put(component, label);
        }

        private void setRowVisible(JComponent component, boolean visible) {
            component.setVisible(visible);
            JLabel label = labels.get(component);
            if (label != null) label.setVisible(visible);
        }

        private void setupToggles(JPanel host, JToggleButton first, String firstKey, JToggleButton second, String secondKey) {
            var group = new ButtonGroup();
            first.setText(i18n.t(firstKey));
            second.setText(i18n.t(secondKey));
            for (JToggleButton button : List.of(first, second)) {
                group.add(button);
                host.add(button);
                button.addItemListener(e -> BrutalistStyle.styleRailToggle(button, false));
                BrutalistStyle.styleRailToggle(button, false);
            }
        }

        private boolean orderMode() { return outgoing && fromOrder.isSelected(); }

        private void refreshMode() {
            boolean orderMode = orderMode();
            setRowVisible(orderLookup, orderMode);
            setRowVisible(receiptRow, !orderMode);
            setRowVisible(partnerName, !orderMode || reviewed != null);
            for (var field : List.of(taxNumber, address, email, countries)) setRowVisible(field, !orderMode || reviewed != null);
            if (!orderMode && reviewed != null) {
                orderLookup.select(null);
                reviewed = null;
                existing = null;
                populatePartner(partnerLookup.selected());
            }
            boolean locked = orderMode || partnerLookup.selected() != null;
            for (JTextField field : List.of(partnerName, taxNumber, address, email)) field.setEditable(!locked);
            countries.setEnabled(!locked);
            currencies.setEnabled(!orderMode);
            taxes.setEnabled(!orderMode || reviewed != null);
            orderHint.setVisible(orderMode);
            orderHint.setText(existing != null ? i18n.t("invoice.entry.existing", existing.invoiceNumber())
                    : i18n.t("invoice.entry.order_locked"));
            refreshAmounts();
            root.revalidate();
            root.repaint();
        }

        private void refreshAmounts() {
            amountMode.setVisible(!orderMode());
            amountBody.removeAll();
            amountBody.add(orderMode() ? orderLines.panel : totalOnly.isSelected() ? netRow : manualLines.panel);
            amountBody.revalidate();
            amountBody.repaint();
            recalculate();
        }

        private void partnerChanged(Customer selected) {
            filterOrders(selected);
            if (reviewed != null && (selected == null || selected.id() != reviewed.customer().id())) {
                orderLookup.select(null);
                reviewed = null;
                existing = null;
                orderLines.model.setRowCount(0);
            }
            populatePartner(selected);
            refreshMode();
        }

        private void filterOrders(Customer customer) {
            orderLookup.setChoices(customer == null ? orders
                    : orders.stream().filter(order -> order.customerId() == customer.id()).toList());
        }

        private void orderChanged(Order order) {
            try {
                loadOrder(order);
            } catch (RuntimeException ex) {
                orderLookup.select(null);
                reviewed = null;
                existing = null;
                orderLines.model.setRowCount(0);
                refreshMode();
                JOptionPane.showMessageDialog(owner, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        }

        private void loadOrder(Order order) {
            updating = true;
            try {
                reviewed = order == null ? null : ctx.getInvoiceService().reviewOrder(order.id());
                existing = order == null ? null : ctx.getInvoiceDao().findByOrderId(order.id())
                        .filter(invoice -> invoice.status() != InvoiceStatus.DRAFT).orElse(null);
                orderLines.model.setRowCount(0);
                if (reviewed != null) {
                    Customer customer = reviewed.customer();
                    partnerLookup.select(customer);
                    filterOrders(customer);
                    populatePartner(customer);
                    setCurrency(reviewed.order().effectiveCurrencyCode());
                    if (existing != null) {
                        ctx.getFinanceConfigDao().findTaxTypeByCode(existing.effectiveTaxTypeCode())
                                .ifPresent(tax -> selectTaxInCombo(taxes, tax));
                    }
                    for (var item : reviewed.items()) {
                        orderLines.model.addRow(new Object[]{item.packageQrCode() + " (" + item.localizedSummary(i18n.getActiveLanguage())
                                + ")", item.weightKg(), "kg", item.pricePerKg()});
                    }
                } else {
                    populatePartner(partnerLookup.selected());
                }
            } finally {
                updating = false;
            }
            refreshMode();
        }

        private void populatePartner(Customer partner) {
            partnerName.setText(partner == null ? "" : partner.name());
            taxNumber.setText(partner == null ? "" : FormatUtil.safe(partner.taxNumber()));
            address.setText(partner == null ? "" : FormatUtil.safe(partner.address()));
            email.setText(partner == null ? "" : FormatUtil.safe(partner.email()));
            countries.setSelectedItem(partner == null ? "HU" : partner.effectiveCountryCode());
            if (partner != null) setCurrency(partner.effectiveCurrencyCode());
            suggestTax();
        }

        private void setCurrency(String code) {
            boolean exists = false;
            for (int i = 0; i < currencies.getItemCount(); i++) if (code.equals(currencies.getItemAt(i))) exists = true;
            if (!exists) currencies.addItem(code);
            currencies.setSelectedItem(code);
        }

        private String country() {
            return CountryDirectory.normalizeCode(java.util.Objects.toString(countries.getEditor().getItem(), ""));
        }

        private void suggestTax() {
            TaxType suggested = ctx.getFinanceConfigDao().resolveDefaultTaxTypeForCountry(country());
            selectTaxInCombo(taxes, suggested);
        }

        private TaxType selectedTax() {
            return taxes.getSelectedItem() instanceof TaxChoice choice ? choice.tax() : null;
        }

        private void chooseReceipt() {
            var chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("PDF / PNG / JPEG", "pdf", "png", "jpeg", "jpg"));
            if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) setReceipt(chooser.getSelectedFile().toPath());
        }

        private void setReceipt(Path path) {
            receipt = path;
            receiptLabel.setText(path == null ? "—" : path.getFileName().toString());
            receiptLabel.setToolTipText(path == null ? null : path.toString());
            removeReceipt.setEnabled(path != null);
        }

        private void recalculate() {
            if (updating || manualLines == null || orderLines == null) return;
            String currency = java.util.Objects.toString(currencies.getSelectedItem(), "EUR");
            int places = decimals.getOrDefault(currency, CurrencyDef.defaultDecimalsFor(currency));
            BigDecimal net;
            if (orderMode()) {
                net = reviewed == null ? BigDecimal.ZERO : reviewed.items().stream()
                        .map(hu.due.rewear.model.OrderItem::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            } else if (totalOnly.isSelected()) {
                try { net = CurrencyCode.parseAmount(totalNet.getText()); }
                catch (IllegalArgumentException ex) { net = BigDecimal.ZERO; }
            } else {
                net = manualLines.previewNet(places);
            }
            net = CurrencyDef.roundForScale(net, places);
            TaxType tax = selectedTax();
            BigDecimal rate = tax == null ? BigDecimal.ZERO : tax.ratePercent();
            BigDecimal vat = CurrencyDef.roundForScale(net.multiply(rate)
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP), places);
            totals.setText(i18n.t("invoice.net_amount") + "  " + CurrencyDef.formatAmount(net, currency, places)
                    + "  |  " + i18n.t("invoice.vat") + " (" + FormatUtil.stripZeros(rate) + "%)  "
                    + CurrencyDef.formatAmount(vat, currency, places) + "  |  "
                    + i18n.t("invoice.gross_amount") + "  " + CurrencyDef.formatAmount(net.add(vat), currency, places));
        }

        Optional<Invoice> show() {
            String title = i18n.t(outgoing ? "invoice.new_outgoing" : "invoice.new_incoming");
            String[] actions = {i18n.t("common.save"), i18n.t("common.cancel")};
            while (JOptionPane.showOptionDialog(owner, root, title, JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE, null, actions, actions[0]) == 0) {
                try {
                    if (orderMode() && existing != null) return Optional.of(existing);
                    long user = ctx.getSession().requireCurrentUser().id();
                    if (orderMode() && (reviewed == null || reviewed.items().isEmpty())) {
                        throw new IllegalArgumentException(i18n.t("invoice.entry.order_required"));
                    }
                    TaxType tax = selectedTax();
                    if (tax == null) throw new IllegalArgumentException(i18n.t("invoice.entry.tax_required"));
                    if (orderMode()) return Optional.of(ctx.getInvoiceService().saveReviewedOrder(reviewed, tax, user));
                    if (partnerName.getText().isBlank()) throw new IllegalArgumentException(i18n.t("invoice.entry.partner_required"));
                    if (!country().matches("[A-Z]{2}")) throw new IllegalArgumentException(i18n.t("invoice.entry.country_invalid"));
                    List<InvoiceService.InvoiceLineInput> lines = totalOnly.isSelected() ? List.of() : manualLines.parseLines(i18n);
                    BigDecimal net = totalOnly.isSelected() && !totalNet.getText().isBlank()
                            ? CurrencyCode.parseAmount(totalNet.getText()) : null;
                    if (lines.isEmpty() && (net == null || net.signum() < 0)) {
                        throw new IllegalArgumentException(i18n.t("invoice.entry.total_required"));
                    }
                    var partner = confirmPartner();
                    if (partner == null) continue;
                    InvoiceCategory category = outgoing ? InvoiceCategory.WHOLESALE_SALES
                            : ((CategoryChoice) categories.getSelectedItem()).category();
                    InvoiceStatus status = outgoing ? InvoiceStatus.ISSUED : ((StatusChoice) statuses.getSelectedItem()).status();
                    var entry = new InvoiceService.ManualEntry(outgoing ? InvoiceDirection.OUTGOING : InvoiceDirection.INCOMING,
                            category, invoiceNumber.getText(), partner, lines, net, tax,
                            java.util.Objects.toString(currencies.getSelectedItem(), "EUR"),
                            LocalDate.now(), LocalDate.now().plusDays(14), status, user, receipt);
                    return Optional.of(ctx.getInvoiceService().saveManualEntry(entry, ctx.getReceiptVaultService()));
                } catch (InvoiceService.OrderChangedException ex) {
                    JOptionPane.showMessageDialog(owner, i18n.t("invoice.entry.order_changed"), title, JOptionPane.WARNING_MESSAGE);
                    orderChanged(orderLookup.selected());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(owner, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
                }
            }
            return Optional.empty();
        }

        private InvoiceService.ManualPartner confirmPartner() {
            Customer partner = partnerLookup.selected();
            if (partner == null) {
                List<Customer> matches = ctx.getCustomerDao().findByTaxNumber(taxNumber.getText());
                if (matches.size() > 1 || matches.stream().anyMatch(customer -> !customer.active())) {
                    throw new IllegalArgumentException(i18n.t("invoice.entry.tax_match_multiple"));
                }
                if (matches.size() == 1) {
                    Customer matched = matches.get(0);
                    if (JOptionPane.showConfirmDialog(owner, i18n.t("invoice.entry.tax_match", matched.name()),
                            i18n.t("invoice.partner"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        partnerLookup.select(matched);
                        partnerChanged(matched);
                    }
                    return null;
                }
            }
            boolean requiresBoth = partner != null && (outgoing ? !partner.partnerType().isBuyer() : !partner.partnerType().isSupplier());
            if (requiresBoth && JOptionPane.showConfirmDialog(owner, i18n.t("invoice.entry.partner_both", partner.name()),
                    i18n.t("invoice.partner"), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null;
            return new InvoiceService.ManualPartner(partner == null ? null : partner.id(), partnerName.getText(), taxNumber.getText(),
                    address.getText(), email.getText(), country(), requiresBoth);
        }
    }

    private static JComboBox<TaxChoice> buildTaxTypeCombo(AppContext ctx, String lang) {
        JComboBox<TaxChoice> combo = new JComboBox<>();
        for (TaxType t : ctx.getFinanceConfigDao().findTaxTypes(true)) {
            combo.addItem(new TaxChoice(t, t.displayLabel(lang)));
        }
        return combo;
    }

    private static void selectTaxInCombo(JComboBox<TaxChoice> combo, TaxType target) {
        if (target == null) {
            return;
        }
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).tax().code().equalsIgnoreCase(target.code())) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    private static JComboBox<String> buildCurrencyCombo(AppContext ctx, String preferred) {
        JComboBox<String> combo = new JComboBox<>();
        for (CurrencyDef c : ctx.getFinanceConfigDao().findCurrencies(true)) {
            combo.addItem(c.code());
        }
        if (preferred != null) {
            combo.setSelectedItem(preferred);
        }
        return combo;
    }
}
