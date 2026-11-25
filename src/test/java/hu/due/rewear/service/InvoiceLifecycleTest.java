package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceEvent;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invoice may only move along its lifecycle; every move is recorded; and a reversal is tied to its
 * original by a real link instead of text in a line description.
 */
class InvoiceLifecycleTest {

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private long userId;

    private void setUp() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("lifecycle.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(db, prefs, i18n);
        userId = ctx.getUserDao().findByUsername("manager").orElseThrow().id();
    }

    private Invoice invoice(InvoiceStatus status, LocalDate issue, LocalDate due) {
        return ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "Test Buyer Kft.", "11111111-2-07", "2400 Dunaújváros", "buyer@test.hu",
                "Mixed Bales", new BigDecimal("10"), "kg", new BigDecimal("2000"),
                new BigDecimal("27"), "HU-27", "HUF", issue, due, status, userId);
    }

    private Invoice invoice(InvoiceStatus status) {
        return invoice(status, BusinessClock.today(), BusinessClock.today().plusDays(14));
    }

    private InvoiceStatus statusOf(Invoice invoice) {
        return ctx.getInvoiceDao().findById(invoice.id()).orElseThrow().status();
    }

    /** One invoice in each of the five states, reached by legitimate means. */
    private Map<InvoiceStatus, Invoice> oneInEveryState() {
        Map<InvoiceStatus, Invoice> all = new EnumMap<>(InvoiceStatus.class);
        all.put(InvoiceStatus.DRAFT, invoice(InvoiceStatus.DRAFT));
        all.put(InvoiceStatus.ISSUED, invoice(InvoiceStatus.ISSUED));
        all.put(InvoiceStatus.PAID, invoice(InvoiceStatus.PAID));
        Invoice late = invoice(InvoiceStatus.ISSUED, BusinessClock.today().minusDays(30), BusinessClock.today().minusDays(10));
        ctx.getInvoiceService().syncOverdueStatuses();
        all.put(InvoiceStatus.OVERDUE, ctx.getInvoiceDao().findById(late.id()).orElseThrow());
        Invoice toCancel = invoice(InvoiceStatus.ISSUED);
        ctx.getInvoiceService().issueCreditNote(toCancel.id(), userId);
        all.put(InvoiceStatus.CANCELLED, ctx.getInvoiceDao().findById(toCancel.id()).orElseThrow());
        for (var e : all.entrySet()) {
            assertEquals(e.getKey(), e.getValue().status(), "setup must really reach " + e.getKey());
        }
        return all;
    }

    @Test
    void everyStatusMoveIsAllowedOrRefusedExactlyAsTheLifecycleSays() {
        setUp();
        Map<InvoiceStatus, Set<InvoiceStatus>> expected = Map.of(
                InvoiceStatus.DRAFT, EnumSet.of(InvoiceStatus.ISSUED),
                InvoiceStatus.ISSUED, EnumSet.of(InvoiceStatus.PAID),
                InvoiceStatus.OVERDUE, EnumSet.of(InvoiceStatus.PAID),
                InvoiceStatus.PAID, EnumSet.of(InvoiceStatus.ISSUED),
                InvoiceStatus.CANCELLED, EnumSet.noneOf(InvoiceStatus.class));

        for (var from : oneInEveryState().entrySet()) {
            Invoice start = from.getValue();
            assertEquals(expected.get(from.getKey()), ctx.getInvoiceService().allowedStatusChanges(start),
                    "allowed targets from " + from.getKey());
            for (InvoiceStatus target : InvoiceStatus.values()) {
                if (target == from.getKey() || expected.get(from.getKey()).contains(target)) {
                    continue;
                }
                assertThrows(IllegalStateException.class,
                        () -> ctx.getInvoiceService().changeStatus(start.id(), target, userId),
                        from.getKey() + " -> " + target + " must be refused");
                assertEquals(from.getKey(), statusOf(start), "A refused move must change nothing");
            }
            assertTrue(ctx.getInvoiceService().history(start.id()).stream()
                            .noneMatch(e -> e.eventType().equals("PAID") || e.eventType().equals("PAYMENT_WITHDRAWN")),
                    "Refused moves must leave no event behind");
        }
    }

    @Test
    void anIssuedInvoiceCanNeverGoBackToDraftNorBeCancelledByHand() {
        setUp();
        Invoice issued = invoice(InvoiceStatus.ISSUED);

        IllegalStateException toDraft = assertThrows(IllegalStateException.class,
                () -> ctx.getInvoiceService().changeStatus(issued.id(), InvoiceStatus.DRAFT, userId));
        assertTrue(toDraft.getMessage().contains("credit note"), toDraft.getMessage());
        IllegalStateException cancel = assertThrows(IllegalStateException.class,
                () -> ctx.getInvoiceService().changeStatus(issued.id(), InvoiceStatus.CANCELLED, userId));
        assertTrue(cancel.getMessage().contains("credit note"), cancel.getMessage());
        assertEquals(InvoiceStatus.ISSUED, statusOf(issued));
    }

    @Test
    void issuingADraftStampsTodayKeepsThePaymentTermsAndIsRecorded() {
        setUp();
        LocalDate created = BusinessClock.today().minusDays(10);
        Invoice draft = invoice(InvoiceStatus.DRAFT, created, created.plusDays(14));

        Invoice issued = ctx.getInvoiceService().changeStatus(draft.id(), InvoiceStatus.ISSUED, userId);

        assertEquals(InvoiceStatus.ISSUED, issued.status());
        assertEquals(BusinessClock.today(), issued.issueDate(), "The issue date is the day it was issued");
        assertEquals(BusinessClock.today().plusDays(14), issued.dueDate(), "The 14-day terms are kept");
        List<InvoiceEvent> history = ctx.getInvoiceService().history(draft.id());
        assertEquals(1, history.size());
        assertEquals("ISSUED", history.get(0).eventType());
        assertEquals("DRAFT", history.get(0).fromStatus());
        assertEquals("ISSUED", history.get(0).toStatus());
        assertNotNull(history.get(0).userName());
    }

    @Test
    void payingAndWithdrawingAPaymentIsRecordedInOrder() {
        setUp();
        Invoice issued = invoice(InvoiceStatus.ISSUED);

        Invoice paid = ctx.getInvoiceService().changeStatus(issued.id(), InvoiceStatus.PAID, userId);
        assertNotNull(paid.paidDate());
        Invoice reopened = ctx.getInvoiceService().changeStatus(issued.id(), InvoiceStatus.ISSUED, userId);
        assertNull(reopened.paidDate());

        assertEquals(List.of("PAID", "PAYMENT_WITHDRAWN"),
                ctx.getInvoiceService().history(issued.id()).stream().map(InvoiceEvent::eventType).toList());
    }

    @Test
    void aCreditNoteIsLinkedToItsOriginalAndFreezesBothDocuments() {
        setUp();
        Invoice unpaid = invoice(InvoiceStatus.ISSUED);

        Invoice creditNote = ctx.getInvoiceService().issueCreditNote(unpaid.id(), userId);

        assertEquals(creditNote.id(), linkedCreditNote(unpaid.id()), "The link is stored, not inferred from text");
        assertEquals(InvoiceStatus.CANCELLED, statusOf(unpaid));
        assertThrows(IllegalStateException.class, () -> ctx.getInvoiceService().issueCreditNote(unpaid.id(), userId),
                "A second reversal must be refused");
        for (InvoiceStatus target : InvoiceStatus.values()) {
            if (target != InvoiceStatus.CANCELLED) {
                assertThrows(IllegalStateException.class,
                        () -> ctx.getInvoiceService().changeStatus(unpaid.id(), target, userId), "cancelled original -> " + target);
            }
            if (target != creditNote.status()) {
                assertThrows(IllegalStateException.class,
                        () -> ctx.getInvoiceService().changeStatus(creditNote.id(), target, userId), "credit note -> " + target);
            }
        }
        assertTrue(ctx.getInvoiceService().history(unpaid.id()).stream().anyMatch(e -> e.eventType().equals("CREDIT_NOTE_ISSUED")
                && creditNote.invoiceNumber().equals(e.note())));
        assertTrue(ctx.getInvoiceService().history(creditNote.id()).stream().anyMatch(e -> e.eventType().equals("CREDIT_NOTE_FOR")
                && unpaid.invoiceNumber().equals(e.note())));
    }

    @Test
    void aPaidInvoiceThatWasReversedCannotHaveItsPaymentWithdrawn() {
        setUp();
        Invoice paid = invoice(InvoiceStatus.PAID);
        assertEquals(Set.of(InvoiceStatus.ISSUED), ctx.getInvoiceService().allowedStatusChanges(paid));

        ctx.getInvoiceService().issueCreditNote(paid.id(), userId);

        Invoice after = ctx.getInvoiceDao().findById(paid.id()).orElseThrow();
        assertEquals(InvoiceStatus.PAID, after.status(), "A paid original stays paid for the cash audit trail");
        assertTrue(ctx.getInvoiceService().allowedStatusChanges(after).isEmpty());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ctx.getInvoiceService().changeStatus(paid.id(), InvoiceStatus.ISSUED, userId));
        assertTrue(ex.getMessage().contains("credit note"), ex.getMessage());
    }

    @Test
    void creditNotesWrittenBeforeTheLinkExistedAreLinkedOnStartup() throws SQLException {
        setUp();
        Invoice original = invoice(InvoiceStatus.ISSUED);
        Invoice creditNote = ctx.getInvoiceService().issueCreditNote(original.id(), userId);
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             var st = conn.createStatement()) {
            st.execute("UPDATE invoices SET credit_for_invoice_id = NULL");
        }
        assertEquals(0L, linkedCreditNote(original.id()), "Precondition: the link is gone, as in an older database");

        new SchemaInitializer(ctx.getDatabaseManager()).initialize();

        assertEquals(creditNote.id(), linkedCreditNote(original.id()));
        assertThrows(IllegalStateException.class, () -> ctx.getInvoiceService().issueCreditNote(original.id(), userId));
    }

    /** Id of the credit note linked to the invoice, or 0. */
    private long linkedCreditNote(long originalId) {
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             var ps = conn.prepareStatement("SELECT id FROM invoices WHERE credit_for_invoice_id = ?")) {
            ps.setLong(1, originalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
