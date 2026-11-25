package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.RoleCode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Super-Admin-only interactive Role &times; Module access control matrix.
 * Allows turning every application menu module ON or OFF per user role ({@code ADMIN},
 * {@code MANAGER}, {@code OPERATOR}, {@code FORKLIFT}), while {@code SUPER_ADMIN}
 * always retains unrestricted access to every module.
 */
public final class AdminModulesPanel extends JPanel {

    private static final RoleCode[] ROLE_COLUMNS = RoleCode.values();

    private final AppContext ctx;
    private final JLabel infoLabel = new JLabel();
    private final JButton resetDefaultsBtn = new JButton();
    private final ModuleMatrixModel matrixModel = new ModuleMatrixModel();
    private final JTable matrixTable;

    private Map<RoleCode, Set<AppModule>> matrix = new EnumMap<>(RoleCode.class);

    public AdminModulesPanel(AppContext ctx) {
        this.ctx = ctx;
        this.matrixTable = new JTable(matrixModel);
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 4));
        buildUi();
        wireEvents();
        applyTranslations();
        applyTheme();
    }

    private void buildUi() {
        JPanel topBar = new JPanel(new BorderLayout(12, 0));
        topBar.setOpaque(false);
        infoLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
        topBar.add(infoLabel, BorderLayout.CENTER);
        JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightBtns.setOpaque(false);
        rightBtns.add(resetDefaultsBtn);
        topBar.add(rightBtns, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        BrutalistStyle.styleTable(matrixTable);
        matrixTable.setRowHeight(32);
        matrixTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        matrixTable.getTableHeader().setReorderingAllowed(false);
        add(new JScrollPane(matrixTable), BorderLayout.CENTER);
    }

    private void wireEvents() {
        resetDefaultsBtn.addActionListener(e -> {
            I18nManager i18n = ctx.getI18n();
            int confirm = JOptionPane.showConfirmDialog(
                    this,
                    i18n.t("admin.modules.reset_confirm"),
                    i18n.t("admin.tab.modules"),
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                ctx.getRoleModuleService().resetAllToDefaults();
                refreshData();
            }
        });
    }

    public void refreshData() {
        matrix = ctx.getRoleModuleService().getPermissionMatrix();
        matrixModel.fireTableDataChanged();
        configureColumnWidths();
    }

    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        infoLabel.setText(i18n.t("admin.modules.info"));
        resetDefaultsBtn.setText(i18n.t("admin.modules.reset").toUpperCase(Locale.ROOT));
        matrixModel.fireTableStructureChanged();
        configureColumnWidths();
        applyTheme();
    }

    public void applyTheme() {
        infoLabel.setForeground(ThemeManager.muted());
        BrutalistStyle.styleSecondaryButton(resetDefaultsBtn);
        BrutalistStyle.styleTable(matrixTable);
        matrixTable.setRowHeight(32);
        configureColumnWidths();
    }

    private void configureColumnWidths() {
        if (matrixTable.getColumnCount() < 7) {
            return;
        }
        var cm = matrixTable.getColumnModel();
        cm.getColumn(0).setPreferredWidth(210);
        cm.getColumn(1).setPreferredWidth(150);
        DefaultTableCellRenderer boldMod = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                lbl.setFont(ThemeManager.monoFont(column == 0 ? Font.BOLD : Font.PLAIN, 12f));
                lbl.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                if (!isSelected && column == 1) {
                    lbl.setForeground(ThemeManager.muted());
                }
                return lbl;
            }
        };
        cm.getColumn(0).setCellRenderer(boldMod);
        cm.getColumn(1).setCellRenderer(boldMod);
        for (int c = 2; c < 7; c++) {
            cm.getColumn(c).setPreferredWidth(115);
            cm.getColumn(c).setMinWidth(95);
        }
    }

    private final class ModuleMatrixModel extends AbstractTableModel {

        private final AppModule[] modules = AppModule.values();

        @Override
        public int getRowCount() {
            return modules.length;
        }

        @Override
        public int getColumnCount() {
            return 2 + ROLE_COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            I18nManager i18n = ctx.getI18n();
            if (column == 0) {
                return i18n.t("admin.modules.col.module").toUpperCase(Locale.ROOT);
            }
            if (column == 1) {
                return i18n.t("admin.modules.col.category").toUpperCase(Locale.ROOT);
            }
            RoleCode role = ROLE_COLUMNS[column - 2];
            String name = i18n.t("role." + role.name()).toUpperCase(Locale.ROOT);
            return role == RoleCode.SUPER_ADMIN ? name + " *" : name;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex >= 2 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            if (columnIndex < 2) {
                return false;
            }
            RoleCode role = ROLE_COLUMNS[columnIndex - 2];
            AppModule mod = modules[rowIndex];
            if (role == RoleCode.SUPER_ADMIN || mod.isAlwaysEnabled() || !mod.allowsRole(role)) {
                return false;
            }
            return ctx.getSession().isSuperAdmin();
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AppModule mod = modules[rowIndex];
            I18nManager i18n = ctx.getI18n();
            if (columnIndex == 0) {
                String label = i18n.t(mod.navI18nKey());
                return mod.isAlwaysEnabled() ? label + "  [" + i18n.t("admin.modules.always_on") + "]" : label;
            }
            if (columnIndex == 1) {
                return i18n.t(mod.category().i18nKey()).toUpperCase(Locale.ROOT);
            }
            RoleCode role = ROLE_COLUMNS[columnIndex - 2];
            if (role == RoleCode.SUPER_ADMIN || mod.isAlwaysEnabled()) {
                return Boolean.TRUE;
            }
            if (!mod.allowsRole(role)) return Boolean.FALSE;
            Set<AppModule> set = matrix.getOrDefault(role, EnumSet.noneOf(AppModule.class));
            return set.contains(mod);
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (!(aValue instanceof Boolean enabled) || columnIndex < 2) {
                return;
            }
            RoleCode role = ROLE_COLUMNS[columnIndex - 2];
            AppModule mod = modules[rowIndex];
            if (role == RoleCode.SUPER_ADMIN || mod.isAlwaysEnabled()) {
                return;
            }
            try {
                ctx.getRoleModuleService().setModuleEnabled(role, mod, enabled);
                matrix.computeIfAbsent(role, k -> EnumSet.noneOf(AppModule.class));
                if (enabled) {
                    matrix.get(role).add(mod);
                } else {
                    matrix.get(role).remove(mod);
                }
                fireTableCellUpdated(rowIndex, columnIndex);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(AdminModulesPanel.this, ex.getMessage(),
                        ctx.getI18n().t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
