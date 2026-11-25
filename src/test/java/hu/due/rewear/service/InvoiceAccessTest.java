package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Money actions follow the Invoices module of the role matrix, checked in the service itself, so a user
 * without access cannot act even if some screen or future caller reaches the method.
 */
class InvoiceAccessTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private User manager;
    private User operator;

    private void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("access.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(db, prefs, i18n);
        manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        operator = ctx.getUserDao().findByUsername("operator").orElseThrow();
    }

    private Invoice issued() {
        return ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "Test Buyer Kft.", "11111111-2-07", "2400 Dunaújváros", "buyer@test.hu",
                "Mixed Bales", new BigDecimal("10"), "kg", new BigDecimal("2000"),
                new BigDecimal("27"), "HU-27", "HUF", BusinessClock.today(), BusinessClock.today().plusDays(14),
                InvoiceStatus.ISSUED, manager.id());
    }

    private InvoiceStatus statusOf(Invoice invoice) {
        return ctx.getInvoiceDao().findById(invoice.id()).orElseThrow().status();
    }

    @Test
    void aRoleWithoutTheInvoicesModuleCannotChangeStatusOrIssueCreditNotes() {
        setUp();
        Invoice invoice = issued();
        assertFalse(AppModule.INVOICES.defaultEnabledFor(RoleCode.OPERATOR), "Precondition: operators have no Invoices module");
        assertFalse(ctx.getInvoiceService().mayManage(operator.id()));

        assertThrows(SecurityException.class,
                () -> ctx.getInvoiceService().changeStatus(invoice.id(), InvoiceStatus.PAID, operator.id()));
        assertThrows(SecurityException.class, () -> ctx.getInvoiceService().issueCreditNote(invoice.id(), operator.id()));

        assertEquals(InvoiceStatus.ISSUED, statusOf(invoice), "A refused action must change nothing");
        assertTrue(ctx.getInvoiceService().history(invoice.id()).isEmpty(), "…and leave no event");
    }

    @Test
    void managersAndAdminsMayAct() {
        setUp();
        Invoice invoice = issued();
        User admin = ctx.getUserDao().findByUsername("admin").orElseThrow();

        assertTrue(ctx.getInvoiceService().mayManage(manager.id()));
        assertTrue(ctx.getInvoiceService().mayManage(admin.id()));
        ctx.getInvoiceService().changeStatus(invoice.id(), InvoiceStatus.PAID, manager.id());
        assertEquals(InvoiceStatus.PAID, statusOf(invoice));
    }

    @Test
    void accessFollowsTheRoleMatrixWhenTheSuperAdminChangesIt() {
        setUp();
        Invoice invoice = issued();

        ctx.getSession().login(ctx.getUserDao().findByUsername("superadmin").orElseThrow());
        ctx.getRoleModuleService().setModuleEnabled(RoleCode.OPERATOR, AppModule.INVOICES, true);
        assertTrue(ctx.getInvoiceService().mayManage(operator.id()), "Granting the module grants the actions");
        ctx.getInvoiceService().changeStatus(invoice.id(), InvoiceStatus.PAID, operator.id());

        ctx.getRoleModuleService().setModuleEnabled(RoleCode.MANAGER, AppModule.INVOICES, false);
        assertFalse(ctx.getInvoiceService().mayManage(manager.id()), "Revoking the module revokes the actions");
        assertThrows(SecurityException.class,
                () -> ctx.getInvoiceService().changeStatus(invoice.id(), InvoiceStatus.ISSUED, manager.id()));
        assertEquals(InvoiceStatus.PAID, statusOf(invoice));
    }

    @Test
    void deactivatedAndUnknownUsersAreRefused() {
        setUp();
        Invoice invoice = issued();
        ctx.getUserDao().updateProfile(manager.id(), manager.lastName(), manager.firstName(), manager.roleId(), false);

        assertFalse(ctx.getInvoiceService().mayManage(manager.id()));
        assertThrows(SecurityException.class,
                () -> ctx.getInvoiceService().changeStatus(invoice.id(), InvoiceStatus.PAID, manager.id()));
        assertFalse(ctx.getInvoiceService().mayManage(987_654L));
        assertThrows(SecurityException.class, () -> ctx.getInvoiceService().issueCreditNote(invoice.id(), 987_654L));
        assertEquals(InvoiceStatus.ISSUED, statusOf(invoice));
    }
}
