package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.dao.BankLedgerDao.Account;
import hu.due.rewear.dao.BankLedgerDao.AccountBalance;
import hu.due.rewear.dao.BankLedgerDao.Transaction;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.CurrencyDef;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BankIntegrationPanel extends JPanel {
    private final AppContext ctx;
    private final DefaultTableModel accountsModel = BrutalistStyle.nonEditableTableModel();
    private final DefaultTableModel transactionsModel = BrutalistStyle.nonEditableTableModel();
    private final JTable accountsTable = new LedgerTable(accountsModel);
    private final JTable transactionsTable = new LedgerTable(transactionsModel);
    private final JButton addAccount = new JButton();
    private final JButton editAccount = new JButton();
    private final JButton addTransaction = new JButton();
    private final JLabel accountsTitle = new JLabel();
    private final JLabel transactionsTitle = new JLabel();
    private final JTextArea totals = new JTextArea();
    private final JPanel accountsCard;
    private final JPanel transactionsCard;
    private List<AccountBalance> accounts = List.of();
    private Map<String, Integer> decimals = Map.of();
    private boolean loading;

    private static final class LedgerTable extends JTable {
        LedgerTable(DefaultTableModel model) { super(model); }

        @Override public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            int column = columnAtPoint(event.getPoint());
            return row < 0 || column < 0 ? null : String.valueOf(getValueAt(row, column));
        }
    }

    BankIntegrationPanel(AppContext ctx) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        totals.setEditable(false);
        totals.setRows(2);
        totals.setLineWrap(true);
        totals.setWrapStyleWord(true);
        totals.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        accountsCard = tableCard(accountsTitle, accountsTable, addAccount, editAccount);
        accountsCard.add(totals, BorderLayout.SOUTH);
        transactionsCard = tableCard(transactionsTitle, transactionsTable, addTransaction);
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, accountsCard, transactionsCard);
        split.setResizeWeight(0.47);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        accountsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        accountsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!loading && !e.getValueIsAdjusting()) refreshTransactions();
        });
        addAccount.addActionListener(e -> edit(null));
        editAccount.addActionListener(e -> { if (selected() != null) edit(selected()); });
        addTransaction.addActionListener(e -> { if (selected() != null) addMovement(selected()); });
    }

    private static JPanel tableCard(JLabel title, JTable table, JButton... actions) {
        var card = new JPanel(new BorderLayout(0, 8));
        var header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.add(title, BorderLayout.WEST);
        var toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        toolbar.setOpaque(false);
        for (JButton button : actions) toolbar.add(button);
        header.add(toolbar, BorderLayout.EAST);
        card.add(header, BorderLayout.NORTH);
        card.add(new JScrollPane(table), BorderLayout.CENTER);
        return card;
    }

    void applyTranslations() {
        accountsTitle.setText(t("bank.accounts"));
        addAccount.setText(t("bank.add_account"));
        editAccount.setText(t("common.edit"));
        addTransaction.setText(t("bank.add_transaction"));
        accountsModel.setColumnIdentifiers(new String[]{t("bank.institution"), t("bank.account"),
                t("bank.account_reference"), t("currency.label"), t("bank.balance"), t("bank.source")});
        transactionsModel.setColumnIdentifiers(new String[]{t("common.date"), t("bank.counterparty"),
                t("bank.transaction_reference"), t("bank.amount")});
        var columns = accountsTable.getColumnModel();
        int[] preferred = {105, 105, 135, 58, 150, 68};
        for (int column = 0; column < preferred.length; column++) {
            columns.getColumn(column).setPreferredWidth(preferred[column]);
        }
        columns.getColumn(4).setMinWidth(112);
    }

    void applyTheme() {
        BrutalistStyle.applyBoxCard(accountsCard, 10, 12);
        BrutalistStyle.applyBoxCard(transactionsCard, 10, 12);
        BrutalistStyle.stylePrimaryButton(addAccount);
        BrutalistStyle.styleSecondaryButtons(editAccount, addTransaction);
        BrutalistStyle.styleTable(accountsTable);
        BrutalistStyle.styleTable(transactionsTable);
        totals.setForeground(ThemeManager.fg());
        totals.setBackground(ThemeManager.bg());
    }

    void refreshData() {
        Account current = selected();
        refreshData(current == null ? 0 : current.id());
    }

    private void refreshData(long preferredId) {
        loading = true;
        try {
            accounts = ctx.getBankLedgerDao().accounts();
            decimals = ctx.getFinanceConfigDao().decimalPlacesMap();
            accountsModel.setRowCount(0);
            var manual = new LinkedHashMap<String, BigDecimal>();
            var demo = new LinkedHashMap<String, BigDecimal>();
            int select = 0;
            for (AccountBalance row : accounts) {
                Account account = row.account();
                if (account.id() == preferredId) select = accountsModel.getRowCount();
                accountsModel.addRow(new Object[]{account.bank(), account.name(), account.reference(), account.currency(),
                        money(row.balance(), account.currency(), false), t(account.demo() ? "bank.demo" : "bank.manual")});
                (account.demo() ? demo : manual).merge(account.currency(), row.balance(), BigDecimal::add);
            }
            if (!accounts.isEmpty()) TableSorting.selectModelRow(accountsTable, select);
            totals.setText(t("bank.totals", t("bank.manual"), formatTotals(manual)) + "\n"
                    + t("bank.totals", t("bank.demo"), formatTotals(demo)));
        } finally {
            loading = false;
        }
        refreshTransactions();
    }

    private String formatTotals(Map<String, BigDecimal> balances) {
        return balances.isEmpty() ? "—" : String.join("   |   ", balances.entrySet().stream()
                .map(entry -> money(entry.getValue(), entry.getKey(), false)).toList());
    }

    private void refreshTransactions() {
        Account account = selected();
        transactionsModel.setRowCount(0);
        transactionsTitle.setText(account == null ? t("bank.transactions")
                : t("bank.transactions_for", account.name(), account.currency()));
        editAccount.setEnabled(account != null);
        addTransaction.setEnabled(account != null);
        if (account == null) return;
        for (Transaction tx : ctx.getBankLedgerDao().transactions(account.id())) {
            transactionsModel.addRow(new Object[]{tx.date(), tx.counterparty(), tx.reference(), money(tx.amount(), account.currency(), true)});
        }
    }

    private Account selected() {
        int row = TableSorting.selectedModelRow(accountsTable);
        return row >= 0 && row < accounts.size() ? accounts.get(row).account() : null;
    }

    private void edit(Account account) {
        var form = new JPanel(new GridLayout(0, 2, 8, 8));
        var institution = field(form, "bank.institution", account == null ? "" : account.bank());
        var name = field(form, "bank.account", account == null ? "" : account.name());
        var reference = field(form, "bank.account_reference", account == null ? "" : account.reference());
        var bic = field(form, "bank.bic", account == null ? "" : account.bic());
        var currencies = new JComboBox<>(ctx.getFinanceConfigDao().findCurrencies(true).stream()
                .map(CurrencyDef::code).toArray(String[]::new));
        if (account != null) {
            currencies.setSelectedItem(account.currency());
            if (!account.currency().equals(currencies.getSelectedItem())) {
                currencies.addItem(account.currency());
                currencies.setSelectedItem(account.currency());
            }
            currencies.setEnabled(ctx.getBankLedgerDao().transactions(account.id()).isEmpty());
        }
        form.add(new JLabel(t("currency.label")));
        form.add(currencies);
        var date = field(form, "bank.opening_date", (account == null ? LocalDate.now() : account.openingDate()).toString());
        var opening = field(form, "bank.opening_balance", account == null ? "0" : account.openingBalance().toPlainString());
        String title = t(account == null ? "bank.add_account" : "bank.edit_account");
        while (confirm(form, title)) {
            try {
                if (currencies.getSelectedItem() == null) throw new IllegalArgumentException(t("bank.currency_required"));
                long id = ctx.getBankLedgerDao().saveAccount(new Account(account == null ? 0 : account.id(), institution.getText(),
                        name.getText(), reference.getText(), bic.getText(), currencies.getSelectedItem().toString(),
                        parseDate(date), CurrencyCode.parseAmount(opening.getText()), account != null && account.demo()));
                refreshData(id);
                return;
            } catch (RuntimeException ex) {
                showError(ex);
            }
        }
    }

    private void addMovement(Account account) {
        var form = new JPanel(new GridLayout(0, 2, 8, 8));
        var date = field(form, "bank.booking_date", LocalDate.now().toString());
        var amount = field(form, "bank.signed_amount", "");
        var counterparty = field(form, "bank.counterparty", "");
        var reference = field(form, "bank.transaction_reference", "");
        while (confirm(form, t("bank.add_transaction") + " · " + account.currency())) {
            try {
                ctx.getBankLedgerDao().addTransaction(account.id(), new Transaction(parseDate(date),
                        CurrencyCode.parseAmount(amount.getText()), counterparty.getText(), reference.getText()));
                refreshData(account.id());
                return;
            } catch (RuntimeException ex) {
                showError(ex);
            }
        }
    }

    private boolean confirm(Component form, String title) {
        Object[] buttons = {t("common.save"), t("common.cancel")};
        return JOptionPane.showOptionDialog(this, form, title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, buttons, buttons[0]) == 0;
    }

    private JTextField field(JPanel form, String key, String value) {
        var entry = new JTextField(value, 22);
        var label = new JLabel(t(key));
        label.setLabelFor(entry);
        form.add(label);
        form.add(entry);
        return entry;
    }

    private LocalDate parseDate(JTextField date) {
        try {
            return LocalDate.parse(date.getText().strip());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(t("bank.date_invalid"));
        }
    }

    private String money(BigDecimal amount, String currency, boolean signed) {
        int scale = Math.max(decimals.getOrDefault(currency, CurrencyDef.defaultDecimalsFor(currency)), amount.stripTrailingZeros().scale());
        return signed ? CurrencyDef.formatSignedAmount(amount, currency, scale) : CurrencyDef.formatAmount(amount, currency, scale);
    }

    private String t(String key, Object... args) { return ctx.getI18n().t(key, args); }

    private void showError(RuntimeException failure) {
        JOptionPane.showMessageDialog(this, failure.getMessage(), t("common.error"), JOptionPane.ERROR_MESSAGE);
    }
}
