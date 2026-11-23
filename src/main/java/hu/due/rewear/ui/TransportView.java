package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.WarehouseLocation;
import hu.due.rewear.service.TransportService;
import hu.due.rewear.util.FormatUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Forklift Driver &amp; In-House Transporter workspace:
 * <ol>
 *     <li><b>Putaway Queue:</b> pick up newly sorted packages from {@link LocationType#OPERATOR_STATION}
 *     workstations and move them onto {@link LocationType#STORAGE} shelves.</li>
 *     <li><b>Order Staging Queue:</b> collect {@code RESERVED} packages for active orders from storage
 *     shelves and bring them to the {@link LocationType#DISPATCH_ZONE} connection dock.</li>
 * </ol>
 */
public final class TransportView extends JPanel implements NavigableView {

    public enum Mode {
        PUTAWAY,
        STAGING
    }

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;
    private final Mode mode;

    // Putaway (OPERATOR_STATION -> STORAGE)
    private final JLabel putawayTargetLabel = new JLabel();
    private final JComboBox<LocChoice> storageCombo = new JComboBox<>();
    private final JButton moveToStorageBtn = new JButton();
    private final DefaultTableModel putawayModel = BrutalistStyle.nonEditableTableModel();
    private final JTable putawayTable = new JTable(putawayModel);
    private final JLabel putawaySummaryLabel = new JLabel();

    // Tab 2: Order Collection (STORAGE -> DISPATCH_ZONE, with live QR/Barcode scan)
    private final JLabel scanCollectLabel = new JLabel();
    private final javax.swing.JTextField scanCollectField = new javax.swing.JTextField(24);
    private final JButton scanCollectBtn = new JButton();
    private final JLabel stagingTargetLabel = new JLabel();
    private final JComboBox<LocChoice> dispatchCombo = new JComboBox<>();
    private final JButton moveToDispatchBtn = new JButton();
    private final DefaultTableModel stagingModel = BrutalistStyle.nonEditableTableModel();
    private final JTable stagingTable = new JTable(stagingModel);
    private final JLabel stagingSummaryLabel = new JLabel();

    private List<InventoryPackage> putawayRows = List.of();
    private List<TransportService.StagingTask> stagingRows = List.of();

    private record LocChoice(WarehouseLocation location, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public TransportView(AppContext ctx) {
        this(ctx, Mode.PUTAWAY);
    }

    public TransportView(AppContext ctx, Mode mode) {
        this.ctx = ctx;
        this.mode = mode;
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        buildUi();
        wireEvents();
        applyTranslations();
        applyTheme();
    }

    private void buildUi() {
        if (mode == Mode.PUTAWAY) {
            JPanel putawayPanel = new JPanel(new BorderLayout(0, 8));
            JPanel putawayBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
            putawayBar.add(putawayTargetLabel);
            putawayBar.add(storageCombo);
            putawayBar.add(moveToStorageBtn);
            putawayPanel.add(putawayBar, BorderLayout.NORTH);

            BrutalistStyle.styleTable(putawayTable);
            putawayTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            putawayPanel.add(new JScrollPane(putawayTable), BorderLayout.CENTER);
            putawaySummaryLabel.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
            putawayPanel.add(putawaySummaryLabel, BorderLayout.SOUTH);
            add(putawayPanel, BorderLayout.CENTER);
        } else {
            JPanel stagingPanel = new JPanel(new BorderLayout(0, 8));
            JPanel stagingBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
            stagingBar.add(scanCollectLabel);
            scanCollectField.setFont(ThemeManager.monoFont(Font.PLAIN, 12.5f));
            stagingBar.add(scanCollectField);
            stagingBar.add(scanCollectBtn);
            stagingBar.add(stagingTargetLabel);
            stagingBar.add(dispatchCombo);
            stagingBar.add(moveToDispatchBtn);
            stagingPanel.add(stagingBar, BorderLayout.NORTH);

            BrutalistStyle.styleTable(stagingTable);
            stagingTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            stagingPanel.add(new JScrollPane(stagingTable), BorderLayout.CENTER);
            stagingSummaryLabel.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
            stagingPanel.add(stagingSummaryLabel, BorderLayout.SOUTH);
            add(stagingPanel, BorderLayout.CENTER);
        }
    }

    private void wireEvents() {
        moveToStorageBtn.addActionListener(e -> handleMoveToStorage());
        moveToDispatchBtn.addActionListener(e -> handleMoveToDispatch());
        scanCollectField.addActionListener(e -> handleScanCollect());
        scanCollectBtn.addActionListener(e -> handleScanCollect());
    }

    private void handleScanCollect() {
        I18nManager i18n = ctx.getI18n();
        String code = scanCollectField.getText().trim();
        if (code.isEmpty()) {
            return;
        }
        Long fallbackDockId = (dispatchCombo.getSelectedItem() instanceof LocChoice c) ? c.location().id() : null;
        try {
            var me = ctx.getSession().requireCurrentUser();
            boolean driverOnly = ctx.getSession().isForklift();
            TransportService.StagingTask done = ctx.getTransportService()
                    .scanAndCollectForOrder(code, fallbackDockId, me.id(), driverOnly);
            scanCollectField.setText("");
            refreshData();
            JOptionPane.showMessageDialog(this,
                    i18n.t("transport.scan_collected_ok", done.qrCode(), done.orderNumber(), done.currentLocationCode()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            scanCollectField.requestFocusInWindow();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            scanCollectField.selectAll();
            scanCollectField.requestFocusInWindow();
        }
    }

    private void handleMoveToStorage() {
        I18nManager i18n = ctx.getI18n();
        int[] selectedRows = putawayTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this, i18n.t("transport.select_packages"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!(storageCombo.getSelectedItem() instanceof LocChoice choice)) {
            return;
        }
        List<Long> ids = new ArrayList<>();
        for (int vr : selectedRows) {
            int mr = putawayTable.convertRowIndexToModel(vr);
            if (mr >= 0 && mr < putawayRows.size()) {
                ids.add(putawayRows.get(mr).id());
            }
        }
        try {
            long driverId = ctx.getSession().requireCurrentUser().id();
            ctx.getTransportService().putawayToStorage(ids, choice.location().id(), driverId);
            refreshData();
            JOptionPane.showMessageDialog(this,
                    i18n.t("transport.moved_success", ids.size(), choice.location().code()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleMoveToDispatch() {
        I18nManager i18n = ctx.getI18n();
        int[] selectedRows = stagingTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this, i18n.t("transport.select_packages"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!(dispatchCombo.getSelectedItem() instanceof LocChoice choice)) {
            return;
        }
        List<Long> ids = new ArrayList<>();
        for (int vr : selectedRows) {
            int mr = stagingTable.convertRowIndexToModel(vr);
            if (mr >= 0 && mr < stagingRows.size()) {
                TransportService.StagingTask task = stagingRows.get(mr);
                if (!task.isAtDispatchZone()) {
                    ids.add(task.packageId());
                }
            }
        }
        if (ids.isEmpty()) {
            JOptionPane.showMessageDialog(this, i18n.t("transport.already_staged"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            long driverId = ctx.getSession().requireCurrentUser().id();
            ctx.getTransportService().stageToDispatchZone(ids, choice.location().id(), driverId);
            refreshData();
            JOptionPane.showMessageDialog(this,
                    i18n.t("transport.moved_success", ids.size(), choice.location().code()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public String getViewKey() {
        return mode == Mode.PUTAWAY ? MainFrame.VIEW_PUTAWAY : MainFrame.VIEW_STAGING;
    }

    @Override
    public String getTitleI18nKey() {
        return mode == Mode.PUTAWAY ? "transport.tab.putaway" : "transport.tab.staging";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();

        if (mode == Mode.PUTAWAY) {
            storageCombo.removeAllItems();
            for (WarehouseLocation loc : ctx.getLocationDao().findByType(LocationType.STORAGE, true)) {
                String lbl = loc.code() + " (" + FormatUtil.stripZeros(loc.usedWeightKg()) + " / "
                        + FormatUtil.stripZeros(loc.maxCapacityKg()) + " kg)";
                storageCombo.addItem(new LocChoice(loc, lbl));
            }

            putawayRows = ctx.getTransportService().findPutawayQueue();
            putawayModel.setRowCount(0);
            for (InventoryPackage p : putawayRows) {
                putawayModel.addRow(new Object[]{
                        p.locationCode(),
                        p.qrCode(),
                        p.targetGroupCode() + " / " + p.localizedGarmentType(lang) + " (" + p.qualityGradeCode() + ")",
                        FormatUtil.stripZeros(p.weightKg()) + " kg",
                        p.createdAt() != null ? DT_FMT.format(p.createdAt()) : ""
                });
            }
            putawaySummaryLabel.setText(i18n.t("transport.putaway.summary", putawayRows.size()));
        } else {
            dispatchCombo.removeAllItems();
            for (WarehouseLocation loc : ctx.getLocationDao().findByType(LocationType.DISPATCH_ZONE, true)) {
                String lbl = loc.code() + " (" + FormatUtil.stripZeros(loc.usedWeightKg()) + " / "
                        + FormatUtil.stripZeros(loc.maxCapacityKg()) + " kg)";
                dispatchCombo.addItem(new LocChoice(loc, lbl));
            }

            Long driverFilter = ctx.getSession().isForklift()
                    ? ctx.getSession().requireCurrentUser().id()
                    : null;
            stagingRows = ctx.getTransportService().findOrderStagingTasksForDriver(driverFilter);
            stagingModel.setRowCount(0);
            int pending = 0;
            for (TransportService.StagingTask t : stagingRows) {
                if (!t.isAtDispatchZone()) pending++;
                String custCol = (t.customerArrived() ? "[!] " : "") + t.customerName();
                String dockCol = t.targetDispatchLocationCode() != null ? t.targetDispatchLocationCode() : "DOCK-01";
                stagingModel.addRow(new Object[]{
                        t.orderNumber(),
                        custCol,
                        t.qrCode(),
                        FormatUtil.stripZeros(t.weightKg()) + " kg",
                        t.currentLocationCode() + " -> " + dockCol,
                        t.isAtDispatchZone() ? i18n.t("transport.status.staged") : i18n.t("transport.status.pickup")
                });
            }
            if (stagingTable.getColumnCount() >= 6) {
                stagingTable.getColumnModel().getColumn(5).setCellRenderer(new StagingStatusRenderer());
            }
            stagingSummaryLabel.setText(i18n.t("transport.staging.summary", pending, stagingRows.size()));
        }
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();

        putawayTargetLabel.setText(i18n.t("transport.target_shelf") + ":");
        moveToStorageBtn.setText(i18n.t("transport.btn.move_storage"));
        putawayModel.setColumnIdentifiers(new Object[]{
                i18n.t("location.type.OPERATOR_STATION"),
                i18n.t("intake.generated_code"),
                i18n.t("intake.garment_type"),
                i18n.t("intake.weight_kg"),
                i18n.t("common.created_at")
        });

        scanCollectLabel.setText(i18n.t("transport.scan_collect_label") + ":");
        scanCollectBtn.setText(i18n.t("transport.scan_collect_btn"));
        stagingTargetLabel.setText(i18n.t("transport.target_dock") + ":");
        moveToDispatchBtn.setText(i18n.t("transport.btn.move_dock"));
        stagingModel.setColumnIdentifiers(new Object[]{
                i18n.t("order.number"),
                i18n.t("order.customer"),
                i18n.t("intake.generated_code"),
                i18n.t("intake.weight_kg"),
                i18n.t("intake.location"),
                i18n.t("common.status")
        });
        applyTheme();
    }

    @Override
    public void applyTheme() {
        BrutalistStyle.stylePrimaryButton(moveToStorageBtn);
        BrutalistStyle.stylePrimaryButton(scanCollectBtn);
        BrutalistStyle.styleSecondaryButtons(moveToDispatchBtn);
        BrutalistStyle.styleTable(putawayTable);
        BrutalistStyle.styleTable(stagingTable);
    }

    private final class StagingStatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int col) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            lbl.setFont(ThemeManager.monoFont(Font.BOLD, 11f));
            String text = value != null ? value.toString() : "";
            lbl.setText("[ " + text.toUpperCase(Locale.ROOT) + " ]");
            if (!isSelected) {
                int mr = table.convertRowIndexToModel(row);
                boolean staged = mr >= 0 && mr < stagingRows.size() && stagingRows.get(mr).isAtDispatchZone();
                lbl.setBackground(staged ? ThemeManager.surface() : ThemeManager.fg());
                lbl.setForeground(staged ? ThemeManager.muted() : ThemeManager.bg());
            }
            return lbl;
        }
    }
}
