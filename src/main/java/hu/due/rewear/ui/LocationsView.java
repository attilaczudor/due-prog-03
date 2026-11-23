package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.PackageFilter;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.WarehouseLocation;
import hu.due.rewear.service.LabelPreset;
import hu.due.rewear.util.FormatUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Warehouse Locations manager displaying live kg occupancy progress bars
 * and the list of packages stored on the selected rack/shelf.
 */
public final class LocationsView extends JPanel implements NavigableView {

    private final AppContext ctx;

    private final JButton addBtn = new JButton();
    private final JButton editBtn = new JButton();
    private final JButton refreshBtn = new JButton();

    private final DefaultTableModel locModel = BrutalistStyle.nonEditableTableModel();
    private final JTable locTable = new JTable(locModel);

    private final JLabel storedTitleLabel = new JLabel();
    private final JButton reprintStoredBtn = new JButton();
    private final DefaultTableModel storedModel = BrutalistStyle.nonEditableTableModel();
    private final JTable storedTable = new JTable(storedModel);

    private List<WarehouseLocation> locations = List.of();
    private List<InventoryPackage> storedPackages = List.of();

    public LocationsView(AppContext ctx) {
        this.ctx = ctx;

        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        buildUi();
        wireEvents();
        applyTranslations();
    }

    private void buildUi() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        toolbar.add(addBtn);
        toolbar.add(editBtn);
        toolbar.add(refreshBtn);
        add(toolbar, BorderLayout.NORTH);

        locTable.setRowHeight(28);
        locTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        locTable.setFillsViewportHeight(true);

        JPanel bottomPanel = new JPanel(new BorderLayout(0, 6));
        JPanel bottomHeader = new JPanel(new BorderLayout());
        storedTitleLabel.setFont(storedTitleLabel.getFont().deriveFont(Font.BOLD, 13.5f));
        bottomHeader.add(storedTitleLabel, BorderLayout.WEST);
        bottomHeader.add(reprintStoredBtn, BorderLayout.EAST);
        bottomPanel.add(bottomHeader, BorderLayout.NORTH);

        storedTable.setRowHeight(24);
        storedTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        storedTable.setFillsViewportHeight(true);
        bottomPanel.add(new JScrollPane(storedTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(locTable), bottomPanel);
        split.setResizeWeight(0.55);
        split.setDividerLocation(310);
        add(split, BorderLayout.CENTER);
    }

