package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppNotification;
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
import javax.swing.JTabbedPane;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Unified To-Do &amp; Notifications workspace available to all roles:
 * <ul>
 *     <li>Top summary KPI cards (System Tasks, Personal To-Dos, Unread Notifications).</li>
 *     <li><b>System To-Do List</b> (read-only, logically ordered P1..P4 for the signed-in user's role).</li>
 *     <li><b>Personal To-Do List</b> (separate user-managed checklist persisted in SQLite).</li>
 *     <li><b>Notifications Inbox</b> (targeted operational alerts with 1-click navigation).</li>
 * </ul>
 */
public final class TodoView extends JPanel implements NavigableView {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;
    private final Consumer<String> navigator;

    // Summary KPI cards
    private final JPanel kpiSysCard = new JPanel(new BorderLayout(0, 4));
    private final JLabel kpiSysTitle = new JLabel();
    private final JLabel kpiSysValue = new JLabel("0");

    private final JPanel kpiPersCard = new JPanel(new BorderLayout(0, 4));
    private final JLabel kpiPersTitle = new JLabel();
    private final JLabel kpiPersValue = new JLabel("0");

    private final JPanel kpiNotifCard = new JPanel(new BorderLayout(0, 4));
    private final JLabel kpiNotifTitle = new JLabel();
    private final JLabel kpiNotifValue = new JLabel("0");

    private final JTabbedPane tabs = new JTabbedPane();

    // Tab 1: Split System To-Dos (top) + Personal To-Dos (bottom)
    private final JLabel sysSectionTitle = new JLabel();
    private final JButton openSysTargetBtn = new JButton();
    private final JButton refreshBtn = new JButton();
    private final DefaultTableModel sysModel = BrutalistStyle.nonEditableTableModel();
    private final JTable sysTable = new JTable(sysModel);

    private final JLabel persSectionTitle = new JLabel();
    private final JButton addPersBtn = new JButton();
    private final JButton togglePersBtn = new JButton();
    private final JButton deletePersBtn = new JButton();
    private final DefaultTableModel persModel = BrutalistStyle.nonEditableTableModel();
    private final JTable persTable = new JTable(persModel);

    // Tab 2: Notifications Feed
    private final JButton openNotifTargetBtn = new JButton();
    private final JButton markReadBtn = new JButton();
    private final JButton markAllReadBtn = new JButton();
    private final DefaultTableModel notifModel = BrutalistStyle.nonEditableTableModel();
    private final JTable notifTable = new JTable(notifModel);

    private List<SystemTodoItem> systemItems = List.of();
    private List<PersonalTodo> personalItems = List.of();
    private List<AppNotification> notifications = List.of();

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
        // Top Summary KPI strip
        JPanel kpiStrip = new JPanel(new GridLayout(1, 3, 12, 0));
        configureKpiCard(kpiSysCard, kpiSysTitle, kpiSysValue);
        configureKpiCard(kpiPersCard, kpiPersTitle, kpiPersValue);
        configureKpiCard(kpiNotifCard, kpiNotifTitle, kpiNotifValue);
        kpiStrip.add(kpiSysCard);
        kpiStrip.add(kpiPersCard);
        kpiStrip.add(kpiNotifCard);
        add(kpiStrip, BorderLayout.NORTH);

        // Tab 1: System To-Dos (top) + Personal To-Dos (bottom)
        JPanel sysPanel = new JPanel(new BorderLayout(0, 6));
        sysPanel.setBorder(BorderFactory.createEmptyBorder(8, 2, 4, 2));
        JPanel sysHeader = new JPanel(new BorderLayout(8, 0));
        sysSectionTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        sysHeader.add(sysSectionTitle, BorderLayout.WEST);
        JPanel sysBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        sysBtns.add(openSysTargetBtn);
        sysBtns.add(refreshBtn);
        sysHeader.add(sysBtns, BorderLayout.EAST);
        sysPanel.add(sysHeader, BorderLayout.NORTH);

        BrutalistStyle.styleTable(sysTable);
        sysTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sysPanel.add(new JScrollPane(sysTable), BorderLayout.CENTER);

        JPanel persPanel = new JPanel(new BorderLayout(0, 6));
        persPanel.setBorder(BorderFactory.createEmptyBorder(8, 2, 2, 2));
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
        todoSplit.setDividerLocation(260);

        // Tab 2: Notifications
        JPanel notifPanel = new JPanel(new BorderLayout(0, 8));
        notifPanel.setBorder(BorderFactory.createEmptyBorder(8, 2, 2, 2));
        JPanel notifBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        notifBar.add(openNotifTargetBtn);
        notifBar.add(markReadBtn);
        notifBar.add(markAllReadBtn);
        notifPanel.add(notifBar, BorderLayout.NORTH);

        BrutalistStyle.styleTable(notifTable);
        notifTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        notifPanel.add(new JScrollPane(notifTable), BorderLayout.CENTER);

        tabs.addTab("To-Do", todoSplit);
        tabs.addTab("Notifications", notifPanel);
        add(tabs, BorderLayout.CENTER);
    }

    private static void configureKpiCard(JPanel card, JLabel title, JLabel value) {
        title.setFont(ThemeManager.monoFont(Font.BOLD, 11f));
        value.setFont(ThemeManager.monoFont(Font.BOLD, 22f));
        card.add(title, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
    }

    private void wireEvents() {
        refreshBtn.addActionListener(e -> refreshData());
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

        openNotifTargetBtn.addActionListener(e -> handleOpenNotificationTarget());
        markReadBtn.addActionListener(e -> {
            int row = notifTable.getSelectedRow();
            if (row >= 0 && row < notifications.size()) {
                ctx.getNotificationService().markRead(notifications.get(row).id());
                refreshData();
            }
        });
        markAllReadBtn.addActionListener(e -> {
            ctx.getNotificationService().markAllRead(ctx.getSession().requireCurrentUser().id());
            refreshData();
        });
    }

    public void showNotificationsTab() {
        tabs.setSelectedIndex(1);
    }

    private void openSelectedSystemTarget() {
        int row = sysTable.getSelectedRow();
        if (row >= 0 && row < systemItems.size() && navigator != null) {
            String target = systemItems.get(row).targetView();
            if (target != null && !target.isBlank()) {
                navigator.accept(target);
            }
        }
    }

    private void handleOpenNotificationTarget() {
        int row = notifTable.getSelectedRow();
        if (row >= 0 && row < notifications.size()) {
            AppNotification n = notifications.get(row);
            ctx.getNotificationService().markRead(n.id());
            if (n.targetView() != null && !n.targetView().isBlank() && navigator != null) {
                navigator.accept(n.targetView());
            } else {
                refreshData();
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
        int row = persTable.getSelectedRow();
        if (row >= 0 && row < personalItems.size()) {
            PersonalTodo item = personalItems.get(row);
            ctx.getTodoService().togglePersonalTodo(item.id(), !item.completed());
            refreshData();
        }
    }

    private void handleDeletePersonalTodo() {
        int row = persTable.getSelectedRow();
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

        notifications = ctx.getNotificationService().listForUser(me.id(), 100);
        notifModel.setRowCount(0);
        int unread = 0;
        for (AppNotification n : notifications) {
            if (!n.read()) unread++;
            notifModel.addRow(new Object[]{
                    n.read() ? "✓" : "●",
                    n.category(),
                    n.title(),
                    n.message(),
                    n.createdAt() != null ? DT_FMT.format(n.createdAt()) : ""
            });
        }
        if (notifTable.getColumnCount() >= 5) {
            notifTable.getColumnModel().getColumn(0).setPreferredWidth(44);
            notifTable.getColumnModel().getColumn(0).setMaxWidth(52);
            notifTable.getColumnModel().getColumn(1).setPreferredWidth(120);
            notifTable.getColumnModel().getColumn(2).setPreferredWidth(200);
            notifTable.getColumnModel().getColumn(3).setPreferredWidth(380);
            notifTable.getColumnModel().getColumn(4).setPreferredWidth(135);
        }

        kpiSysValue.setText(String.valueOf(systemItems.size()));
        kpiPersValue.setText(openPersonal + " / " + personalItems.size());
        kpiNotifValue.setText(String.valueOf(unread));
        tabs.setTitleAt(1, i18n.t("todo.tab.notifications").toUpperCase(Locale.ROOT)
                + (unread > 0 ? " [" + unread + "]" : ""));
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        kpiSysTitle.setText(i18n.t("todo.kpi.system").toUpperCase(Locale.ROOT));
        kpiPersTitle.setText(i18n.t("todo.kpi.personal").toUpperCase(Locale.ROOT));
        kpiNotifTitle.setText(i18n.t("todo.kpi.notifications").toUpperCase(Locale.ROOT));

        tabs.setTitleAt(0, i18n.t("todo.tab.lists").toUpperCase(Locale.ROOT));
        tabs.setTitleAt(1, i18n.t("todo.tab.notifications").toUpperCase(Locale.ROOT));

        sysSectionTitle.setText(i18n.t("todo.system.header").toUpperCase(Locale.ROOT));
        openSysTargetBtn.setText(i18n.t("todo.btn.open_target").toUpperCase(Locale.ROOT));
        refreshBtn.setText(i18n.t("common.refresh").toUpperCase(Locale.ROOT));

        persSectionTitle.setText(i18n.t("todo.personal.header").toUpperCase(Locale.ROOT));
        addPersBtn.setText("+ " + i18n.t("todo.personal.add").toUpperCase(Locale.ROOT));
        togglePersBtn.setText(i18n.t("todo.personal.toggle").toUpperCase(Locale.ROOT));
        deletePersBtn.setText(i18n.t("common.delete").toUpperCase(Locale.ROOT));

        openNotifTargetBtn.setText(i18n.t("todo.btn.open_target").toUpperCase(Locale.ROOT));
        markReadBtn.setText(i18n.t("todo.notif.mark_read").toUpperCase(Locale.ROOT));
        markAllReadBtn.setText(i18n.t("todo.notif.mark_all").toUpperCase(Locale.ROOT));

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
        notifModel.setColumnIdentifiers(new Object[]{
                "●",
                i18n.t("invoice.category").toUpperCase(Locale.ROOT),
                i18n.t("email.subject").toUpperCase(Locale.ROOT),
                i18n.t("todo.col.details").toUpperCase(Locale.ROOT),
                i18n.t("common.date").toUpperCase(Locale.ROOT)
        });
        applyTheme();
    }

    @Override
    public void applyTheme() {
        BrutalistStyle.applyBoxCard(kpiSysCard, 10, 12);
        BrutalistStyle.applyBoxCard(kpiPersCard, 10, 12);
        BrutalistStyle.applyBoxCard(kpiNotifCard, 10, 12);
        BrutalistStyle.stylePrimaryButton(addPersBtn);
        BrutalistStyle.stylePrimaryButton(openSysTargetBtn);
        BrutalistStyle.styleSecondaryButtons(refreshBtn, togglePersBtn, deletePersBtn,
                openNotifTargetBtn, markReadBtn, markAllReadBtn);
        BrutalistStyle.styleTable(sysTable);
        BrutalistStyle.styleTable(persTable);
        BrutalistStyle.styleTable(notifTable);
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
