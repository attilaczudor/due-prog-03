package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.GarmentType;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.PackageFilter;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.PackageType;
import hu.due.rewear.model.QualityGrade;
import hu.due.rewear.model.StockMovement;
import hu.due.rewear.model.TargetGroup;
import hu.due.rewear.model.WarehouseLocation;
import hu.due.rewear.service.LabelPreset;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
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
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Searchable, multi-criteria Inventory Browser supporting details & history inspection,
 * relocation, single/batch A4 4-up print preview, discarding, and CSV export.
 */
public final class InventoryView extends JPanel implements NavigableView {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;

    private final JTextField searchField = new JTextField(18);
    private final JComboBox<FilterItem<PackageStatus>> statusFilter = new JComboBox<>();
    private final JComboBox<FilterItem<Long>> targetFilter = new JComboBox<>();
    private final JComboBox<FilterItem<Long>> garmentFilter = new JComboBox<>();
    private final JComboBox<FilterItem<Long>> qualityFilter = new JComboBox<>();
    private final JComboBox<FilterItem<Long>> packageFilter = new JComboBox<>();
    private final JComboBox<FilterItem<Long>> locationFilter = new JComboBox<>();

    private final JButton searchBtn = new JButton();
    private final JButton clearBtn = new JButton();
    private final JButton refreshBtn = new JButton();

    private final JButton detailsBtn = new JButton();
    private final JButton editBtn = new JButton();
    private final JButton relocateBtn = new JButton();
    private final JButton printSingleBtn = new JButton();
    private final JButton batchPrintBtn = new JButton();
    private final JButton discardBtn = new JButton();
    private final JButton exportCsvBtn = new JButton();

