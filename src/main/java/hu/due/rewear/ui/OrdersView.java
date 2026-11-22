package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceItem;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.model.PackageFilter;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.service.DocumentPageSource;
import hu.due.rewear.util.FormatUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
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
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * B2B Orders & Dispatch workspace with live QR scan-to-pack, available stock picker,
 * atomic order status lifecycle (DRAFT -> PACKING -> SHIPPED / CANCELLED),
 * and OpenPDF Delivery Note / Packing List export.
 */
public final class OrdersView extends JPanel implements NavigableView {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;

    // Top bar
    private final JButton newOrderBtn = new JButton();
    private final JButton refreshBtn = new JButton();

    // Left: Orders list
    private final DefaultTableModel ordersModel = BrutalistStyle.nonEditableTableModel();
    private final JTable ordersTable = new JTable(ordersModel);

    // Right: Selected order detail + live QR scan + items + lifecycle
    private final JPanel detailHeaderPanel = new JPanel(new BorderLayout(8, 6));
    private final JLabel orderHeaderTitle = new JLabel("—");
    private final JLabel orderStatusTag = new JLabel(" ");
    private final JLabel orderCurrencyTag = new JLabel(" ");
    private final JComboBox<String> orderCurrencyCombo = new JComboBox<>();
    private final JLabel orderMetaLabel = new JLabel(" ");
    private boolean syncingCurrencyCombo = false;

