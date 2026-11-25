package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.dao.OrderDemandDao.Product;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.service.OrderService.Availability;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.List;

final class OrderDemandDialog extends JDialog {
    private final AppContext ctx;
    private final long orderId;
    private final javax.swing.table.DefaultTableModel model = BrutalistStyle.nonEditableTableModel();
    private final JTable products = new JTable(model);
    private final JButton addLine = new JButton();
    private final JButton remove = new JButton();
    private final JButton allocate = new JButton();
    private List<Availability> rows = List.of();

    OrderDemandDialog(Window owner, AppContext ctx, Order order) {
        super(owner, ctx.getI18n().t("orders.requested_products") + " — " + order.orderNumber(), ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        this.orderId = order.id();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        var header = new JLabel(t("orders.delivery_date") + "   " + order.deliveryDate());
        root.add(header, BorderLayout.NORTH);
        model.setColumnIdentifiers(new String[]{t("sorting.product"), t("orders.requested_products.requested"),
                t("orders.requested_products.reserved"), t("orders.requested_products.available"),
                t("orders.requested_products.shortage")});
        BrutalistStyle.styleTable(products);
        products.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        var scroll = new JScrollPane(products);
        scroll.setPreferredSize(new java.awt.Dimension(700, 300));
        root.add(scroll, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        button(actions, addLine, "orders.requested_products.add", this::addProduct);
        button(actions, remove, "orders.requested_products.remove", () -> {
            int row = TableSorting.selectedModelRow(products);
            if (row >= 0 && row < rows.size()) ctx.getOrderService().removeRequestedProduct(orderId, rows.get(row).line().id());
        });
        button(actions, allocate, "orders.requested_products.allocate",
                () -> ctx.getOrderService().allocateRequestedProducts(orderId, ctx.getSession().requireCurrentUser().id()));
        BrutalistStyle.stylePrimaryButton(allocate);
        button(actions, new JButton(), "common.close", this::dispose);
        root.add(actions, BorderLayout.SOUTH);
        setContentPane(root);
        refresh();
        pack();
        setLocationRelativeTo(owner);
        BrutalistStyle.installEscapeToClose(this);
    }

    private void button(JPanel container, JButton button, String key, Runnable action) {
        button.setText(t(key));
        BrutalistStyle.styleSecondaryButton(button);
        button.addActionListener(event -> {
            try {
                action.run();
                if (isDisplayable()) refresh();
            } catch (RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), t("common.error"), JOptionPane.ERROR_MESSAGE);
                refresh();
            }
        });
        container.add(button);
    }

    private void refresh() {
        Order order = ctx.getOrderDao().findById(orderId).orElseThrow();
        rows = ctx.getOrderService().checkAvailability(orderId);
        model.setRowCount(0);
        for (var row : rows) model.addRow(new Object[]{row.line().description(), row.line().quantity(),
                row.reserved(), row.available(), row.shortage()});
        boolean draft = order.status() == OrderStatus.DRAFT;
        boolean noOrdersItems = ctx.getOrderItemDao().findByOrderId(orderId).isEmpty();
        addLine.setEnabled(draft && noOrdersItems);
        remove.setEnabled(draft && noOrdersItems && !rows.isEmpty());
        allocate.setEnabled(draft && !rows.isEmpty() && rows.stream().noneMatch(row -> row.shortage() > 0)
                && order.assignedForkliftId() != null && order.dispatchLocationId() != null);
        allocate.setToolTipText(t("orders.requested_products.allocate_hint"));
    }

    private void addProduct() {
        String lang = ctx.getI18n().getActiveLanguage();
        var target = choices(ctx.getDictionaryDao().findTargetGroups(true).stream().map(x -> new Choice(x.id(), x.displayLabel(lang))).toList());
        var garment = choices(ctx.getDictionaryDao().findGarmentTypes(true).stream().map(x -> new Choice(x.id(), x.displayLabel(lang))).toList());
        var quality = choices(ctx.getDictionaryDao().findQualityGrades(true).stream().map(x -> new Choice(x.id(), x.displayLabel(lang))).toList());
        var packaging = choices(ctx.getDictionaryDao().findPackageTypes(true).stream().map(x -> new Choice(x.id(), x.displayLabel(lang))).toList());
        var quantity = new JSpinner(new SpinnerNumberModel(1, 1, 10_000, 1));
        var form = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        BrutalistStyle.addFormRow(form, gbc, 0, t("sorting.target_group"), target);
        BrutalistStyle.addFormRow(form, gbc, 1, t("sorting.product"), garment);
        BrutalistStyle.addFormRow(form, gbc, 2, t("sorting.grade"), quality);
        BrutalistStyle.addFormRow(form, gbc, 3, t("sorting.packaging"), packaging);
        BrutalistStyle.addFormRow(form, gbc, 4, t("orders.requested_products.requested"), quantity);
        if (JOptionPane.showConfirmDialog(this, form, t("orders.requested_products.add"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            ctx.getOrderService().setRequestedProduct(orderId, new Product(id(target), id(garment), id(quality), id(packaging)),
                    ((Number) quantity.getValue()).intValue());
        }
    }

    private record Choice(long id, String label) { @Override public String toString() { return label; } }

    private static JComboBox<Choice> choices(List<Choice> items) {
        return new JComboBox<>(items.toArray(Choice[]::new));
    }

    private static long id(JComboBox<Choice> combo) {
        if (combo.getSelectedItem() instanceof Choice item) return item.id();
        throw new IllegalArgumentException("Select an active product classification.");
    }

    private String t(String key) { return ctx.getI18n().t(key); }
}
