package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.Order;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * B2B Wholesale Customer management view with order history drill-down.
 */
public final class CustomersView extends JPanel implements NavigableView {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;

    private final JButton addBtn = new JButton();
    private final JButton editBtn = new JButton();
    private final JButton refreshBtn = new JButton();

    private final DefaultTableModel customerModel = nonEditableModel();
    private final JTable customerTable = new JTable(customerModel);

    private final JLabel historyTitleLabel = new JLabel();
    private final DefaultTableModel orderHistoryModel = nonEditableModel();
    private final JTable orderHistoryTable = new JTable(orderHistoryModel);

    private List<Customer> customers = List.of();

    public CustomersView(AppContext ctx) {
        this.ctx = ctx;

        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        buildUi();
        wireEvents();
        applyTranslations();
        applyBrutalistTheme();
        refreshData();
    }

    private static DefaultTableModel nonEditableModel() {
        return new DefaultTableModel() {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private void buildUi() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        toolbar.add(addBtn);
        toolbar.add(editBtn);
        toolbar.add(refreshBtn);
        add(toolbar, BorderLayout.NORTH);

        BrutalistStyle.styleTable(customerTable);
        customerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JPanel historyPanel = new JPanel(new BorderLayout(0, 6));
        historyTitleLabel.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        historyPanel.add(historyTitleLabel, BorderLayout.NORTH);

        BrutalistStyle.styleTable(orderHistoryTable);
        historyPanel.add(new JScrollPane(orderHistoryTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(customerTable), historyPanel);
        split.setResizeWeight(0.58);
        split.setDividerLocation(320);
        add(split, BorderLayout.CENTER);
    }

    private void wireEvents() {
        addBtn.addActionListener(e -> openCustomerDialog(null));
        editBtn.addActionListener(e -> {
            Customer c = getSelectedCustomer();
            if (c != null) openCustomerDialog(c);
        });
        refreshBtn.addActionListener(e -> refreshData());
        customerTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadOrdersForSelectedCustomer();
            }
        });
    }

    private Customer getSelectedCustomer() {
        int r = customerTable.getSelectedRow();
        return (r >= 0 && r < customers.size()) ? customers.get(r) : null;
    }

    private void openCustomerDialog(Customer existing) {
        I18nManager i18n = ctx.getI18n();
        JTextField nameF = new JTextField(existing != null ? existing.name() : "", 22);
        JTextField contactF = new JTextField(existing != null ? safe(existing.contactName()) : "", 22);
        JTextField emailF = new JTextField(existing != null ? safe(existing.email()) : "", 22);
        JTextField phoneF = new JTextField(existing != null ? safe(existing.phone()) : "", 22);
        JTextField addrF = new JTextField(existing != null ? safe(existing.address()) : "", 22);
        JTextField taxF = new JTextField(existing != null ? safe(existing.taxNumber()) : "", 22);
        JCheckBox activeBox = new JCheckBox(i18n.t("common.activate"), existing == null || existing.active());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        addRow(form, gbc, r++, i18n.t("customers.company") + " *", nameF);
        addRow(form, gbc, r++, i18n.t("customers.contact"), contactF);
        addRow(form, gbc, r++, i18n.t("customers.email"), emailF);
        addRow(form, gbc, r++, i18n.t("customers.phone"), phoneF);
        addRow(form, gbc, r++, i18n.t("customers.address"), addrF);
        addRow(form, gbc, r++, i18n.t("customers.tax_number"), taxF);
        if (existing != null) {
            gbc.gridx = 1; gbc.gridy = r; form.add(activeBox, gbc);
        }

        String title = i18n.t(existing == null ? "customers.add_title" : "customers.edit_title");
        if (JOptionPane.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            String company = nameF.getText().trim();
            if (company.isEmpty()) {
                throw new IllegalArgumentException("Company name is required.");
            }
            if (existing == null) {
                ctx.getCustomerDao().insert(company, contactF.getText().trim(), emailF.getText().trim(),
                        phoneF.getText().trim(), addrF.getText().trim(), taxF.getText().trim());
            } else {
                ctx.getCustomerDao().update(existing.id(), company, contactF.getText().trim(), emailF.getText().trim(),
                        phoneF.getText().trim(), addrF.getText().trim(), taxF.getText().trim(), activeBox.isSelected());
            }
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void addRow(JPanel form, GridBagConstraints gbc, int row, String label, JTextField field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel(label), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(field, gbc);
    }

    private void loadOrdersForSelectedCustomer() {
        orderHistoryModel.setRowCount(0);
        Customer sel = getSelectedCustomer();
        if (sel == null) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        for (Order o : ctx.getOrderDao().findAll()) {
            if (o.customerId() == sel.id()) {
                orderHistoryModel.addRow(new Object[]{
                        o.orderNumber(),
                        i18n.t("status.order." + o.status().name()).toUpperCase(),
                        o.totalWeightKg().stripTrailingZeros().toPlainString() + " kg",
                        o.totalPrice().setScale(2, RoundingMode.HALF_UP).toPlainString() + " EUR",
                        o.createdAt() != null ? DT_FMT.format(o.createdAt()) : ""
                });
            }
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @Override
    public void applyTheme() {
        applyBrutalistTheme();
    }

    public void applyBrutalistTheme() {
        BrutalistStyle.stylePrimaryButton(addBtn);
        BrutalistStyle.styleTable(customerTable);
        BrutalistStyle.styleTable(orderHistoryTable);
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_CUSTOMERS;
    }

    @Override
    public String getTitleI18nKey() {
        return "customers.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        I18nManager i18n = ctx.getI18n();
        customers = ctx.getCustomerDao().findAll(false);
        customerModel.setRowCount(0);
        for (Customer c : customers) {
            customerModel.addRow(new Object[]{
                    c.name(),
                    safe(c.contactName()),
                    safe(c.email()),
                    safe(c.phone()),
                    safe(c.address()),
                    safe(c.taxNumber()),
                    c.active() ? i18n.t("common.yes").toUpperCase() : i18n.t("common.no").toUpperCase()
            });
        }
        if (!customers.isEmpty() && customerTable.getSelectedRow() < 0) {
            customerTable.setRowSelectionInterval(0, 0);
        }
        loadOrdersForSelectedCustomer();
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        addBtn.setText("+ " + i18n.t("common.add").toUpperCase());
        editBtn.setText(i18n.t("common.edit").toUpperCase());
        refreshBtn.setText(i18n.t("common.refresh").toUpperCase());
        historyTitleLabel.setText(i18n.t("customers.order_history").toUpperCase());

        customerModel.setColumnIdentifiers(new Object[]{
                i18n.t("customers.company").toUpperCase(),
                i18n.t("customers.contact").toUpperCase(),
                i18n.t("customers.email").toUpperCase(),
                i18n.t("customers.phone").toUpperCase(),
                i18n.t("customers.address").toUpperCase(),
                i18n.t("customers.tax_number").toUpperCase(),
                i18n.t("common.status").toUpperCase()
        });
        orderHistoryModel.setColumnIdentifiers(new Object[]{
                i18n.t("orders.order_number").toUpperCase(),
                i18n.t("common.status").toUpperCase(),
                i18n.t("orders.total_weight").toUpperCase(),
                i18n.t("orders.total_price").toUpperCase(),
                i18n.t("common.created_at").toUpperCase()
        });

        applyBrutalistTheme();
        refreshData();
    }
}
