package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.DaoException;
import hu.due.rewear.dao.SmartFolderDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.EmailMessage;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.SmartFolder;
import hu.due.rewear.model.SmartFolder.Field;
import hu.due.rewear.model.SmartFolder.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SmartFolderTest {
    @TempDir Path temp;

    @Test
    void foldersPersistAcrossSessionsAndAreIsolatedByOwnerAndWorkspace() {
        var db = new DatabaseManager(temp.resolve("folders.db"));
        var ctx = new AppContext(db, new AppPreferences(temp.resolve("prefs")), new I18nManager());
        long owner = ctx.getUserDao().findByUsername("superadmin").orElseThrow().id();
        long other = ctx.getUserDao().findByUsername("manager").orElseThrow().id();
        var dao = ctx.getSmartFolderDao();

        var rules = Map.of(Field.CURRENCY, List.of("USD", "EUR"), Field.DIRECTION, List.of("OUTGOING"));
        long id = dao.save(owner, Scope.INVOICE, new SmartFolder(0, "Exports", "invoices", rules));
        dao.save(owner, Scope.MAIL, new SmartFolder(0, "Exports", "folder", Map.of()));
        dao.save(other, Scope.INVOICE, new SmartFolder(0, "Exports", "folder", Map.of()));

        var reopened = new SmartFolderDao(new DatabaseManager(db.getDatabasePath()));
        SmartFolder saved = reopened.find(owner, Scope.INVOICE).get(0);
        assertEquals(id, saved.id());
        assertEquals("invoices", saved.icon());
        assertEquals(List.of("EUR", "USD"), saved.rules().get(Field.CURRENCY));
        assertFalse(reopened.find(other, Scope.INVOICE).stream().anyMatch(folder -> folder.id() == id));
        assertThrows(DaoException.class, () -> reopened.save(other, Scope.INVOICE, saved));
        assertThrows(DaoException.class, () -> reopened.save(owner, Scope.MAIL, saved));
        assertThrows(DaoException.class, () -> reopened.save(owner, Scope.INVOICE,
                new SmartFolder(0, "exports", "folder", Map.of())));

        reopened.delete(other, Scope.INVOICE, id);
        assertEquals(id, reopened.find(owner, Scope.INVOICE).get(0).id());
        var edited = new SmartFolder(id, "Supplier EUR", "inbox", Map.of(Field.DIRECTION, List.of("INCOMING")));
        reopened.save(owner, Scope.INVOICE, edited);
        assertEquals(edited, reopened.find(owner, Scope.INVOICE).get(0));

        reopened.delete(owner, Scope.INVOICE, id);
        assertTrue(reopened.find(owner, Scope.INVOICE).isEmpty());
        assertEquals(1, reopened.find(owner, Scope.MAIL).size());
        assertEquals(1, reopened.find(other, Scope.INVOICE).size());
    }

    @Test
    void invoiceAndMailRulesMatchWithoutMovingOrCopyingRecords() {
        var ctx = new AppContext(new DatabaseManager(temp.resolve("rules.db")),
                new AppPreferences(temp.resolve("prefs")), new I18nManager());
        var outgoing = ctx.getInvoiceDao().findAll().stream()
                .filter(invoice -> invoice.direction() == InvoiceDirection.OUTGOING
                        && invoice.customerId() != null).findFirst().orElseThrow();
        var incoming = ctx.getInvoiceDao().findAll().stream()
                .filter(invoice -> invoice.direction() == InvoiceDirection.INCOMING).findFirst().orElseThrow();

        var invoiceFolder = new SmartFolder(0, "Exports", "invoices", Map.of(
                Field.CURRENCY, List.of("USD", outgoing.effectiveCurrencyCode()).stream().distinct().toList(),
                Field.PARTNER, List.of("id:" + outgoing.customerId(), "id:999999"),
                Field.DIRECTION, List.of(InvoiceDirection.OUTGOING.name()),
                Field.SEARCH, List.of(outgoing.invoiceNumber().toLowerCase(java.util.Locale.ROOT))));
        assertTrue(invoiceFolder.matches(outgoing));
        assertFalse(invoiceFolder.matches(incoming));

        var mailFolder = new SmartFolder(0, "Stock inquiries", "inbox", Map.of(
                Field.SENDER, List.of("@example.test"),
                Field.SEARCH, List.of("stock", "CREAM"),
                Field.READ, List.of("false")));
        var inbox = new EmailMessage(1, 1, EmailMessage.FOLDER_INBOX, "buyer@example.test",
                "sales@example.test", "", "Stock inquiry", "Do you have any cream bales?", null,
                false, true, "RECEIVED", null);
        assertTrue(mailFolder.matches(inbox));
        assertFalse(mailFolder.matches(inbox.withRead(true)));
        var trash = new EmailMessage(1, 1, EmailMessage.FOLDER_TRASH, inbox.fromAddress(), inbox.toAddresses(),
                "", inbox.subject(), inbox.body(), null, false, true, "RECEIVED", null);
        assertFalse(mailFolder.matches(trash));
        assertTrue(new SmartFolder(0, "Trash search", "inbox", Map.of(
                Field.MAILBOX, List.of(EmailMessage.FOLDER_TRASH), Field.BODY, List.of("cream"))).matches(trash));
    }
}
