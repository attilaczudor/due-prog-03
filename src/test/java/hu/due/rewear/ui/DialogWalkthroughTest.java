package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opens the modal dialogs the screen walkthrough cannot reach, and checks what a person would see in them:
 * the invoice history, which status changes are offered, and which buttons are available to whom.
 * <p>
 * A modal dialog blocks the thread that opens it, so the inspection runs from a Swing timer that fires inside
 * the dialog's own event pump, and closes the dialog afterwards.
 */
class DialogWalkthroughTest {

    @TempDir
    Path tempDir;

    /** What a dialog looked like: its buttons, combo contents and tables, plus how much of it was painted. */
    /** One table in the dialog, identified by its column headers so tables cannot be confused. */
    private record TableShape(List<String> columns, int rows) { }

    private record Inspection(List<String> enabledButtons, List<String> disabledButtons,
                              List<List<String>> comboItems, List<TableShape> tables, double ink) {

        /** The table whose first column is {@code firstColumn}, which is how the history table is told apart. */
        TableShape tableStartingWith(String firstColumn) {
            return tables.stream().filter(t -> !t.columns().isEmpty() && t.columns().get(0).equalsIgnoreCase(firstColumn))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "No table starting with '" + firstColumn + "'; found " + tables));
        }

        boolean hasEnabled(String text) {
            return enabledButtons.stream().anyMatch(b -> b.equalsIgnoreCase(text));
        }

        boolean hasDisabled(String text) {
            return disabledButtons.stream().anyMatch(b -> b.equalsIgnoreCase(text));
        }
    }

    private static void collect(Component component, List<Component> into) {
        into.add(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, into);
            }
        }
    }

    private static double inkFraction(BufferedImage image) {
        int background = image.getRGB(0, 0);
        int different = 0;
        int sampled = 0;
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                sampled++;
                if (image.getRGB(x, y) != background) {
                    different++;
                }
            }
        }
        return different / (double) sampled;
    }

    /**
     * Runs {@code openDialog}, which blocks while its modal dialog is up, and inspects that dialog from a
     * timer firing inside the dialog's event pump.
     */
    private Inspection openAndInspect(String name, Runnable openDialog) throws Exception {
        AtomicReference<Inspection> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        String shotDir = System.getProperty("shot.dir");

        Timer timer = new Timer(700, event -> {
            try {
                Window dialog = null;
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog candidate && candidate.isShowing()) {
                        dialog = candidate;
                    }
                }
                assertNotNull(dialog, "No dialog came up for " + name);
                List<Component> all = new ArrayList<>();
                collect(((JDialog) dialog).getContentPane(), all);

                List<String> enabled = new ArrayList<>();
                List<String> disabled = new ArrayList<>();
                List<List<String>> combos = new ArrayList<>();
                List<TableShape> tables = new ArrayList<>();
                for (Component component : all) {
                    if (component instanceof AbstractButton button && button.getText() != null
                            && !button.getText().isBlank()) {
                        (button.isEnabled() ? enabled : disabled).add(button.getText());
                    } else if (component instanceof JComboBox<?> combo) {
                        List<String> items = new ArrayList<>();
                        for (int i = 0; i < combo.getItemCount(); i++) {
                            items.add(String.valueOf(combo.getItemAt(i)));
                        }
                        combos.add(items);
                    } else if (component instanceof JTable table) {
                        List<String> columns = new ArrayList<>();
                        for (int i = 0; i < table.getColumnCount(); i++) {
                            columns.add(String.valueOf(table.getColumnName(i)));
                        }
                        tables.add(new TableShape(columns, table.getRowCount()));
                    }
                }

                BufferedImage image = new BufferedImage(Math.max(1, dialog.getWidth()),
                        Math.max(1, dialog.getHeight()), BufferedImage.TYPE_INT_RGB);
                var g = image.createGraphics();
                try {
                    ((JDialog) dialog).getContentPane().paint(g);
                } finally {
                    g.dispose();
                }
                if (shotDir != null) {
                    ImageIO.write(image, "png", new File(shotDir, "dialog-" + name + ".png"));
                }
                result.set(new Inspection(enabled, disabled, combos, tables, inkFraction(image)));
                dialog.dispose();
            } catch (Throwable t) {
                failure.set(t);
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog candidate && candidate.isShowing()) {
                        candidate.dispose();
                    }
                }
            }
        });
        timer.setRepeats(false);
        SwingUtilities.invokeAndWait(() -> {
            timer.start();
            openDialog.run();
        });
        if (failure.get() != null) {
            throw new AssertionError("Inspecting " + name + " failed", failure.get());
        }
        Inspection inspection = result.get();
        assertNotNull(inspection, name + " produced no inspection");
        assertTrue(inspection.ink() > 0.02, name + " was drawn almost blank: " + inspection.ink());
        return inspection;
    }

    private AppContext context() {
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ThemeManager.applyTheme(false);
        return new AppContext(new DatabaseManager(tempDir.resolve("dialogs.db")),
                new AppPreferences(tempDir.resolve("app.properties")), i18n);
    }

    @Test
    void theInvoiceDialogShowsItsHistoryAndOffersOnlyTheStatusChangesThatAreAllowed() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        AppContext ctx = context();
        User manager = ctx.getUserDao().findByUsername("manager").orElseThrow();
        ctx.getSession().login(manager);

        Invoice issued = ctx.getInvoiceDao().findAll().stream()
                .filter(i -> i.status() == InvoiceStatus.ISSUED).findFirst().orElseThrow();
        ctx.getInvoiceService().changeStatus(issued.id(), InvoiceStatus.PAID, manager.id());
        Invoice paid = ctx.getInvoiceDao().findById(issued.id()).orElseThrow();

        AtomicReference<InvoicesView> viewRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> viewRef.set(new InvoicesView(ctx)));
        Inspection seen = openAndInspect("invoice-manager", () -> viewRef.get().openInvoiceDetails(paid));

        // The history table — told apart from the line items by its first column — carries the payment
        // that was just recorded.
        TableShape history = seen.tableStartingWith(ctx.getI18n().t("invoice.history.when"));
        assertTrue(history.rows() >= 1, "The invoice history should list the payment: " + history);
        assertTrue(seen.tableStartingWith(ctx.getI18n().t("invoice.item.description")).rows() >= 1,
                "The line items should still be shown as well");
        // Status choices: the current one, plus only the moves the lifecycle allows from PAID.
        List<String> statuses = seen.comboItems().stream()
                .filter(items -> items.stream().anyMatch(item -> item.equalsIgnoreCase(ctx.getI18n().t("status.invoice.PAID"))))
                .findFirst().orElseThrow(() -> new AssertionError("No status chooser: " + seen.comboItems()));
        assertTrue(statuses.contains(ctx.getI18n().t("status.invoice.ISSUED")), "A payment may be withdrawn: " + statuses);
        assertFalse(statuses.contains(ctx.getI18n().t("status.invoice.DRAFT")), "Never back to draft: " + statuses);
        assertFalse(statuses.contains(ctx.getI18n().t("status.invoice.CANCELLED")),
                "Cancelling happens through a credit note: " + statuses);
        assertTrue(seen.hasEnabled(ctx.getI18n().t("invoice.status_update")), "Apply should be available to a manager");
        assertTrue(seen.hasEnabled(ctx.getI18n().t("invoice.credit_note.btn")));
    }

    @Test
    void aUserWithoutTheInvoicesModuleSeesTheDialogWithItsActionsTurnedOff() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        AppContext ctx = context();
        User superAdmin = ctx.getUserDao().findByUsername("superadmin").orElseThrow();
        User manager = ctx.getUserDao().findByUsername("manager").orElseThrow();

        ctx.getSession().login(superAdmin);
        ctx.getRoleModuleService().setModuleEnabled(RoleCode.MANAGER, AppModule.INVOICES, false);
        ctx.getSession().login(manager);
        assertFalse(ctx.getInvoiceService().mayManage(manager.id()), "Precondition: access was revoked");

        Invoice invoice = ctx.getInvoiceDao().findAll().get(0);
        AtomicReference<InvoicesView> viewRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> viewRef.set(new InvoicesView(ctx)));
        Inspection seen = openAndInspect("invoice-no-access", () -> viewRef.get().openInvoiceDetails(invoice));

        assertTrue(seen.hasDisabled(ctx.getI18n().t("invoice.status_update")),
                "Apply must be off: enabled=" + seen.enabledButtons());
        assertTrue(seen.hasDisabled(ctx.getI18n().t("invoice.credit_note.btn")),
                "Issuing a credit note must be off: enabled=" + seen.enabledButtons());
        assertTrue(seen.hasEnabled(ctx.getI18n().t("common.close")), "Closing the dialog must still work");
    }

    @Test
    void theDeliveryDriversPickListOpensWithTheirOwnOrder() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        AppContext ctx = context();
        User driver = ctx.getUserDao().findByUsername("forklift2").orElseThrow();
        ctx.getSession().login(driver);
        assertFalse(ctx.getPickListService().forDriver(driver.id()).isEmpty(),
                "Precondition: the demo gives the delivery driver something to collect");

        AtomicReference<TransportView> viewRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            TransportView view = new TransportView(ctx, TransportView.Mode.STAGING);
            view.refreshData();
            viewRef.set(view);
        });
        Inspection seen = openAndInspect("pick-list", () -> viewRef.get().showPickList());

        assertTrue(seen.enabledButtons().size() > 1, "The preview should offer its actions: " + seen.enabledButtons());
    }
}
