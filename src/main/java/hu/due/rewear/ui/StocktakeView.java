package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.Stocktake.Count;
import hu.due.rewear.model.Stocktake.Missing;
import hu.due.rewear.model.Stocktake.Session;
import hu.due.rewear.model.WarehouseLocation;
import hu.due.rewear.service.StocktakeService;
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
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Counting the shelves. One session runs at a time: open it over a shelf, a zone or the whole warehouse,
 * scan what is there, and close it to correct the record. Everything found that did not match is listed,
 * together with what was expected but never turned up.
 */
public final class StocktakeView extends JPanel implements NavigableView {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;
    private final StocktakeService service;

    private final JLabel sessionLabel = new JLabel();
    private final JLabel scanLabel = new JLabel();
    private final JTextField scanField = new JTextField(22);
    private final JTextField weightField = new JTextField(7);
    private final JCheckBox damagedBox = new JCheckBox();
    private final JButton countBtn = new JButton();
    private final JButton startBtn = new JButton();
    private final JButton closeBtn = new JButton();
    private final JButton cancelBtn = new JButton();
    private final JLabel feedbackLabel = new JLabel(" ");
    private final JLabel summaryLabel = new JLabel();

    private final DefaultTableModel countsModel = BrutalistStyle.nonEditableTableModel();
    private final JTable countsTable = BrutalistStyle.tableWithTooltips(countsModel);
    private final DefaultTableModel missingModel = BrutalistStyle.nonEditableTableModel();
    private final JTable missingTable = BrutalistStyle.tableWithTooltips(missingModel);
    private final DefaultTableModel quarantineModel = BrutalistStyle.nonEditableTableModel();
    private final JTable quarantineTable = BrutalistStyle.tableWithTooltips(quarantineModel);
    private final JButton returnBtn = new JButton();
    private final JButton writeOffBtn = new JButton();
    private List<InventoryPackage> quarantineRows = List.of();

    private Session current;