    private final DefaultTableModel tableModel = new DefaultTableModel() {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final JLabel summaryLabel = new JLabel(" ");

    private List<InventoryPackage> currentRows = List.of();
    private boolean loadingFilters = false;
    private JPanel filterBarPanel;

    private record FilterItem<T>(T value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public InventoryView(AppContext ctx) {
        this.ctx = ctx;

        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        buildUi();
        wireEvents();
        applyTranslations();
        refreshData();
    }

    private void buildUi() {
        // Filter Bar (clean 2-row brutalist panel so every dropdown & button stays visible)
        JPanel topPanel = new JPanel(new BorderLayout(0, 6));

        filterBarPanel = new JPanel(new BorderLayout(0, 4));
        JPanel searchRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        searchRow.add(searchField);
        searchRow.add(locationFilter);
        searchRow.add(searchBtn);
        searchRow.add(clearBtn);
        searchRow.add(refreshBtn);

        JPanel dropdownRow = new JPanel(new GridLayout(1, 5, 6, 0));
        dropdownRow.add(statusFilter);
        dropdownRow.add(targetFilter);
        dropdownRow.add(garmentFilter);
        dropdownRow.add(qualityFilter);
        dropdownRow.add(packageFilter);

        filterBarPanel.add(searchRow, BorderLayout.NORTH);
        filterBarPanel.add(dropdownRow, BorderLayout.SOUTH);
        BrutalistStyle.applyBoxCard(filterBarPanel, 6, 8);
        topPanel.add(filterBarPanel, BorderLayout.NORTH);

        JPanel actionRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        actionRow.add(editBtn);
        actionRow.add(detailsBtn);
        actionRow.add(relocateBtn);
        actionRow.add(printSingleBtn);
        actionRow.add(batchPrintBtn);
        if (ctx.getSession().isManagerOrAdmin()) {
            actionRow.add(discardBtn);
        }
        if (ctx.getSession().canExportReports()) {
            actionRow.add(exportCsvBtn);
        }
        topPanel.add(actionRow, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                topPanel.revalidate();
                revalidate();
            }
        });

        // Table
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(26);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        add(new JScrollPane(table), BorderLayout.CENTER);

        // Summary Footer
        JPanel footer = new JPanel(new BorderLayout());
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 12.5f));
        footer.add(summaryLabel, BorderLayout.WEST);
        add(footer, BorderLayout.SOUTH);
    }

    private void wireEvents() {
        searchField.addActionListener(e -> loadTableRows());
        searchBtn.addActionListener(e -> loadTableRows());
        refreshBtn.addActionListener(e -> refreshData());
        clearBtn.addActionListener(e -> {
            searchField.setText("");
            statusFilter.setSelectedIndex(0);
            targetFilter.setSelectedIndex(0);
            garmentFilter.setSelectedIndex(0);
            qualityFilter.setSelectedIndex(0);
            packageFilter.setSelectedIndex(0);
            locationFilter.setSelectedIndex(0);
            loadTableRows();
        });

        statusFilter.addActionListener(e -> { if (!loadingFilters) loadTableRows(); });
        targetFilter.addActionListener(e -> { if (!loadingFilters) loadTableRows(); });
        garmentFilter.addActionListener(e -> { if (!loadingFilters) loadTableRows(); });
        qualityFilter.addActionListener(e -> { if (!loadingFilters) loadTableRows(); });
        packageFilter.addActionListener(e -> { if (!loadingFilters) loadTableRows(); });
        locationFilter.addActionListener(e -> { if (!loadingFilters) loadTableRows(); });

        detailsBtn.addActionListener(e -> showDetailsDialog());
        editBtn.addActionListener(e -> openEditDialog());
        relocateBtn.addActionListener(e -> handleRelocate());
        printSingleBtn.addActionListener(e -> handleSinglePrint());
        batchPrintBtn.addActionListener(e -> handleBatchPrint());
        discardBtn.addActionListener(e -> handleDiscard());
        exportCsvBtn.addActionListener(e -> handleExportCsv());

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openEditDialog();
                }
            }
        });
    }

    private void openEditDialog() {
        InventoryPackage pkg = requireSingleSelection();
        if (pkg == null) {
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        InventoryPackage updated = EditPackageDialog.showDialog(owner, ctx, pkg);
        if (updated != null) {
            refreshData();
        }
    }

    private void populateFilterDropdowns() {
        loadingFilters = true;
        try {
            I18nManager i18n = ctx.getI18n();
            String lang = i18n.getActiveLanguage();
            String all = i18n.t("common.all");

            PackageStatus prevStatus = selectedValue(statusFilter);
            Long prevTarget = selectedValue(targetFilter);
            Long prevGarment = selectedValue(garmentFilter);
            Long prevQuality = selectedValue(qualityFilter);
            Long prevPkg = selectedValue(packageFilter);
            Long prevLoc = selectedValue(locationFilter);

            statusFilter.removeAllItems();
            statusFilter.addItem(new FilterItem<>(null, i18n.t("common.status") + ": " + all));
            for (PackageStatus s : PackageStatus.values()) {
                FilterItem<PackageStatus> item = new FilterItem<>(s, i18n.t("status.package." + s.name()));
                statusFilter.addItem(item);
                if (s == prevStatus) {
                    statusFilter.setSelectedItem(item);
                }
            }

            targetFilter.removeAllItems();
            targetFilter.addItem(new FilterItem<>(null, i18n.t("intake.target_group") + ": " + all));
            for (TargetGroup tg : ctx.getDictionaryDao().findTargetGroups(false)) {
                FilterItem<Long> item = new FilterItem<>(tg.id(), tg.displayLabel(lang));
                targetFilter.addItem(item);
                if (tg.id() == (prevTarget != null ? prevTarget : -1L)) {
                    targetFilter.setSelectedItem(item);
                }
            }

            garmentFilter.removeAllItems();
            garmentFilter.addItem(new FilterItem<>(null, i18n.t("intake.garment_type") + ": " + all));
            for (GarmentType gt : ctx.getDictionaryDao().findGarmentTypes(false)) {
                FilterItem<Long> item = new FilterItem<>(gt.id(), gt.displayLabel(lang));
                garmentFilter.addItem(item);
                if (gt.id() == (prevGarment != null ? prevGarment : -1L)) {
                    garmentFilter.setSelectedItem(item);
                }
            }

            qualityFilter.removeAllItems();
            qualityFilter.addItem(new FilterItem<>(null, i18n.t("intake.quality_grade") + ": " + all));
            for (QualityGrade qg : ctx.getDictionaryDao().findQualityGrades(false)) {
                FilterItem<Long> item = new FilterItem<>(qg.id(), qg.displayLabel(lang));
                qualityFilter.addItem(item);
                if (qg.id() == (prevQuality != null ? prevQuality : -1L)) {
                    qualityFilter.setSelectedItem(item);
                }
            }

            packageFilter.removeAllItems();
            packageFilter.addItem(new FilterItem<>(null, i18n.t("intake.package_type") + ": " + all));
            for (PackageType pt : ctx.getDictionaryDao().findPackageTypes(false)) {
                FilterItem<Long> item = new FilterItem<>(pt.id(), pt.displayLabel(lang));
                packageFilter.addItem(item);
                if (pt.id() == (prevPkg != null ? prevPkg : -1L)) {
                    packageFilter.setSelectedItem(item);
                }
            }

            locationFilter.removeAllItems();
            locationFilter.addItem(new FilterItem<>(null, i18n.t("intake.location") + ": " + all));
            for (WarehouseLocation loc : ctx.getLocationDao().findAll(false)) {
                FilterItem<Long> item = new FilterItem<>(loc.id(), loc.code());
                locationFilter.addItem(item);
                if (loc.id() == (prevLoc != null ? prevLoc : -1L)) {
                    locationFilter.setSelectedItem(item);
                }
            }
        } finally {
            loadingFilters = false;
        }
    }

    private void loadTableRows() {
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();

        PackageFilter filter = new PackageFilter(
                searchField.getText(),
                selectedValue(statusFilter),
                selectedValue(targetFilter),
                selectedValue(garmentFilter),
                selectedValue(qualityFilter),
                selectedValue(packageFilter),
                selectedValue(locationFilter)
        );
        currentRows = ctx.getPackageDao().findByFilter(filter);

        tableModel.setRowCount(0);
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (InventoryPackage p : currentRows) {
            totalWeight = totalWeight.add(p.weightKg());
            tableModel.addRow(new Object[]{
                    p.qrCode(),
                    p.targetGroupCode() + " — " + p.localizedTargetGroup(lang),
                    p.garmentTypeCode() + " — " + p.localizedGarmentType(lang),
                    p.qualityGradeCode() + " — " + p.localizedQualityGrade(lang),
                    p.packageTypeCode() + " — " + p.localizedPackageType(lang),
                    stripZeros(p.weightKg()),
                    p.locationCode(),
                    p.status(),
                    p.createdAt() != null ? DT_FMT.format(p.createdAt()) : ""
            });
        }

        if (table.getColumnCount() >= 8) {
            table.getColumnModel().getColumn(0).setPreferredWidth(235);
            table.getColumnModel().getColumn(7).setCellRenderer(new StatusCellRenderer());
        }

        summaryLabel.setText(i18n.t("inventory.summary", currentRows.size(), stripZeros(totalWeight)));
    }

    private List<InventoryPackage> getSelectedPackages() {
        int[] viewRows = table.getSelectedRows();
        List<InventoryPackage> selected = new ArrayList<>();
        for (int vr : viewRows) {
            int mr = table.convertRowIndexToModel(vr);
            if (mr >= 0 && mr < currentRows.size()) {
                selected.add(currentRows.get(mr));
            }
        }
        return selected;
    }

    private InventoryPackage requireSingleSelection() {
        List<InventoryPackage> sel = getSelectedPackages();
        if (sel.isEmpty()) {
            JOptionPane.showMessageDialog(this, ctx.getI18n().t("inventory.select_one"),
                    ctx.getI18n().t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return sel.get(0);
    }

    private void showDetailsDialog() {
        InventoryPackage pkg = requireSingleSelection();
        if (pkg == null) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();
        Window owner = SwingUtilities.getWindowAncestor(this);

        JDialog dialog = new JDialog(owner, i18n.t("inventory.view_details") + " — " + pkg.qrCode(),
                JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setMinimumSize(new Dimension(760, 480));

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JPanel header = new JPanel(new BorderLayout(14, 0));
        JLabel qr = new JLabel(new ImageIcon(ctx.getQrCodeService().generateQrImage(pkg.qrCode(), 130)));
        header.add(qr, BorderLayout.WEST);

        String html = "<html><body style='font-family:sans-serif;'>"
                + "<h3>" + pkg.qrCode() + "</h3>"
                + "<b>" + i18n.t("intake.target_group") + ":</b> " + pkg.targetGroupCode() + " — " + pkg.localizedTargetGroup(lang) + "<br>"
                + "<b>" + i18n.t("intake.garment_type") + ":</b> " + pkg.garmentTypeCode() + " — " + pkg.localizedGarmentType(lang) + "<br>"
                + "<b>" + i18n.t("intake.quality_grade") + ":</b> " + pkg.qualityGradeCode() + " — " + pkg.localizedQualityGrade(lang) + "<br>"
                + "<b>" + i18n.t("intake.package_type") + ":</b> " + pkg.packageTypeCode() + " — " + pkg.localizedPackageType(lang) + "<br>"
                + "<b>" + i18n.t("intake.weight_kg") + ":</b> " + stripZeros(pkg.weightKg()) + " kg &nbsp;|&nbsp; "
                + "<b>" + i18n.t("intake.location") + ":</b> " + pkg.locationCode() + " &nbsp;|&nbsp; "
                + "<b>" + i18n.t("common.status") + ":</b> " + i18n.t("status.package." + pkg.status().name())
                + "</body></html>";
        header.add(new JLabel(html), BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);

        DefaultTableModel histModel = new DefaultTableModel(new Object[]{
                i18n.t("common.created_at"), i18n.t("common.actions"),
                i18n.t("intake.location"), i18n.t("scanner.field.operator")
        }, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        for (StockMovement m : ctx.getMovementDao().findByPackageId(pkg.id())) {
            String fromTo = (m.fromLocationCode() != null ? m.fromLocationCode() : "—")
                    + " -> " + (m.toLocationCode() != null ? m.toLocationCode() : "—");
            histModel.addRow(new Object[]{
                    m.createdAt() != null ? DT_FMT.format(m.createdAt()) : "",
                    i18n.t("movement." + m.movementType().name()),
                    fromTo,
                    m.userFullName()
            });
        }
        JTable histTable = new JTable(histModel);
        histTable.setRowHeight(24);
        root.add(new JScrollPane(histTable), BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton printBtn = new JButton(i18n.t("scanner.action.reprint"));
        printBtn.addActionListener(e -> PrintPreviewDialog.showPreview(dialog, ctx, List.of(pkg), LabelPreset.THERMAL_100X150));
        JButton closeBtn = new JButton(i18n.t("common.close"));
        closeBtn.addActionListener(e -> dialog.dispose());
        south.add(printBtn);
        south.add(closeBtn);
        root.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private void handleRelocate() {
        InventoryPackage pkg = requireSingleSelection();
        if (pkg == null) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        List<WarehouseLocation> locations = ctx.getLocationDao().findAll(true);
        Object[] options = locations.stream()
                .map(l -> l.code() + " (" + stripZeros(l.usedWeightKg()) + "/" + stripZeros(l.maxCapacityKg()) + " kg)")
                .toArray();
        Object chosen = JOptionPane.showInputDialog(this, i18n.t("scanner.relocate.prompt"),
                i18n.t("scanner.action.relocate"), JOptionPane.PLAIN_MESSAGE, null, options,
                options.length > 0 ? options[0] : null);
        if (chosen == null) {
            return;
        }
        String code = chosen.toString().split(" ")[0];
        locations.stream().filter(l -> l.code().equals(code)).findFirst().ifPresent(loc -> {
            try {
                ctx.getInventoryService().relocatePackage(pkg.id(), loc.id(), "Relocated from Inventory Browser",
                        ctx.getSession().requireCurrentUser().id());
                refreshData();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void handleSinglePrint() {
        InventoryPackage pkg = requireSingleSelection();
        if (pkg == null) {
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        PrintPreviewDialog.showPreview(owner, ctx, List.of(pkg), LabelPreset.THERMAL_100X150);
    }

    private void handleBatchPrint() {
        List<InventoryPackage> batch = getSelectedPackages();
        if (batch.isEmpty()) {
            batch = currentRows;
        }
        if (batch.isEmpty()) {
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        PrintPreviewDialog.showPreview(owner, ctx, batch, LabelPreset.A4_4UP);
    }

    private void handleDiscard() {
        InventoryPackage pkg = requireSingleSelection();
        if (pkg == null) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        int ans = JOptionPane.showConfirmDialog(this, i18n.t("inventory.discard.confirm", pkg.qrCode()),
                i18n.t("common.confirm"), JOptionPane.YES_NO_OPTION);
        if (ans != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            ctx.getInventoryService().discardPackage(pkg.id(), "Discarded by manager/admin",
                    ctx.getSession().requireCurrentUser().id());
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleExportCsv() {
        I18nManager i18n = ctx.getI18n();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n.t("common.export.csv"));
        chooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        chooser.setSelectedFile(new File("rewear-inventory.csv"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            Path target = file.getName().toLowerCase().endsWith(".csv")
                    ? file.toPath()
                    : Path.of(file.getAbsolutePath() + ".csv");
            try {
                ctx.getCsvExportService().exportPackages(currentRows, target);
                JOptionPane.showMessageDialog(this, i18n.t("print.exported", target.toAbsolutePath()),
                        i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T selectedValue(JComboBox<FilterItem<T>> combo) {
        Object o = combo.getSelectedItem();
        return o instanceof FilterItem<?> fi ? ((FilterItem<T>) fi).value() : null;
    }

    private static String stripZeros(BigDecimal val) {
        if (val == null) {
            return "0";
        }
        BigDecimal s = val.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_INVENTORY;
    }

    @Override
    public String getTitleI18nKey() {
        return "inventory.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        populateFilterDropdowns();
        loadTableRows();
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        searchBtn.setText(i18n.t("common.search"));
        clearBtn.setText(i18n.t("common.clear"));
        refreshBtn.setText(i18n.t("common.refresh"));
        editBtn.setText(i18n.t("common.edit"));
        detailsBtn.setText(i18n.t("inventory.view_details"));
        relocateBtn.setText(i18n.t("scanner.action.relocate"));
        printSingleBtn.setText(i18n.t("scanner.action.reprint"));
        batchPrintBtn.setText(i18n.t("inventory.batch_print"));
        discardBtn.setText(i18n.t("inventory.discard"));
        exportCsvBtn.setText(i18n.t("common.export.csv"));

        tableModel.setColumnIdentifiers(new Object[]{
                i18n.t("intake.generated_code"),
                i18n.t("intake.target_group"),
                i18n.t("intake.garment_type"),
                i18n.t("intake.quality_grade"),
                i18n.t("intake.package_type"),
                i18n.t("intake.weight_kg"),
                i18n.t("intake.location"),
                i18n.t("common.status"),
                i18n.t("common.created_at")
        });

        refreshData();
        applyTheme();
    }

    @Override
    public void applyTheme() {
        if (filterBarPanel != null) {
            BrutalistStyle.applyBoxCard(filterBarPanel, 6, 8);
        }
        BrutalistStyle.stylePrimaryButton(batchPrintBtn);
        BrutalistStyle.styleTable(table);
        table.repaint();
    }

    private final class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            if (value instanceof PackageStatus status) {
                lbl.setText("[ " + ctx.getI18n().t("status.package." + status.name()).toUpperCase() + " ]");
                lbl.setFont(ThemeManager.monoFont(Font.BOLD, 11f));
                if (!isSelected) {
                    if (status == PackageStatus.IN_STOCK) {
                        lbl.setBackground(ThemeManager.fg());
                        lbl.setForeground(ThemeManager.bg());
                    } else if (status == PackageStatus.RESERVED) {
                        lbl.setBackground(ThemeManager.bg());
                        lbl.setForeground(ThemeManager.fg());
                    } else {
                        lbl.setBackground(ThemeManager.surface());
                        lbl.setForeground(ThemeManager.muted());
                    }
                }
            }
            return lbl;
        }
    }
}