    private final JPanel scanBarPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 6));
    private final JLabel scanLabel = new JLabel();
    private final JTextField scanQrField = new JTextField(26);
    private final JButton scanAddBtn = new JButton();
    private final JButton pickStockBtn = new JButton();
    private final JButton removeItemBtn = new JButton();

    private final JLabel itemsTitleLabel = new JLabel();
    private final DefaultTableModel itemsModel = BrutalistStyle.nonEditableTableModel();
    private final JTable itemsTable = new JTable(itemsModel);

    private final JLabel statusSelectLabel = new JLabel();
    private final JComboBox<StatusStepOption> statusDropdown = new JComboBox<>();
    private final JButton applyStatusBtn = new JButton();
    private final JButton previewDocsBtn = new JButton();

    private List<Order> orders = List.of();
    private List<OrderItem> currentItems = List.of();

    private record StatusStepOption(OrderStatus status, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public OrdersView(AppContext ctx) {
        this.ctx = ctx;

        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        buildUi();
        wireEvents();
        applyTranslations();
        applyBrutalistTheme();
        refreshData();
    }

    private void buildUi() {
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        topBar.add(newOrderBtn);
        topBar.add(refreshBtn);
        add(topBar, BorderLayout.NORTH);

        // Left Orders Table
        BrutalistStyle.styleTable(ordersTable);
        ordersTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Right Order Detail Workspace
        JPanel rightPanel = new JPanel(new BorderLayout(0, 10));

        JPanel topMetaRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 0));
        orderHeaderTitle.setFont(ThemeManager.monoFont(Font.BOLD, 16f));
        topMetaRow.add(orderHeaderTitle);
        topMetaRow.add(orderStatusTag);
        topMetaRow.add(orderCurrencyTag);
        topMetaRow.add(orderCurrencyCombo);
        detailHeaderPanel.add(topMetaRow, BorderLayout.NORTH);
        orderMetaLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
        detailHeaderPanel.add(orderMetaLabel, BorderLayout.CENTER);

        // Scan-to-pack bar
        scanQrField.setFont(ThemeManager.monoFont(Font.BOLD, 12.5f));
        scanQrField.setPreferredSize(new Dimension(270, 30));
        scanBarPanel.add(scanLabel);
        scanBarPanel.add(scanQrField);
        scanBarPanel.add(scanAddBtn);
        scanBarPanel.add(pickStockBtn);
        scanBarPanel.add(removeItemBtn);

        JPanel rightNorth = new JPanel(new BorderLayout(0, 8));
        rightNorth.add(detailHeaderPanel, BorderLayout.NORTH);
        rightNorth.add(scanBarPanel, BorderLayout.SOUTH);
        rightPanel.add(rightNorth, BorderLayout.NORTH);

        JPanel itemsContainer = new JPanel(new BorderLayout(0, 6));
        itemsTitleLabel.setFont(ThemeManager.monoFont(Font.BOLD, 12.5f));
        itemsContainer.add(itemsTitleLabel, BorderLayout.NORTH);
        BrutalistStyle.styleTable(itemsTable);
        itemsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        itemsContainer.add(new JScrollPane(itemsTable), BorderLayout.CENTER);
        rightPanel.add(itemsContainer, BorderLayout.CENTER);

        JPanel lifecycleBar = new JPanel(new WrapLayout(FlowLayout.RIGHT, 8, 4));
        lifecycleBar.add(statusSelectLabel);
        lifecycleBar.add(statusDropdown);
        lifecycleBar.add(applyStatusBtn);
        lifecycleBar.add(previewDocsBtn);
        rightPanel.add(lifecycleBar, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(ordersTable), rightPanel);
        split.setResizeWeight(0.38);
        split.setDividerLocation(420);
        add(split, BorderLayout.CENTER);
    }

    private void wireEvents() {
        newOrderBtn.addActionListener(e -> handleNewOrder());
        refreshBtn.addActionListener(e -> refreshData());

        ordersTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                renderSelectedOrder();
            }
        });

        scanQrField.addActionListener(e -> handleScanAdd());
        scanAddBtn.addActionListener(e -> handleScanAdd());
        pickStockBtn.addActionListener(e -> handlePickFromStock());
        removeItemBtn.addActionListener(e -> handleRemoveItem());

        applyStatusBtn.addActionListener(e -> handleApplyStatus());
        previewDocsBtn.addActionListener(e -> handlePreviewDocuments());
        orderCurrencyCombo.addActionListener(e -> {
            if (!syncingCurrencyCombo) {
                handleChangeOrderCurrency();
            }
        });
    }

    private Order getSelectedOrder() {
        int r = ordersTable.getSelectedRow();
        return (r >= 0 && r < orders.size()) ? orders.get(r) : null;
    }

    private void handleNewOrder() {
        I18nManager i18n = ctx.getI18n();
        List<Customer> activeCustomers = ctx.getCustomerDao().findBuyers(true);
        if (activeCustomers.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No active buyers available.",
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        JComboBox<Customer> customerCombo = new JComboBox<>(activeCustomers.toArray(new Customer[0]));
        JComboBox<String> currencyCombo = new JComboBox<>();
        for (CurrencyDef c : ctx.getFinanceConfigDao().findCurrencies(true)) {
            currencyCombo.addItem(c.code());
        }
        if (!activeCustomers.isEmpty()) {
            currencyCombo.setSelectedItem(activeCustomers.get(0).effectiveCurrencyCode());
        }
        customerCombo.addActionListener(e -> {
            if (customerCombo.getSelectedItem() instanceof Customer c) {
                currencyCombo.setSelectedItem(c.effectiveCurrencyCode());
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 0; form.add(new JLabel(i18n.t("orders.customer") + ":"), gbc);
        gbc.gridx = 1; form.add(customerCombo, gbc);
        gbc.gridx = 0; gbc.gridy = 1; form.add(new JLabel(i18n.t("currency.label") + ":"), gbc);
        gbc.gridx = 1; form.add(currencyCombo, gbc);

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("orders.new_order"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        Customer chosen = (Customer) customerCombo.getSelectedItem();
        if (chosen == null) return;
        String chosenCur = currencyCombo.getSelectedItem() != null
                ? currencyCombo.getSelectedItem().toString() : chosen.effectiveCurrencyCode();

        try {
            Order created = ctx.getOrderService().createOrder(
                    chosen.id(), chosenCur, null, ctx.getSession().requireCurrentUser().id());
            refreshDataSelecting(created.id());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleChangeOrderCurrency() {
        Order order = getSelectedOrder();
        if (order == null || orderCurrencyCombo.getSelectedItem() == null) {
            return;
        }
        String target = orderCurrencyCombo.getSelectedItem().toString();
        if (order.effectiveCurrencyCode().equalsIgnoreCase(target)) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        if (!currentItems.isEmpty()) {
            int confirm = JOptionPane.showConfirmDialog(
                    this,
                    i18n.t("orders.currency_change_confirm", order.orderNumber(), target),
                    i18n.t("common.confirm"),
                    JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) {
                syncingCurrencyCombo = true;
                orderCurrencyCombo.setSelectedItem(order.effectiveCurrencyCode());
                syncingCurrencyCombo = false;
                return;
            }
        }
        try {
            ctx.getOrderService().changeOrderCurrency(order.id(), target);
            refreshDataSelecting(order.id());
        } catch (Exception ex) {
            syncingCurrencyCombo = true;
            orderCurrencyCombo.setSelectedItem(order.effectiveCurrencyCode());
            syncingCurrencyCombo = false;
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleScanAdd() {
        Order order = getSelectedOrder();
        I18nManager i18n = ctx.getI18n();
        if (order == null) {
            JOptionPane.showMessageDialog(this, i18n.t("orders.select_order"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        String raw = scanQrField.getText().trim();
        if (raw.isEmpty()) return;

        Optional<InventoryPackage> pkgOpt = ctx.getPackageDao().findByQrCode(raw);
        if (pkgOpt.isEmpty()) {
            JOptionPane.showMessageDialog(this, i18n.t("scanner.status.valid_not_found") + " [" + raw + "]",
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            scanQrField.selectAll();
            return;
        }
        try {
            InventoryPackage pkg = pkgOpt.get();
            ctx.getOrderService().addPackageToOrder(order.id(), pkg.id(), null, ctx.getSession().requireCurrentUser().id());
            Toolkit.getDefaultToolkit().beep();
            scanQrField.setText("");
            refreshDataSelecting(order.id());
            scanQrField.requestFocusInWindow();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handlePickFromStock() {
        Order order = getSelectedOrder();
        I18nManager i18n = ctx.getI18n();
        if (order == null) {
            JOptionPane.showMessageDialog(this, i18n.t("orders.select_order"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        String lang = i18n.getActiveLanguage();
        List<InventoryPackage> available = ctx.getPackageDao().findByFilter(
                new PackageFilter(null, PackageStatus.IN_STOCK, null, null, null, null, null));
        if (available.isEmpty()) {
            JOptionPane.showMessageDialog(this, i18n.t("orders.empty_stock"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        Object[] options = available.stream()
                .map(p -> p.qrCode() + "  [" + p.shortSummary(lang) + " | " + stripZeros(p.weightKg()) + " kg]")
                .toArray();
        Object chosen = JOptionPane.showInputDialog(this, i18n.t("orders.add_from_stock"),
                order.orderNumber(), JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (chosen == null) return;

        String qr = chosen.toString().split(" ")[0];
        available.stream().filter(p -> p.qrCode().equals(qr)).findFirst().ifPresent(pkg -> {
            try {
                ctx.getOrderService().addPackageToOrder(order.id(), pkg.id(), null, ctx.getSession().requireCurrentUser().id());
                refreshDataSelecting(order.id());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void handleRemoveItem() {
        Order order = getSelectedOrder();
        int row = itemsTable.getSelectedRow();
        if (order == null || row < 0 || row >= currentItems.size()) return;

        try {
            ctx.getOrderService().removeItemFromOrder(order.id(), currentItems.get(row).id(),
                    ctx.getSession().requireCurrentUser().id());
            refreshDataSelecting(order.id());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), ctx.getI18n().t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleApplyStatus() {
        Order order = getSelectedOrder();
        if (order == null || !(statusDropdown.getSelectedItem() instanceof StatusStepOption opt)) {
            return;
        }
        OrderStatus target = opt.status();
        if (target == order.status()) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        if (order.status() == OrderStatus.SHIPPED && target != OrderStatus.SHIPPED) {
            if (JOptionPane.showConfirmDialog(this, i18n.t("orders.status.revert_confirm"),
                    i18n.t("common.confirm"), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                syncStatusDropdown(order.status());
                return;
            }
        } else if (target == OrderStatus.CANCELLED) {
            if (JOptionPane.showConfirmDialog(this, i18n.t("orders.cancel_order") + " (" + order.orderNumber() + ")?",
                    i18n.t("common.confirm"), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                syncStatusDropdown(order.status());
                return;
            }
        }
        try {
            ctx.getOrderService().changeOrderStatus(order.id(), target, ctx.getSession().requireCurrentUser().id());
            refreshDataSelecting(order.id());
        } catch (Exception ex) {
            syncStatusDropdown(order.status());
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handlePreviewDocuments() {
        Order order = getSelectedOrder();
        I18nManager i18n = ctx.getI18n();
        if (order == null) {
            JOptionPane.showMessageDialog(this, i18n.t("orders.select_order"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Customer customer = ctx.getCustomerDao().findById(order.customerId()).orElse(null);
            List<OrderItem> items = ctx.getOrderItemDao().findByOrderId(order.id());

            // Auto-resolves customer country default VAT (HU 27%, EU international 0%, non-EU 0%)
            Invoice inv = ctx.getInvoiceService().ensureInvoiceForOrder(
                    order.id(), ctx.getSession().requireCurrentUser().id());
            List<InvoiceItem> invItems = ctx.getInvoiceService().findItems(inv.id());

            DocumentPageSource deliveryNoteDoc = ctx.getDeliveryNotePdfExporter().createPageSource(order, customer, items);
            DocumentPageSource invoiceDoc = ctx.getInvoicePdfExporter().createPageSource(inv, invItems);

            java.awt.Window owner = javax.swing.SwingUtilities.getWindowAncestor(this);
            DocumentPreviewDialog.showDocuments(
                    owner,
                    ctx,
                    List.of(deliveryNoteDoc, invoiceDoc),
                    customer != null ? customer.email() : "",
                    order.orderNumber() + " — " + i18n.t("orders.documents.preview")
            );
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void syncStatusDropdown(OrderStatus status) {
        for (int i = 0; i < statusDropdown.getItemCount(); i++) {
            if (statusDropdown.getItemAt(i).status() == status) {
                statusDropdown.setSelectedIndex(i);
                break;
            }
        }
    }

    private void renderSelectedOrder() {
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();
        Order order = getSelectedOrder();
        itemsModel.setRowCount(0);

        if (order == null) {
            currentItems = List.of();
            orderHeaderTitle.setText("—");
            orderStatusTag.setText(" ");
            orderCurrencyTag.setText(" ");
            orderMetaLabel.setText(" ");
            setEditButtonsEnabled(false, false);
            return;
        }

        String cur = order.effectiveCurrencyCode();
        int dec = ctx.getFinanceConfigDao().decimalPlacesFor(cur);
        orderHeaderTitle.setText(order.orderNumber() + " // " + order.customerName());
        orderStatusTag.setText(i18n.t("status.order." + order.status().name()).toUpperCase());
        BrutalistStyle.styleBoxedTag(orderStatusTag, order.status() == OrderStatus.SHIPPED || order.status() == OrderStatus.PACKING);
        orderCurrencyTag.setText(cur);
        BrutalistStyle.styleBoxedTag(orderCurrencyTag, false);
        syncingCurrencyCombo = true;
        orderCurrencyCombo.removeAllItems();
        boolean hasCur = false;
        for (CurrencyDef c : ctx.getFinanceConfigDao().findCurrencies(true)) {
            orderCurrencyCombo.addItem(c.code());
            if (c.code().equalsIgnoreCase(cur)) hasCur = true;
        }
        if (!hasCur) {
            orderCurrencyCombo.addItem(cur);
        }
        orderCurrencyCombo.setSelectedItem(cur);
        syncingCurrencyCombo = false;
        syncStatusDropdown(order.status());

        itemsModel.setColumnIdentifiers(new Object[]{
                i18n.t("intake.generated_code").toUpperCase(),
                i18n.t("intake.garment_type").toUpperCase(),
                "KG",
                cur + "/KG",
                cur
        });

        orderMetaLabel.setText(
                i18n.t("orders.total_weight") + ": " + stripZeros(order.totalWeightKg()) + " kg   |   "
                        + i18n.t("orders.total_price") + ": " + CurrencyDef.formatAmount(order.totalPrice(), cur, dec) + "   |   "
                        + i18n.t("common.created_at") + ": " + (order.createdAt() != null ? DT_FMT.format(order.createdAt()) : "—")
        );

        currentItems = ctx.getOrderItemDao().findByOrderId(order.id());
        for (OrderItem item : currentItems) {
            itemsModel.addRow(new Object[]{
                    item.packageQrCode(),
                    item.localizedSummary(lang),
                    stripZeros(item.weightKg()),
                    CurrencyDef.formatNumeric(item.pricePerKg(), dec),
                    CurrencyDef.formatNumeric(item.lineTotal(), dec)
            });
        }

        boolean editable = order.status() == OrderStatus.DRAFT || order.status() == OrderStatus.PACKING;
        setEditButtonsEnabled(editable, true);
    }

    private void setEditButtonsEnabled(boolean editable, boolean hasOrder) {
        scanQrField.setEnabled(editable);
        scanAddBtn.setEnabled(editable);
        pickStockBtn.setEnabled(editable);
        removeItemBtn.setEnabled(editable);
        orderCurrencyCombo.setEnabled(editable);
        statusDropdown.setEnabled(hasOrder);
        applyStatusBtn.setEnabled(hasOrder);
        previewDocsBtn.setEnabled(hasOrder);
    }

    private void refreshDataSelecting(long orderId) {
        refreshData();
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i).id() == orderId) {
                ordersTable.setRowSelectionInterval(i, i);
                break;
            }
        }
        renderSelectedOrder();
    }

    private static String stripZeros(BigDecimal val) {
        return FormatUtil.stripZeros(val);
    }

    @Override
    public void applyTheme() {
        applyBrutalistTheme();
        renderSelectedOrder();
    }

    public void applyBrutalistTheme() {
        BrutalistStyle.applyBoxCard(detailHeaderPanel, 12, 14);
        BrutalistStyle.applyBoxCard(scanBarPanel, 8, 10);
        BrutalistStyle.stylePrimaryButton(newOrderBtn);
        BrutalistStyle.styleSecondaryButton(applyStatusBtn);
        BrutalistStyle.stylePrimaryButton(previewDocsBtn);
        BrutalistStyle.styleTable(ordersTable);
        BrutalistStyle.styleTable(itemsTable);
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_ORDERS;
    }

    @Override
    public String getTitleI18nKey() {
        return "orders.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        I18nManager i18n = ctx.getI18n();
        Order prev = getSelectedOrder();
        Long prevId = prev != null ? prev.id() : null;

        orders = ctx.getOrderDao().findAll();
        ordersModel.setRowCount(0);
        int selectRow = -1;
        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            ordersModel.addRow(new Object[]{
                    o.orderNumber(),
                    o.customerName(),
                    i18n.t("status.order." + o.status().name()).toUpperCase(),
                    stripZeros(o.totalWeightKg()) + " kg",
                    CurrencyDef.formatAmount(o.totalPrice(), o.effectiveCurrencyCode(), o.currencyDecimals())
            });
            if (prevId != null && o.id() == prevId) {
                selectRow = i;
            }
        }
        if (selectRow >= 0) {
            ordersTable.setRowSelectionInterval(selectRow, selectRow);
        } else if (!orders.isEmpty()) {
            ordersTable.setRowSelectionInterval(0, 0);
        }
        renderSelectedOrder();
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        newOrderBtn.setText("+ " + i18n.t("orders.new_order").toUpperCase());
        refreshBtn.setText(i18n.t("common.refresh").toUpperCase());
        scanLabel.setText(i18n.t("orders.add_by_scan").toUpperCase() + ":");
        scanQrField.putClientProperty("JTextField.placeholderText", i18n.t("orders.scan_placeholder"));
        scanAddBtn.setText(i18n.t("common.add").toUpperCase());
        pickStockBtn.setText(i18n.t("orders.add_from_stock").toUpperCase());
        removeItemBtn.setText(i18n.t("orders.remove_item").toUpperCase());
        itemsTitleLabel.setText(i18n.t("orders.items_title").toUpperCase());
        statusSelectLabel.setText(i18n.t("orders.status.label").toUpperCase() + ":");
        statusDropdown.removeAllItems();
        statusDropdown.addItem(new StatusStepOption(OrderStatus.DRAFT, "1. " + i18n.t("status.order.DRAFT").toUpperCase()));
        statusDropdown.addItem(new StatusStepOption(OrderStatus.PACKING, "2. " + i18n.t("status.order.PACKING").toUpperCase()));
        statusDropdown.addItem(new StatusStepOption(OrderStatus.SHIPPED, "3. " + i18n.t("status.order.SHIPPED").toUpperCase()));
        statusDropdown.addItem(new StatusStepOption(OrderStatus.CANCELLED, "4. " + i18n.t("status.order.CANCELLED").toUpperCase()));
        applyStatusBtn.setText(i18n.t("orders.status.apply").toUpperCase());
        previewDocsBtn.setText(i18n.t("orders.documents.preview").toUpperCase());

        ordersModel.setColumnIdentifiers(new Object[]{
                i18n.t("orders.order_number").toUpperCase(),
                i18n.t("orders.customer").toUpperCase(),
                i18n.t("common.status").toUpperCase(),
                "KG",
                i18n.t("orders.total_price").toUpperCase()
        });
        itemsModel.setColumnIdentifiers(new Object[]{
                i18n.t("intake.generated_code").toUpperCase(),
                i18n.t("intake.garment_type").toUpperCase(),
                "KG",
                "EUR/KG",
                "EUR"
        });

        applyBrutalistTheme();
        refreshData();
    }
}
