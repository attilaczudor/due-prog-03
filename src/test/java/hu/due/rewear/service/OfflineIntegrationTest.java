package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.BankLedgerDao.Account;
import hu.due.rewear.dao.BankLedgerDao.Transaction;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.NavSettings;
import hu.due.rewear.model.NavSettings.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OfflineIntegrationTest {
    @TempDir Path temp;

    private AppContext context() {
        return new AppContext(new DatabaseManager(temp.resolve("integration.db")),
                new AppPreferences(temp.resolve("prefs.properties")), new I18nManager());
    }

    @Test
    void accountsKeepExactBalancesAndHistoriesInTheirOwnCurrency() {
        var ctx = context();
        var ledger = ctx.getBankLedgerDao();
        var demo = ledger.accounts();
        assertEquals(Set.of("HUF", "EUR", "USD"), demo.stream().map(row -> row.account().currency()).collect(Collectors.toSet()));
        assertTrue(demo.stream().allMatch(row -> row.account().demo() && ledger.transactions(row.account().id()).size() == 2));
        ctx.getFinanceConfigDao().insertCurrency("GBP", "British Pound", "Angol font", "£", 2);
        var date = LocalDate.of(2026, 11, 24);
        long primary = ledger.saveAccount(new Account(0, "Offline Bank", "Sales", "GBP-01", "",
                "GBP", date, new BigDecimal("0.10"), false));
        long secondary = ledger.saveAccount(new Account(0, "Offline Bank", "Reserve", "GBP-02", "",
                "GBP", date, new BigDecimal("300.00"), false));
        ledger.addTransaction(primary, new Transaction(date, new BigDecimal("0.20"), "Buyer", "Deposit"));
        ledger.addTransaction(primary, new Transaction(date.plusDays(1), new BigDecimal("-0.01"), "Bank", "Fee"));
        assertThrows(IllegalArgumentException.class, () -> ledger.addTransaction(primary,
                new Transaction(date, new BigDecimal("0.001"), "Buyer", "Too precise")));
        assertThrows(IllegalArgumentException.class, () -> ledger.saveAccount(new Account(primary, "Offline Bank",
                "Sales", "GBP-01", "", "USD", date, BigDecimal.ZERO, false)));
        assertTrue(ctx.getFinanceConfigDao().isCurrencyReferenced("GBP"));
        var reloaded = context().getBankLedgerDao();
        assertEquals(5, reloaded.accounts().size());
        assertEquals(new BigDecimal("0.29"), reloaded.accounts().stream().filter(row -> row.account().id() == primary)
                .findFirst().orElseThrow().balance());
        assertTrue(reloaded.transactions(secondary).isEmpty());
        assertEquals(List.of("Fee", "Deposit"), reloaded.transactions(primary).stream().map(Transaction::reference).toList());
    }

    @Test
    void navSetupSavesTheIdentityAndNeverTheSecrets() {
        var ctx = context();
        var settings = new NavSettings(Map.of(Field.TAX_NUMBER, "87654321", Field.LOGIN, "tech-user-1"));
        ctx.getSettingsDao().saveNavSettings(settings);
        var reloaded = context().getSettingsDao().getNavSettings();
        assertEquals("87654321", reloaded.value(Field.TAX_NUMBER));
        assertEquals("tech-user-1", reloaded.value(Field.LOGIN));
        assertEquals("TEST", reloaded.value(Field.ENVIRONMENT), "A fresh setup points at the test environment");
        assertFalse(reloaded.enabled(), "Reporting must start switched off");
        // The secrets have no key in the database at all, under any name.
        assertTrue(ctx.getSettingsDao().get("nav.password").isEmpty());
        assertTrue(ctx.getSettingsDao().get("nav.signing_key").isEmpty());
        assertTrue(ctx.getSettingsDao().get("nav.exchange_key").isEmpty());
    }

}