    private void wireEvents() {
        addBtn.addActionListener(e -> openLocationDialog(null));
        editBtn.addActionListener(e -> {
            WarehouseLocation sel = getSelectedLocation();
            if (sel != null) {
                openLocationDialog(sel);
            }
        });
        refreshBtn.addActionListener(e -> refreshData());

        locTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadStoredPackagesForSelected();
            }
        });

        reprintStoredBtn.addActionListener(e -> {
            int row = storedTable.getSelectedRow();
            if (row >= 0 && row < storedPackages.size()) {
                Window owner = SwingUtilities.getWindowAncestor(this);
                PrintPreviewDialog.showPreview(owner, ctx, List.of(storedPackages.get(row)), LabelPreset.THERMAL_100X150);
            }
        });

        storedTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int row = storedTable.getSelectedRow();
                    if (row >= 0 && row < storedPackages.size()) {
                        Window owner = SwingUtilities.getWindowAncestor(LocationsView.this);
                        if (EditPackageDialog.showDialog(owner, ctx, storedPackages.get(row)) != null) {
                            refreshData();
                        }
                    }
                }
            }
        });
    }

    private WarehouseLocation getSelectedLocation() {
        int row = locTable.getSelectedRow();
        return (row >= 0 && row < locations.size()) ? locations.get(row) : null;
    }

    private void openLocationDialog(WarehouseLocation existing) {
        I18nManager i18n = ctx.getI18n();
        JTextField codeField = new JTextField(existing != null ? existing.code() : "", 14);
        JTextField zoneField = new JTextField(existing != null ? existing.zone() : "A", 10);
        JTextField aisleField = new JTextField(existing != null ? existing.aisle() : "01", 10);
        JTextField shelfField = new JTextField(existing != null ? existing.shelf() : "01", 10);
        JComboBox<LocationType> typeCombo = new JComboBox<>(LocationType.values());
        typeCombo.setSelectedItem(existing != null ? existing.locationType() : LocationType.STORAGE);
        typeCombo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel lbl = new JLabel(val != null ? i18n.t("location.type." + val.name()) : "");
            lbl.setOpaque(true);
            lbl.setBackground(sel ? list.getSelectionBackground() : list.getBackground());
            lbl.setForeground(sel ? list.getSelectionForeground() : list.getForeground());
            lbl.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return lbl;
        });
        JTextField capField = new JTextField(existing != null ? FormatUtil.stripZeros(existing.maxCapacityKg()) : "600", 10);
        JCheckBox activeBox = new JCheckBox(i18n.t("common.activate"), existing == null || existing.active());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int r = 0;
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("common.code") + " *", codeField);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("locations.type") + " *", typeCombo);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("locations.zone") + " *", zoneField);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("locations.aisle") + " *", aisleField);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("locations.shelf") + " *", shelfField);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("locations.capacity") + " *", capField);
        if (existing != null) {
            gbc.gridx = 1;
            gbc.gridy = r;
            form.add(activeBox, gbc);
        }

        String title = i18n.t(existing == null ? "locations.add_title" : "locations.edit_title");
        int res = JOptionPane.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            String code = codeField.getText().trim().toUpperCase(java.util.Locale.ROOT);
            String zone = zoneField.getText().trim().toUpperCase(java.util.Locale.ROOT);
            String aisle = aisleField.getText().trim();
            String shelf = shelfField.getText().trim();
            LocationType locType = (LocationType) typeCombo.getSelectedItem();
            BigDecimal cap = new BigDecimal(capField.getText().trim().replace(',', '.'));
            if (code.isEmpty() || zone.isEmpty() || aisle.isEmpty() || shelf.isEmpty() || cap.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(i18n.t("validation.location_fields_required"));
            }
            if (existing == null) {
                ctx.getLocationDao().insert(code, zone, aisle, shelf, locType, cap);
            } else {
                ctx.getLocationDao().update(existing.id(), code, zone, aisle, shelf, locType, cap, activeBox.isSelected());
            }
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadStoredPackagesForSelected() {
        WarehouseLocation loc = getSelectedLocation();
        storedModel.setRowCount(0);
        if (loc == null) {
            storedPackages = List.of();
            return;
        }
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();
        List<InventoryPackage> allAtLoc = ctx.getPackageDao().findByFilter(
                new PackageFilter(null, null, null, null, null, null, loc.id())
        );
        storedPackages = allAtLoc.stream()
                .filter(p -> p.status() == PackageStatus.IN_STOCK || p.status() == PackageStatus.RESERVED)
                .toList();

        for (InventoryPackage p : storedPackages) {
            storedModel.addRow(new Object[]{
                    p.qrCode(),
                    p.shortSummary(lang),
                    FormatUtil.stripZeros(p.weightKg()) + " kg",
                    i18n.t("status.package." + p.status().name())
            });
        }
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_LOCATIONS;
    }

    @Override
    public String getTitleI18nKey() {
        return "locations.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        I18nManager i18n = ctx.getI18n();
        locations = ctx.getLocationDao().findAll(false);
        locModel.setRowCount(0);
        for (WarehouseLocation l : locations) {
            int pct = l.maxCapacityKg().compareTo(BigDecimal.ZERO) > 0
                    ? l.usedWeightKg().multiply(BigDecimal.valueOf(100))
                    .divide(l.maxCapacityKg(), 0, RoundingMode.HALF_UP).intValue()
                    : 0;
            locModel.addRow(new Object[]{
                    l.code(),
                    i18n.t("location.type." + l.locationType().name()),
                    l.zone(),
                    l.aisle(),
                    l.shelf(),
                    FormatUtil.stripZeros(l.usedWeightKg()),
                    FormatUtil.stripZeros(l.maxCapacityKg()),
                    pct,
                    l.active() ? i18n.t("common.yes") : i18n.t("common.no")
            });
        }
        if (locTable.getColumnCount() >= 8) {
            locTable.getColumnModel().getColumn(7).setCellRenderer(new OccupancyBarRenderer());
            locTable.getColumnModel().getColumn(7).setPreferredWidth(180);
        }
        if (!locations.isEmpty() && locTable.getSelectedRow() < 0) {
            locTable.setRowSelectionInterval(0, 0);
        }
        loadStoredPackagesForSelected();
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        addBtn.setText(i18n.t("common.add"));
        editBtn.setText(i18n.t("common.edit"));
        refreshBtn.setText(i18n.t("common.refresh"));
        storedTitleLabel.setText(i18n.t("locations.packages_here"));
        reprintStoredBtn.setText(i18n.t("scanner.action.reprint"));

        locModel.setColumnIdentifiers(new Object[]{
                i18n.t("common.code"),
                i18n.t("locations.type"),
                i18n.t("locations.zone"),
                i18n.t("locations.aisle"),
                i18n.t("locations.shelf"),
                i18n.t("locations.used"),
                i18n.t("locations.capacity"),
                i18n.t("locations.occupancy"),
                i18n.t("common.status")
        });

        storedModel.setColumnIdentifiers(new Object[]{
                i18n.t("intake.generated_code"),
                i18n.t("intake.garment_type"),
                i18n.t("intake.weight_kg"),
                i18n.t("common.status")
        });

        refreshData();
        applyTheme();
    }

    @Override
    public void applyTheme() {
        BrutalistStyle.stylePrimaryButton(addBtn);
        BrutalistStyle.styleSecondaryButtons(editBtn, refreshBtn, reprintStoredBtn);
        BrutalistStyle.styleTable(locTable);
        BrutalistStyle.styleTable(storedTable);
        locTable.repaint();
    }

    private static final class OccupancyBarRenderer extends JProgressBar implements TableCellRenderer {
        OccupancyBarRenderer() {
            super(0, 100);
            setStringPainted(true);
            setBorderPainted(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            int pct = value instanceof Number n ? n.intValue() : 0;
            setValue(Math.min(100, Math.max(0, pct)));
            setString(pct + "%");
            setBackground(ThemeManager.bg());
            setForeground(ThemeManager.fg());
            setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));
            return this;
        }
    }
}
