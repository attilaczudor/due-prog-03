package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.util.BusinessClock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** An offline bank ledger with exact decimal balances in the account's own currency. */
public final class BankLedgerDao {
    public record Account(long id, String bank, String name, String reference, String bic, String currency,
                          LocalDate openingDate, BigDecimal openingBalance, boolean demo) { }
    public record AccountBalance(Account account, BigDecimal balance) { }
    public record Transaction(LocalDate date, BigDecimal amount, String counterparty, String reference) { }

    private final DatabaseManager db;

    public BankLedgerDao(DatabaseManager db) {
        this.db = db;
    }

    public List<AccountBalance> accounts() {
        String sql = """
                SELECT a.*, t.amount AS movement FROM bank_accounts a
                LEFT JOIN bank_transactions t ON a.id = t.account_id
                ORDER BY a.demo DESC, a.currency, a.bank_name, a.id
                """;
        var accounts = new LinkedHashMap<Long, AccountBalance>();
        try (var conn = db.getConnection(); var query = conn.prepareStatement(sql); var rows = query.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                AccountBalance prior = accounts.get(id);
                if (prior == null) {
                    var account = readAccount(rows);
                    prior = new AccountBalance(account, account.openingBalance());
                }
                String movement = rows.getString("movement");
                accounts.put(id, movement == null ? prior
                        : new AccountBalance(prior.account(), prior.balance().add(new BigDecimal(movement))));
            }
            return List.copyOf(accounts.values());
        } catch (SQLException ex) {
            throw new DaoException("Could not load bank accounts", ex);
        }
    }

    public List<Transaction> transactions(long accountId) {
        String sql = """
                SELECT booking_date, amount, counterparty, reference FROM bank_transactions
                WHERE account_id = ? ORDER BY booking_date DESC, id DESC
                """;
        try (var conn = db.getConnection(); var query = conn.prepareStatement(sql)) {
            query.setLong(1, accountId);
            try (var rows = query.executeQuery()) {
                var result = new ArrayList<Transaction>();
                while (rows.next()) result.add(new Transaction(LocalDate.parse(rows.getString(1)),
                        new BigDecimal(rows.getString(2)), rows.getString(3), rows.getString(4)));
                return List.copyOf(result);
            }
        } catch (SQLException ex) {
            throw new DaoException("Could not load bank transactions", ex);
        }
    }

    public long saveAccount(Account input) {
        if (input.bank().isBlank() || input.name().isBlank() || input.reference().isBlank()) {
            throw new IllegalArgumentException("Bank, account name and account reference are required.");
        }
        return db.executeInTransaction(conn -> {
            if (input.id() == 0) return writeAccount(conn, input);
            try (var query = conn.prepareStatement("""
                    SELECT a.currency, MIN(t.booking_date) AS first_date FROM bank_accounts a
                    LEFT JOIN bank_transactions t ON t.account_id = a.id WHERE a.id = ? GROUP BY a.id
                    """)) {
                query.setLong(1, input.id());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Bank account does not exist.");
                    String firstDate = rows.getString("first_date");
                    if (firstDate != null && (!input.currency().equals(rows.getString("currency"))
                            || input.openingDate().isAfter(LocalDate.parse(firstDate)))) {
                        throw new IllegalArgumentException("Keep the account currency and choose an opening date before its first transaction.");
                    }
                }
            }
            return writeAccount(conn, input);
        });
    }

    public void addTransaction(long accountId, Transaction transaction) {
        if (transaction.reference().isBlank() || transaction.amount().signum() == 0) {
            throw new IllegalArgumentException("Enter a transaction description and a nonzero amount.");
        }
        db.runInTransaction(conn -> {
            String currency;
            try (var query = conn.prepareStatement("SELECT currency, opening_date FROM bank_accounts WHERE id = ?")) {
                query.setLong(1, accountId);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Bank account does not exist.");
                    if (transaction.date().isBefore(LocalDate.parse(rows.getString("opening_date")))) {
                        throw new IllegalArgumentException("The booking date must be on or after the opening balance date.");
                    }
                    currency = rows.getString("currency");
                }
            }
            writeTransaction(conn, accountId, transaction, currency);
        });
    }

    public void seedDemoAccounts() {
        db.runInTransaction(conn -> {
            try (var claim = conn.prepareStatement("""
                    INSERT OR IGNORE INTO app_settings(setting_key, setting_value) VALUES ('bank.demo_seeded', '1')
                    """)) {
                if (claim.executeUpdate() == 0) return;
            }
            var currencies = new ArrayList<String>();
            try (var query = conn.prepareStatement("SELECT code FROM currencies WHERE active = 1 ORDER BY code");
                 var rows = query.executeQuery()) {
                while (rows.next()) currencies.add(rows.getString(1));
            }
            LocalDate today = BusinessClock.today();
            for (String currency : currencies) {
                boolean forint = currency.equals("HUF");
                long id = writeAccount(conn, new Account(0, "Demo Bank", "ReWear " + currency, "DEMO-" + currency,
                        "", currency, today.minusDays(10), BigDecimal.valueOf(forint ? 1_250_000 : 2_500), true));
                writeTransaction(conn, id, new Transaction(today.minusDays(4), BigDecimal.valueOf(forint ? 145_000 : 175),
                        "Demo Wholesale", "DEMO-SALE-01"), currency);
                writeTransaction(conn, id, new Transaction(today.minusDays(2), BigDecimal.valueOf(forint ? -38_500 : -25),
                        "Demo Logistics", "DEMO-COST-01"), currency);
            }
        });
    }

    private static Account readAccount(ResultSet row) throws SQLException {
        return new Account(row.getLong("id"), row.getString("bank_name"), row.getString("account_name"),
                row.getString("account_reference"), row.getString("bic"), row.getString("currency"),
                LocalDate.parse(row.getString("opening_date")), new BigDecimal(row.getString("opening_balance")),
                row.getBoolean("demo"));
    }

    private static long writeAccount(Connection conn, Account account) throws SQLException {
        String balance = exactAmount(conn, account.currency(), account.openingBalance());
        boolean insert = account.id() == 0;
        String sql = insert
                ? "INSERT INTO bank_accounts(bank_name, account_name, account_reference, bic, currency, opening_date, opening_balance, demo) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id"
                : "UPDATE bank_accounts SET bank_name=?, account_name=?, account_reference=?, bic=?, currency=?, opening_date=?, opening_balance=? WHERE id=? RETURNING id";
        try (var query = conn.prepareStatement(sql)) {
            query.setString(1, account.bank().strip());
            query.setString(2, account.name().strip());
            query.setString(3, account.reference().strip());
            query.setString(4, account.bic().strip());
            query.setString(5, account.currency());
            query.setString(6, account.openingDate().toString());
            query.setString(7, balance);
            if (insert) query.setBoolean(8, account.demo());
            else query.setLong(8, account.id());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Could not save bank account.");
                return rows.getLong(1);
            }
        }
    }

    private static void writeTransaction(Connection conn, long id, Transaction transaction, String currency) throws SQLException {
        String amount = exactAmount(conn, currency, transaction.amount());
        try (var query = conn.prepareStatement("""
                INSERT INTO bank_transactions(account_id, booking_date, amount, counterparty, reference) VALUES (?, ?, ?, ?, ?)
                """)) {
            query.setLong(1, id);
            query.setString(2, transaction.date().toString());
            query.setString(3, amount);
            query.setString(4, transaction.counterparty().strip());
            query.setString(5, transaction.reference().strip());
            query.executeUpdate();
        }
    }

    private static String exactAmount(Connection conn, String currency, BigDecimal amount) throws SQLException {
        try (var query = conn.prepareStatement("SELECT decimal_places FROM currencies WHERE code = ?")) {
            query.setString(1, currency);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Unknown bank account currency.");
                try {
                    return amount.setScale(rows.getInt(1), RoundingMode.UNNECESSARY).toPlainString();
                } catch (ArithmeticException ex) {
                    throw new IllegalArgumentException("Amount has more decimal places than the account currency accepts.");
                }
            }
        }
    }
}
