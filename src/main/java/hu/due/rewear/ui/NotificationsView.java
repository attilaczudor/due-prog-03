package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppNotification;
import hu.due.rewear.model.User;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Dedicated Operational Notifications inbox displaying targeted warehouse alerts
 * (Putaway Pickup, Order Collection, Customer Arrived, Order Ready-to-Ship) with 1-click navigation.
 */
public final class NotificationsView extends JPanel implements NavigableView {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;
    private final Consumer<String> navigator;

    private final JLabel summaryLabel = new JLabel();
    private final JButton openTargetBtn = new JButton();
    private final JButton markReadBtn = new JButton();
    private final JButton markAllReadBtn = new JButton();

    private final DefaultTableModel notifModel = BrutalistStyle.nonEditableTableModel();
    private final JTable notifTable = new JTable(notifModel);

    private List<AppNotification> notifications = List.of();

    public NotificationsView(AppContext ctx, Consumer<String> navigator) {
        this.ctx = ctx;
        this.navigator = navigator;
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        buildUi();
        wireEvents();
        applyTranslations();
        applyTheme();
    }

    private void buildUi() {
        JPanel topBar = new JPanel(new BorderLayout(10, 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        actions.add(openTargetBtn);
        actions.add(markReadBtn);
        actions.add(markAllReadBtn);
        topBar.add(actions, BorderLayout.WEST);

        summaryLabel.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        topBar.add(summaryLabel, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        BrutalistStyle.styleTable(notifTable);
        notifTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        add(new JScrollPane(notifTable), BorderLayout.CENTER);
    }

    private void wireEvents() {
        openTargetBtn.addActionListener(e -> handleOpenTarget());
        notifTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    handleOpenTarget();
                }
            }
        });
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

    private void handleOpenTarget() {
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

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_NOTIFICATIONS;
    }

    @Override
    public String getTitleI18nKey() {
        return "notifications.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        User me = ctx.getSession().requireCurrentUser();
        notifications = ctx.getNotificationService().listForUser(me.id(), 150);
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
            var cm = notifTable.getColumnModel();
            cm.getColumn(0).setPreferredWidth(44);
            cm.getColumn(0).setMaxWidth(52);
            cm.getColumn(0).setCellRenderer(new ReadDotRenderer());
            cm.getColumn(1).setPreferredWidth(130);
            cm.getColumn(2).setPreferredWidth(220);
            cm.getColumn(3).setPreferredWidth(400);
            cm.getColumn(4).setPreferredWidth(135);
        }
        summaryLabel.setText(ctx.getI18n().t("todo.kpi.notifications").toUpperCase(Locale.ROOT) + ": " + unread);
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        openTargetBtn.setText(i18n.t("todo.btn.open_target").toUpperCase(Locale.ROOT));
        markReadBtn.setText(i18n.t("todo.notif.mark_read").toUpperCase(Locale.ROOT));
        markAllReadBtn.setText(i18n.t("todo.notif.mark_all").toUpperCase(Locale.ROOT));
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
        BrutalistStyle.stylePrimaryButton(openTargetBtn);
        BrutalistStyle.styleSecondaryButtons(markReadBtn, markAllReadBtn);
        BrutalistStyle.styleTable(notifTable);
    }

    private final class ReadDotRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int col) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            lbl.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
            return lbl;
        }
    }
}
