package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import hu.due.rewear.util.FormatUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.List;
import java.util.Locale;

/**
 * Manager-facing Customer Handover QR Verification dialog: every allocated package on the order
 * must be scanned and verified against the order checklist before completing handover and shipping.
 */
public final class OrderHandoverDialog extends JDialog {

    private final AppContext ctx;
    private final Order order;

    private final JLabel progressLabel = new JLabel();
    private final JTextField scanField = new JTextField(28);
    private final JButton scanBtn = new JButton();
    private final JButton scanSelectedBtn = new JButton();
    private final JLabel feedbackLabel = new JLabel(" ");

    private final DefaultTableModel itemsModel = BrutalistStyle.nonEditableTableModel();
    private final JTable itemsTable = new JTable(itemsModel);

    private final JButton completeBtn = new JButton();
    private final JButton closeBtn = new JButton();

    private List<OrderItem> items = List.of();
    private boolean handedOver = false;

    public OrderHandoverDialog(Window owner, AppContext ctx, Order order) {
        super(owner, ctx.getI18n().t("order.handover.title") + " — " + order.orderNumber(),
                ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        this.order = order;

        setMinimumSize(new Dimension(780, 520));
        setPreferredSize(new Dimension(860, 560));

        buildUi();
        wireEvents();
        reloadItems();

        pack();
        BrutalistStyle.installEscapeToClose(this);
        setLocationRelativeTo(owner);
        scanField.requestFocusInWindow();
    }

    public static boolean showDialog(Window owner, AppContext ctx, Order order) {
        OrderHandoverDialog dlg = new OrderHandoverDialog(owner, ctx, order);
        dlg.setVisible(true);
        return dlg.handedOver;
    }

    private void buildUi() {
        I18nManager i18n = ctx.getI18n();
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        // Top info + scan bar
        JPanel north = new JPanel(new BorderLayout(0, 8));
        JPanel infoBar = new JPanel(new BorderLayout(8, 0));
        BrutalistStyle.applyBoxCard(infoBar, 8, 12);
        String dock = order.dispatchLocationCode() != null ? order.dispatchLocationCode() : "DOCK-01";
        JLabel orderInfo = new JLabel(order.orderNumber() + "  —  " + order.customerName()
                + "  |  " + i18n.t("transport.target_dock") + ": " + dock);
        orderInfo.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        progressLabel.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        infoBar.add(orderInfo, BorderLayout.WEST);
        infoBar.add(progressLabel, BorderLayout.EAST);
        north.add(infoBar, BorderLayout.NORTH);

        JPanel scanBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        scanBar.add(new JLabel(i18n.t("order.handover.scan_label") + ":"));
        scanField.setFont(ThemeManager.monoFont(Font.PLAIN, 13f));
        scanBtn.setText(i18n.t("order.handover.scan_btn").toUpperCase(Locale.ROOT));
        scanSelectedBtn.setText(i18n.t("order.handover.scan_selected").toUpperCase(Locale.ROOT));
        BrutalistStyle.stylePrimaryButton(scanBtn);
        BrutalistStyle.styleSecondaryButton(scanSelectedBtn);
        scanBar.add(scanField);
        scanBar.add(scanBtn);
        scanBar.add(scanSelectedBtn);
        north.add(scanBar, BorderLayout.CENTER);

        feedbackLabel.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        north.add(feedbackLabel, BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);

        // Checklist table
        itemsModel.setColumnIdentifiers(new Object[]{
                i18n.t("intake.generated_code").toUpperCase(Locale.ROOT),
                i18n.t("intake.garment_type").toUpperCase(Locale.ROOT),
                i18n.t("intake.weight_kg").toUpperCase(Locale.ROOT),
                i18n.t("intake.location").toUpperCase(Locale.ROOT),
                i18n.t("common.status").toUpperCase(Locale.ROOT)
        });
        BrutalistStyle.styleTable(itemsTable);
        itemsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        root.add(new JScrollPane(itemsTable), BorderLayout.CENTER);

        // Bottom actions
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        completeBtn.setText(i18n.t("order.handover.complete_btn").toUpperCase(Locale.ROOT));
        closeBtn.setText(i18n.t("common.close").toUpperCase(Locale.ROOT));
        BrutalistStyle.stylePrimaryButton(completeBtn);
        BrutalistStyle.styleSecondaryButton(closeBtn);
        south.add(completeBtn);
        south.add(closeBtn);
        root.add(south, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private void wireEvents() {
        scanField.addActionListener(e -> performScan(scanField.getText()));
        scanBtn.addActionListener(e -> performScan(scanField.getText()));
        scanSelectedBtn.addActionListener(e -> {
            int row = TableSorting.selectedModelRow(itemsTable);
            if (row >= 0 && row < items.size()) {
                performScan(items.get(row).packageQrCode());
            }
        });
        completeBtn.addActionListener(e -> handleCompleteHandover());
        closeBtn.addActionListener(e -> dispose());
    }

    private void performScan(String rawCode) {
        I18nManager i18n = ctx.getI18n();
        try {
            long managerId = ctx.getSession().requireCurrentUser().id();
            OrderItem verified = ctx.getOrderService().scanOrderItemForHandover(order.id(), rawCode, managerId);
            feedbackLabel.setText("✓ " + i18n.t("order.handover.scan_ok", verified.packageQrCode()));
            scanField.setText("");
            reloadItems();
            scanField.requestFocusInWindow();
        } catch (Exception ex) {
            feedbackLabel.setText("! " + ex.getMessage());
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            scanField.selectAll();
            scanField.requestFocusInWindow();
        }
    }

    private void handleCompleteHandover() {
        I18nManager i18n = ctx.getI18n();
        try {
            long managerId = ctx.getSession().requireCurrentUser().id();
            ctx.getOrderService().completeVerifiedHandover(order.id(), managerId);
            handedOver = true;
            JOptionPane.showMessageDialog(this, i18n.t("order.handover.completed", order.orderNumber()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void reloadItems() {
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();
        items = ctx.getOrderItemDao().findByOrderId(order.id());
        itemsModel.setRowCount(0);
        int scannedCount = 0;
        for (OrderItem item : items) {
            if (item.handoverScanned()) scannedCount++;
            itemsModel.addRow(new Object[]{
                    item.packageQrCode(),
                    item.localizedSummary(lang),
                    FormatUtil.stripZeros(item.weightKg()) + " kg",
                    item.currentLocationCode(),
                    item.handoverScanned()
                            ? i18n.t("order.handover.status.scanned")
                            : i18n.t("order.handover.status.pending")
            });
        }
        if (itemsTable.getColumnCount() >= 5) {
            itemsTable.getColumnModel().getColumn(4).setCellRenderer(new ScanStatusRenderer());
        }
        progressLabel.setText(i18n.t("order.handover.progress", scannedCount, items.size()));
        completeBtn.setEnabled(!items.isEmpty() && scannedCount == items.size());
    }

    private final class ScanStatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int col) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            lbl.setFont(ThemeManager.monoFont(Font.BOLD, 11f));
            String text = value != null ? value.toString() : "";
            lbl.setText("[ " + text.toUpperCase(Locale.ROOT) + " ]");
            if (!isSelected && row >= 0 && row < items.size()) {
                boolean ok = items.get(row).handoverScanned();
                lbl.setBackground(ok ? ThemeManager.fg() : ThemeManager.bg());
                lbl.setForeground(ok ? ThemeManager.bg() : ThemeManager.fg());
            }
            return lbl;
        }
    }
}
