package hu.due.rewear.service;

import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceStatus;

import java.util.EnumSet;
import java.util.Set;

/**
 * The only moves an invoice's status may make by hand.
 * <pre>
 *   DRAFT ──issue──▶ ISSUED ──pay──▶ PAID
 *                      │  ▲            │
 *                      │  └──unpay─────┘
 *                      ▼
 *                   OVERDUE ──pay──▶ PAID          (ISSUED → OVERDUE is set by the due date, never by hand)
 * </pre>
 * Everything else is closed on purpose:
 * <ul>
 *   <li>An invoice that has left {@code DRAFT} never goes back to it. A draft's lines and amounts can still be
 *       regenerated from its order, so reopening an issued invoice would let the figures the customer already
 *       received change silently.</li>
 *   <li>{@code CANCELLED} is reached only through a credit note, which is what keeps the ledger and the
 *       audit trail consistent; it cannot be picked from a menu, and it is final.</li>
 *   <li>A credit note is a document, not a payable: its status never changes by hand.</li>
 *   <li>Once a credit note reverses a paid invoice, that invoice stays paid; taking the payment back would
 *       leave the credit note's opposite cash movement with nothing to offset.</li>
 * </ul>
 */
public final class InvoiceLifecycle {

    private InvoiceLifecycle() { }

    /** Statuses {@code invoice} may be moved to by hand, not counting its current one. */
    public static Set<InvoiceStatus> allowedTargets(Invoice invoice, boolean hasCreditNote) {
        if (invoice.category() == InvoiceCategory.CREDIT_NOTE) {
            return EnumSet.noneOf(InvoiceStatus.class);
        }
        return switch (invoice.status()) {
            case DRAFT -> EnumSet.of(InvoiceStatus.ISSUED);
            case ISSUED, OVERDUE -> EnumSet.of(InvoiceStatus.PAID);
            case PAID -> hasCreditNote ? EnumSet.noneOf(InvoiceStatus.class) : EnumSet.of(InvoiceStatus.ISSUED);
            case CANCELLED -> EnumSet.noneOf(InvoiceStatus.class);
        };
    }

    /** @throws IllegalStateException with a message the user can act on if the move is not allowed */
    public static void require(Invoice invoice, InvoiceStatus target, boolean hasCreditNote) {
        if (allowedTargets(invoice, hasCreditNote).contains(target)) {
            return;
        }
        String number = invoice.invoiceNumber();
        if (invoice.category() == InvoiceCategory.CREDIT_NOTE) {
            throw new IllegalStateException("Credit note " + number + " is a finished document; its status cannot be changed.");
        }
        if (invoice.status() == InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Invoice " + number + " is cancelled and cannot be changed.");
        }
        if (target == InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("An invoice is cancelled by issuing a credit note, not by changing its status.");
        }
        if (target == InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Invoice " + number + " has been issued and cannot go back to draft. "
                    + "Correct it with a credit note.");
        }
        if (invoice.status() == InvoiceStatus.PAID && hasCreditNote) {
            throw new IllegalStateException("Invoice " + number + " is reversed by a credit note, so its payment cannot be withdrawn.");
        }
        if (target == InvoiceStatus.OVERDUE) {
            throw new IllegalStateException("Overdue is set automatically from the due date.");
        }
        throw new IllegalStateException("Invoice " + number + " cannot move from " + invoice.status() + " to " + target + ".");
    }
}
