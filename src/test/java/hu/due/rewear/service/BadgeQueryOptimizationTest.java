package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.model.PersonalTodo;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the sidebar badge refresh against regressing to full-table scans.
 * <p>
 * The badge refresh runs on a 10 second timer, so the counts behind it must be produced by indexed
 * SQL rather than by loading every invoice, order and personal to-do and filtering in Java. These
 * tests pin the filtered queries to the Java filtering they replaced, so the cheap path and the
 * displayed list can never drift apart.
 */
class BadgeQueryOptimizationTest {

    @TempDir
    Path tempDir;

    private AppContext newContext() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("badge-query-test.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        return new AppContext(db, prefs, i18n);
    }

    @Test
    void overdueInvoiceQueryMatchesFilteringTheFullLedgerInJava() {
        AppContext ctx = newContext();

        // Force every past-due issued invoice into OVERDUE so the subset is non-trivial.
        ctx.getInvoiceDao().markPastDueAsOverdue(BusinessClock.today().plusYears(5));

        List<Invoice> all = ctx.getInvoiceDao().findAll();
        List<Long> expected = all.stream()
                .filter(inv -> inv.status() == InvoiceStatus.OVERDUE)
                .map(Invoice::id)
                .toList();
        List<Long> actual = ctx.getInvoiceDao().findOverdue().stream().map(Invoice::id).toList();

        assertFalse(expected.isEmpty(), "Demo data should contain at least one overdue invoice");
        assertEquals(expected, actual, "findOverdue() must return the same invoices, in the same order");
        assertEquals(expected.size(), ctx.getInvoiceDao().countOverdue(),
                "countOverdue() must agree with findOverdue()");
        assertTrue(actual.size() < all.size(),
                "The filtered query should read fewer rows than the whole ledger");
    }

    @Test
    void openOrderQueryMatchesFilteringTheFullOrderListInJava() {
        AppContext ctx = newContext();

        List<Long> expected = ctx.getOrderDao().findAll().stream()
                .filter(o -> o.status() == OrderStatus.DRAFT || o.status() == OrderStatus.PACKING)
                .map(Order::id)
                .sorted()
                .toList();
        List<Long> actual = ctx.getOrderDao().findOpenOrders().stream()
                .map(Order::id)
                .sorted()
                .toList();

        assertEquals(expected, actual, "findOpenOrders() must match DRAFT/PACKING filtering in Java");
        assertEquals(expected.size(), ctx.getOrderDao().countOpenOrders(),
                "countOpenOrders() must agree with findOpenOrders()");
    }

    @Test
    void openPersonalTodoCountMatchesLoadingAndFilteringThem() {
        AppContext ctx = newContext();
        User manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        TodoService todos = ctx.getTodoService();

        long first = todos.addPersonalTodo(manager.id(), "Check dock capacity", "HIGH", BusinessClock.today());
        todos.addPersonalTodo(manager.id(), "Call carrier", "MEDIUM", null);
        todos.addPersonalTodo(manager.id(), "Reorder bale wrap", "LOW", null);
        todos.togglePersonalTodo(first, true);

        long expectedOpen = todos.listPersonalTodos(manager.id()).stream()
                .filter(t -> !t.completed())
                .count();

        assertEquals(2, expectedOpen, "Two of the three seeded to-dos should remain open");
        assertEquals(expectedOpen, todos.countOpenPersonalTodos(manager.id()),
                "countOpenPersonalTodos() must agree with filtering the loaded list");

        // Completing the rest must take the count to zero rather than leaving a stale badge.
        for (PersonalTodo todo : todos.listPersonalTodos(manager.id())) {
            todos.togglePersonalTodo(todo.id(), true);
        }
        assertEquals(0, todos.countOpenPersonalTodos(manager.id()));
    }

    @Test
    void managementSystemTodoListStillBuildsAfterPushingFiltersIntoSql() {
        AppContext ctx = newContext();
        User manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        ctx.getInvoiceDao().markPastDueAsOverdue(BusinessClock.today().plusYears(5));

        var items = ctx.getTodoService().buildSystemTodosFor(manager, ctx.getI18n());

        assertFalse(items.isEmpty(), "A manager should see live system to-dos from demo data");
        assertTrue(items.stream().allMatch(i -> i.summary() != null && !i.summary().isBlank()),
                "Every system to-do needs a resolved, non-blank localized summary");
        assertTrue(items.stream().anyMatch(i -> "P1".equals(i.priorityBadge())),
                "Overdue invoices should still surface as P1 items");
    }
}
