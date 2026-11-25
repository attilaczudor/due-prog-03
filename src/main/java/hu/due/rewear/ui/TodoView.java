package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.PersonalTodo;
import hu.due.rewear.model.SystemTodoItem;
import hu.due.rewear.model.User;

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
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Dedicated To-Do workspace available to all roles:
 * <ul>
 *     <li>Top summary KPI cards (System Tasks &amp; Personal To-Dos).</li>
 *     <li><b>System To-Do List</b> (read-only, logically ordered P1..P4 for the signed-in user's role).</li>
 *     <li><b>Personal To-Do List</b> (separate private user checklist persisted in SQLite).</li>
 * </ul>
 */
public final class TodoView extends JPanel implements NavigableView {

    private final AppContext ctx;
    private final Consumer<String> navigator;

    // Summary KPI cards
    private final JPanel kpiSysCard = new JPanel(new BorderLayout(0, 4));
    private final JLabel kpiSysTitle = new JLabel();
    private final JLabel kpiSysValue = new JLabel("0");

    private final JPanel kpiPersCard = new JPanel(new BorderLayout(0, 4));
    private final JLabel kpiPersTitle = new JLabel();
    private final JLabel kpiPersValue = new JLabel("0");

    // Top: System To-Dos
    private final JLabel sysSectionTitle = new JLabel();
    private final JButton openSysTargetBtn = new JButton();
    private final DefaultTableModel sysModel = BrutalistStyle.nonEditableTableModel();
    private final JTable sysTable = new JTable(sysModel);

    // Bottom: Personal To-Dos
    private final JLabel persSectionTitle = new JLabel();
    private final JButton addPersBtn = new JButton();
    private final JButton togglePersBtn = new JButton();
    private final JButton deletePersBtn = new JButton();
    private final DefaultTableModel persModel = BrutalistStyle.nonEditableTableModel();
    private final JTable persTable = new JTable(persModel);

    private List<SystemTodoItem> systemItems = List.of();
    private List<PersonalTodo> personalItems = List.of();

    public TodoView(AppContext ctx, Consumer<String> navigator) {
        this.ctx = ctx;
        this.navigator = navigator;
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        buildUi();
        wireEvents();
        applyTranslations();
        applyTheme();
    }

    private void buildUi() {
        JPanel kpiStrip = new JPanel(new GridLayout(1, 2, 12, 0));
        configureKpiCard(kpiSysCard, kpiSysTitle, kpiSysValue);
        configureKpiCard(kpiPersCard, kpiPersTitle, kpiPersValue);
        kpiStrip.add(kpiSysCard);
        kpiStrip.add(kpiPersCard);
        add(kpiStrip, BorderLayout.NORTH);

        JPanel sysPanel = new JPanel(new BorderLayout(0, 6));
        sysPanel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        JPanel sysHeader = new JPanel(new BorderLayout(8, 0));
        sysSectionTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        sysHeader.add(sysSectionTitle, BorderLayout.WEST);
        JPanel sysBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        sysBtns.add(openSysTargetBtn);
        sysHeader.add(sysBtns, BorderLayout.EAST);
        sysPanel.add(sysHeader, BorderLayout.NORTH);

        BrutalistStyle.styleTable(sysTable);
        sysTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sysPanel.add(new JScrollPane(sysTable), BorderLayout.CENTER);

        JPanel persPanel = new JPanel(new BorderLayout(0, 6));
        persPanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        JPanel persHeader = new JPanel(new BorderLayout(8, 0));
        persSectionTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        persHeader.add(persSectionTitle, BorderLayout.WEST);
        JPanel persBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        persBtns.add(addPersBtn);
        persBtns.add(togglePersBtn);
        persBtns.add(deletePersBtn);
        persHeader.add(persBtns, BorderLayout.EAST);
        persPanel.add(persHeader, BorderLayout.NORTH);

        BrutalistStyle.styleTable(persTable);
        persTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        persPanel.add(new JScrollPane(persTable), BorderLayout.CENTER);

        JSplitPane todoSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sysPanel, persPanel);
        todoSplit.setResizeWeight(0.54);
        todoSplit.setDividerLocation(270);
        add(todoSplit, BorderLayout.CENTER);
    }

    private static void configureKpiCard(JPanel card, JLabel title, JLabel value) {
        title.setFont(ThemeManager.monoFont(Font.BOLD, 11f));
        value.setFont(ThemeManager.monoFont(Font.BOLD, 22f));
        card.add(title, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
    }

    private void wireEvents() {
        openSysTargetBtn.addActionListener(e -> openSelectedSystemTarget());
        sysTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelectedSystemTarget();
                }
            }
        });

        addPersBtn.addActionListener(e -> handleAddPersonalTodo());
        togglePersBtn.addActionListener(e -> handleTogglePersonalTodo());
        deletePersBtn.addActionListener(e -> handleDeletePersonalTodo());
        persTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    handleTogglePersonalTodo();
                }
            }
        });
    }

    private void openSelectedSystemTarget() {
        int row = TableSorting.selectedModelRow(sysTable);
        if (row >= 0 && row < systemItems.size() && navigator != null) {
            String target = systemItems.get(row).targetView();
            if (target != null && !target.isBlank()) {
                navigator.accept(target);
            }
        }
    }

    private void handleAddPersonalTodo() {
        I18nManager i18n = ctx.getI18n();
        JTextField titleField = new JTextField(28);
        JComboBox<String> prioCombo = new JComboBox<>(new String[]{
                PersonalTodo.PRIO_HIGH, PersonalTodo.PRIO_MEDIUM, PersonalTodo.PRIO_LOW
        });
        prioCombo.setSelectedItem(PersonalTodo.PRIO_MEDIUM);
        JTextField dueField = new JTextField("", 14);
        dueField.setToolTipText("yyyy-MM-dd");

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("todo.col.task") + " *", titleField);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("todo.col.priority"), prioCombo);
        BrutalistStyle.addFormRow(form, gbc, r, i18n.t("invoice.due_date") + " (yyyy-MM-dd)", dueField);

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("todo.personal.add"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            LocalDate due = dueField.getText().isBlank() ? null : LocalDate.parse(dueField.getText().trim());
            ctx.getTodoService().addPersonalTodo(
                    ctx.getSession().requireCurrentUser().id(),
                    titleField.getText(),
                    String.valueOf(prioCombo.getSelectedItem()),
                    due);
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleTogglePersonalTodo() {
        int row = TableSorting.selectedModelRow(persTable);
        if (row >= 0 && row < personalItems.size()) {
            PersonalTodo item = personalItems.get(row);
            ctx.getTodoService().togglePersonalTodo(item.id(), !item.completed());
            refreshData();
        }
    }

    private void handleDeletePersonalTodo() {
        int row = TableSorting.selectedModelRow(persTable);
        if (row >= 0 && row < personalItems.size()) {
            ctx.getTodoService().deletePersonalTodo(personalItems.get(row).id());
            refreshData();
        }
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_TODO;
    }

    @Override
    public String getTitleI18nKey() {
        return "todo.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        I18nManager i18n = ctx.getI18n();
        User me = ctx.getSession().requireCurrentUser();

        systemItems = ctx.getTodoService().buildSystemTodosFor(me, i18n);
        sysModel.setRowCount(0);
        for (SystemTodoItem item : systemItems) {
            sysModel.addRow(new Object[]{
                    item.priorityBadge(),
                    item.category(),
                    item.summary(),
                    item.detail()
            });
        }
        if (sysTable.getColumnCount() >= 4) {
            sysTable.getColumnModel().getColumn(0).setPreferredWidth(64);
            sysTable.getColumnModel().getColumn(0).setMaxWidth(72);
            sysTable.getColumnModel().getColumn(0).setCellRenderer(new PriorityCellRenderer());
            sysTable.getColumnModel().getColumn(1).setPreferredWidth(150);
            sysTable.getColumnModel().getColumn(2).setPreferredWidth(340);
            sysTable.getColumnModel().getColumn(3).setPreferredWidth(260);
        }

        personalItems = ctx.getTodoService().listPersonalTodos(me.id());
        persModel.setRowCount(0);
        int openPersonal = 0;
        for (PersonalTodo p : personalItems) {
            if (!p.completed()) openPersonal++;
            persModel.addRow(new Object[]{
                    p.completed() ? "[✓]" : "[ ]",
                    p.priority(),
                    p.title(),
                    p.dueDate() != null ? p.dueDate().toString() : "—"
            });
        }
        if (persTable.getColumnCount() >= 4) {
            persTable.getColumnModel().getColumn(0).setPreferredWidth(54);
            persTable.getColumnModel().getColumn(0).setMaxWidth(64);
            persTable.getColumnModel().getColumn(1).setPreferredWidth(90);
            persTable.getColumnModel().getColumn(1).setMaxWidth(100);
        }

        kpiSysValue.setText(String.valueOf(systemItems.size()));
        kpiPersValue.setText(openPersonal + " / " + personalItems.size());
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        kpiSysTitle.setText(i18n.t("todo.kpi.system").toUpperCase(Locale.ROOT));
        kpiPersTitle.setText(i18n.t("todo.kpi.personal").toUpperCase(Locale.ROOT));

        sysSectionTitle.setText(i18n.t("todo.system.header").toUpperCase(Locale.ROOT));
        openSysTargetBtn.setText(i18n.t("todo.btn.open_target").toUpperCase(Locale.ROOT));

        persSectionTitle.setText(i18n.t("todo.personal.header").toUpperCase(Locale.ROOT));
        addPersBtn.setText("+ " + i18n.t("todo.personal.add").toUpperCase(Locale.ROOT));
        togglePersBtn.setText(i18n.t("todo.personal.toggle").toUpperCase(Locale.ROOT));
        deletePersBtn.setText(i18n.t("common.delete").toUpperCase(Locale.ROOT));

        sysModel.setColumnIdentifiers(new Object[]{
                i18n.t("todo.col.priority").toUpperCase(Locale.ROOT),
                i18n.t("invoice.category").toUpperCase(Locale.ROOT),
                i18n.t("todo.col.task").toUpperCase(Locale.ROOT),
                i18n.t("todo.col.details").toUpperCase(Locale.ROOT)
        });
        persModel.setColumnIdentifiers(new Object[]{
                "✓",
                i18n.t("todo.col.priority").toUpperCase(Locale.ROOT),
                i18n.t("todo.col.task").toUpperCase(Locale.ROOT),
                i18n.t("invoice.due_date").toUpperCase(Locale.ROOT)
        });
        applyTheme();
    }

    @Override
    public void applyTheme() {
        BrutalistStyle.applyBoxCard(kpiSysCard, 10, 12);
        BrutalistStyle.applyBoxCard(kpiPersCard, 10, 12);
        BrutalistStyle.stylePrimaryButton(addPersBtn);
        BrutalistStyle.stylePrimaryButton(openSysTargetBtn);
        BrutalistStyle.styleSecondaryButtons(togglePersBtn, deletePersBtn);
        BrutalistStyle.styleTable(sysTable);
        BrutalistStyle.styleTable(persTable);
    }

    private static final class PriorityCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int col) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            lbl.setFont(ThemeManager.monoFont(Font.BOLD, 11f));
            String v = value != null ? value.toString() : "";
            lbl.setText("[ " + v + " ]");
            if (!isSelected) {
                boolean urgent = "P1".equals(v);
                lbl.setBackground(urgent ? ThemeManager.fg() : ThemeManager.bg());
                lbl.setForeground(urgent ? ThemeManager.bg() : ThemeManager.fg());
            }
            return lbl;
        }
    }
}