    public StocktakeView(AppContext ctx) {
        super(new BorderLayout(0, 10));
        this.ctx = ctx;
        this.service = ctx.getStocktakeService();
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        bar.setOpaque(false);
        bar.add(sessionLabel);
        bar.add(startBtn);
        bar.add(scanLabel);
        scanField.setFont(ThemeManager.monoFont(Font.PLAIN, 12.5f));
        bar.add(scanField);
        bar.add(weightField);
        bar.add(damagedBox);
        bar.add(countBtn);
        bar.add(closeBtn);
        bar.add(cancelBtn);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(bar, BorderLayout.NORTH);
        header.add(feedbackLabel, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        JPanel quarantinePanel = titled("stocktake.quarantine", quarantineTable);
        JPanel quarantineActions = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        quarantineActions.setOpaque(false);
        quarantineActions.add(returnBtn);
        quarantineActions.add(writeOffBtn);
        quarantinePanel.add(quarantineActions, BorderLayout.SOUTH);

        JPanel tables = new JPanel(new java.awt.GridLayout(3, 1, 0, 8));
        tables.setOpaque(false);
        tables.add(titled("stocktake.counted", countsTable));
        tables.add(titled("stocktake.missing", missingTable));
        tables.add(quarantinePanel);
        add(tables, BorderLayout.CENTER);

        summaryLabel.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        add(summaryLabel, BorderLayout.SOUTH);

        startBtn.addActionListener(e -> guarded(this::startSession));
        countBtn.addActionListener(e -> guarded(this::countScanned));
        scanField.addActionListener(e -> guarded(this::countScanned));
        closeBtn.addActionListener(e -> guarded(this::closeSession));
        cancelBtn.addActionListener(e -> guarded(this::cancelSession));
        returnBtn.addActionListener(e -> guarded(() -> decideQuarantine(false)));
        writeOffBtn.addActionListener(e -> guarded(() -> decideQuarantine(true)));

        applyTranslations();
        applyTheme();
    }

    private JPanel titled(String key, JTable table) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel title = new JLabel(t(key));
        title.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        title.setName(key);
        panel.add(title, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private void guarded(Runnable action) {
        try {
            action.run();
            refreshData();
        } catch (RuntimeException ex) {
            feedbackLabel.setText(t("common.warning") + " — " + ex.getMessage());
        }
    }

    private void startSession() {
        var zones = ctx.getLocationDao().findAll(true).stream()
                .map(WarehouseLocation::zone).distinct().sorted().toList();
        JComboBox<String> scope = new JComboBox<>();
        scope.addItem(t("stocktake.scope.all"));
        for (String zone : zones) {
            scope.addItem(t("stocktake.scope.zone") + " " + zone);
        }
        List<WarehouseLocation> shelves = ctx.getLocationDao().findByType(LocationType.STORAGE, true);
        for (WarehouseLocation shelf : shelves) {
            scope.addItem(t("stocktake.scope.shelf") + " " + shelf.code());
        }
        JTextField note = new JTextField(20);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        BrutalistStyle.addFormRow(form, gbc, 0, t("stocktake.scope"), scope);
        BrutalistStyle.addFormRow(form, gbc, 1, t("invoice.history.note"), note);
        if (JOptionPane.showConfirmDialog(this, form, t("stocktake.start"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        int index = scope.getSelectedIndex();
        if (index <= 0) {
            service.open(null, null, note.getText());
        } else if (index <= zones.size()) {
            service.open(zones.get(index - 1), null, note.getText());
        } else {
            service.open(null, shelves.get(index - zones.size() - 1).id(), note.getText());
        }
        feedbackLabel.setText(" ");
    }

    private void countScanned() {
        if (current == null) {
            throw new IllegalStateException(t("stocktake.none_open"));
        }
        String code = scanField.getText().strip();
        if (code.isEmpty()) {
            return;
        }
        BigDecimal weight = weightField.getText().isBlank()
                ? null : new BigDecimal(weightField.getText().strip().replace(',', '.'));
        Count counted = service.count(current.id(), code, weight, damagedBox.isSelected(), null);
        scanField.setText("");
        weightField.setText("");
        damagedBox.setSelected(false);
        feedbackLabel.setText(counted.qrCode() + " — " + t("stocktake.outcome." + counted.outcome().name()));
        scanField.requestFocusInWindow();
    }

    private void closeSession() {
        if (current == null) {
            throw new IllegalStateException(t("stocktake.none_open"));
        }
        if (JOptionPane.showConfirmDialog(this, t("stocktake.close_confirm"), t("common.confirm"),
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        var applied = service.close(current.id());
        JOptionPane.showMessageDialog(this,
                t("stocktake.applied", applied.relocated(), applied.reweighed(), applied.quarantined(), applied.missing()),
                t("common.success"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void cancelSession() {
        if (current == null) {
            throw new IllegalStateException(t("stocktake.none_open"));
        }
        if (JOptionPane.showConfirmDialog(this, t("stocktake.cancel_confirm"), t("common.confirm"),
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            service.cancel(current.id());
        }
    }

    /** Releases the selected quarantined package back to stock, or writes it off for good. */
    private void decideQuarantine(boolean writeOff) {
        int row = TableSorting.selectedModelRow(quarantineTable);
        if (row < 0 || row >= quarantineRows.size()) {
            throw new IllegalStateException(t("stocktake.select_quarantined"));
        }
        InventoryPackage item = quarantineRows.get(row);
        String reason = JOptionPane.showInputDialog(this,
                t(writeOff ? "stocktake.write_off_confirm" : "stocktake.return_confirm", item.qrCode()),
                t(writeOff ? "stocktake.write_off" : "stocktake.return"), JOptionPane.QUESTION_MESSAGE);
        if (reason == null) {
            return;
        }
        if (writeOff) {
            service.writeOffQuarantined(item.id(), reason);
        } else {
            service.returnToStock(item.id(), reason);
        }
        feedbackLabel.setText(item.qrCode() + " — " + t(writeOff ? "stocktake.write_off" : "stocktake.return"));
    }

    @Override public void refreshData() {
        current = service.openSession().orElse(null);
        boolean open = current != null;
        boolean mayCount = service.mayCount();

        sessionLabel.setText(open
                ? t("stocktake.open_session", current.id(), current.scopeLabel(t("stocktake.scope.all")))
                : t("stocktake.none_open"));
        startBtn.setEnabled(mayCount && !open);
        for (JComponent component : List.of(scanField, weightField, damagedBox, countBtn, closeBtn, cancelBtn)) {
            component.setEnabled(mayCount && open);
        }

        quarantineRows = service.quarantined();
        quarantineModel.setRowCount(0);
        for (InventoryPackage item : quarantineRows) {
            quarantineModel.addRow(new Object[]{item.qrCode(), item.locationCode(),
                    FormatUtil.stripZeros(item.weightKg()) + " kg", item.notes() == null ? "" : item.notes()});
        }
        boolean hasQuarantine = !quarantineRows.isEmpty();
        returnBtn.setEnabled(mayCount && hasQuarantine);
        writeOffBtn.setEnabled(mayCount && hasQuarantine);

        countsModel.setRowCount(0);
        missingModel.setRowCount(0);
        if (!open) {
            summaryLabel.setText(t("stocktake.history", service.recentSessions(5).size())
                    + (hasQuarantine ? "  |  " + t("stocktake.in_quarantine", quarantineRows.size()) : ""));
            return;
        }
        List<Count> counts = service.counts(current.id());
        for (Count line : counts) {
            String difference = line.countedGrams() == null ? ""
                    : FormatUtil.stripZeros(BigDecimal.valueOf(line.differenceGrams(), 3)) + " kg";
            countsModel.addRow(new Object[]{line.qrCode(), t("stocktake.outcome." + line.outcome().name()),
                    line.expectedLocationCode(), line.foundLocationCode(),
                    FormatUtil.stripZeros(line.expectedKg()) + " kg",
                    line.countedKg() == null ? "" : FormatUtil.stripZeros(line.countedKg()) + " kg",
                    difference, line.note() == null ? "" : line.note(),
                    line.countedBy(), TIME.format(line.countedAt())});
        }
        List<Missing> missing = service.missing(current.id());
        for (Missing gone : missing) {
            missingModel.addRow(new Object[]{gone.qrCode(), gone.locationCode(),
                    FormatUtil.stripZeros(BigDecimal.valueOf(gone.grams(), 3)) + " kg"});
        }
        summaryLabel.setText(t("stocktake.summary", counts.size(), missing.size())
                + (hasQuarantine ? "  |  " + t("stocktake.in_quarantine", quarantineRows.size()) : ""));
    }

    @Override public void applyTranslations() {
        startBtn.setText(t("stocktake.start"));
        countBtn.setText(t("stocktake.count"));
        closeBtn.setText(t("stocktake.close"));
        cancelBtn.setText(t("common.cancel"));
        scanLabel.setText(t("stocktake.scan") + ":");
        damagedBox.setText(t("stocktake.damaged"));
        weightField.setToolTipText(t("stocktake.counted_kg"));
        countsModel.setColumnIdentifiers(new Object[]{t("intake.generated_code"), t("common.status"),
                t("stocktake.expected_at"), t("stocktake.found_at"), t("stocktake.expected_kg"),
                t("stocktake.counted_kg"), t("stocktake.difference"), t("invoice.history.note"),
                t("invoice.history.by"), t("invoice.history.when")});
        missingModel.setColumnIdentifiers(new Object[]{t("intake.generated_code"), t("intake.location"),
                t("intake.weight_kg")});
        quarantineModel.setColumnIdentifiers(new Object[]{t("intake.generated_code"), t("intake.location"),
                t("intake.weight_kg"), t("intake.notes")});
        returnBtn.setText(t("stocktake.return"));
        writeOffBtn.setText(t("stocktake.write_off"));
        refreshData();
    }

    @Override public void applyTheme() {
        setBackground(ThemeManager.bg());
        sessionLabel.setForeground(ThemeManager.fg());
        summaryLabel.setForeground(ThemeManager.fg());
        feedbackLabel.setForeground(ThemeManager.muted());
        scanLabel.setForeground(ThemeManager.fg());
        BrutalistStyle.stylePrimaryButton(countBtn);
        BrutalistStyle.styleSecondaryButtons(startBtn, closeBtn, cancelBtn, returnBtn, writeOffBtn);
        BrutalistStyle.styleTable(countsTable);
        BrutalistStyle.styleTable(missingTable);
        BrutalistStyle.styleTable(quarantineTable);
    }

    @Override public String getViewKey() { return MainFrame.VIEW_STOCKTAKE; }
    @Override public String getTitleI18nKey() { return "nav.stocktake"; }
    @Override public JComponent getComponent() { return this; }

    private String t(String key, Object... args) { return ctx.getI18n().t(key, args); }
}
