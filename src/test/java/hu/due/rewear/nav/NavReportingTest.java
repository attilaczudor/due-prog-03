package hu.due.rewear.nav;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.NavOperation;
import hu.due.rewear.model.NavReport;
import hu.due.rewear.model.NavReportState;
import hu.due.rewear.model.NavSettings;
import hu.due.rewear.model.NavSettings.Field;
import hu.due.rewear.service.NavReportLifecycle;
import hu.due.rewear.service.NavReportingService;
import hu.due.rewear.util.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
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
 * The reporting path end to end, against a service of our own running on this machine.
 *
 * <p>The live tax authority is never contacted, and these tests are written so that it could not be: the
 * client is pointed at a local address, and the one test that proves the switch works asserts that the local
 * service received nothing at all.
 */
class NavReportingTest {

    private static final String SIGN_KEY = "sign-key-abcdefghijklmno";
    private static final String EXCHANGE_KEY = "0123456789abcdef";

    @TempDir
    Path tempDir;

    private AppContext ctx;
    private FakeNavService nav;
    private NavReportingService reporting;
    private long userId;

    @BeforeEach
    void setUp() throws IOException {
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ctx = new AppContext(new DatabaseManager(tempDir.resolve("nav.db")),
                new AppPreferences(tempDir.resolve("nav.properties")), i18n);
        userId = ctx.getUserDao().findByUsername("manager").orElseThrow().id();
        nav = new FakeNavService(SIGN_KEY, EXCHANGE_KEY);
        reporting = new NavReportingService(ctx.getDatabaseManager(), ctx.getNavReportDao(),
                ctx.getInvoiceDao(), ctx.getSettingsDao(), ctx.getNavCredentialStore(),
                ctx.getNotificationService(), settings -> new NavHttpClient(nav.baseUrl(), settings,
                        ctx.getNavCredentialStore().load()));
        ctx.getInvoiceService().setReportingHook(reporting);
    }

    @AfterEach
    void tearDown() {
        nav.close();
    }

    // ------------------------------------------------------------------ queueing

    @Test
    void issuingAnOutgoingInvoiceQueuesItForReporting() {
        var invoice = outgoingInvoice(InvoiceStatus.ISSUED);

        NavReport report = reportFor(invoice.id());
        assertEquals(NavOperation.CREATE, report.operation());
        assertEquals(NavReportState.PENDING, report.state());
        assertFalse(ctx.getNavReportDao().findEvents(report.id()).isEmpty(),
                "The queueing itself is part of the evidence and belongs in the history");
    }

    @Test
    void aDraftIsNotReportedAndAnIncomingInvoiceIsSomebodyElsesReport() {
        var draft = outgoingInvoice(InvoiceStatus.DRAFT);
        var purchase = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.INCOMING, InvoiceCategory.RAW_STOCK_PURCHASE, null, null,
                "Beszállító Kft.", "22222222-2-07", "1095 Budapest", "seller@test.hu",
                "Raw stock", new BigDecimal("100"), "kg", new BigDecimal("2"),
                new BigDecimal("27"), "HU-27", "HUF", BusinessClock.today(),
                BusinessClock.today().plusDays(30), InvoiceStatus.ISSUED, userId);

        assertTrue(ctx.getNavReportDao().findLatestForInvoice(draft.id()).isEmpty(),
                "A draft is not an invoice yet");
        assertTrue(ctx.getNavReportDao().findLatestForInvoice(purchase.id()).isEmpty(),
                "The supplier reports their own outgoing invoice");
    }

    @Test
    void issuingADraftLaterQueuesItThen() {
        var draft = outgoingInvoice(InvoiceStatus.DRAFT);
        assertTrue(ctx.getNavReportDao().findLatestForInvoice(draft.id()).isEmpty());

        ctx.getInvoiceService().changeStatus(draft.id(), InvoiceStatus.ISSUED, userId);

        assertEquals(NavOperation.CREATE, reportFor(draft.id()).operation());
    }

    @Test
    void aCreditNoteIsQueuedAsACancellationNamingTheOriginal() {
        // The identity is configured but reporting stays off: this checks the document, not the sending.
        enableReporting(false);
        var invoice = outgoingInvoice(InvoiceStatus.ISSUED);
        var creditNote = ctx.getInvoiceService().issueCreditNote(invoice.id(), userId);

        NavReport report = reportFor(creditNote.id());
        assertEquals(NavOperation.STORNO, report.operation());

        String xml = NavInvoiceXmlWriter.toXml(reporting.assemble(report));
        assertTrue(xml.contains("<originalInvoiceNumber>" + invoice.invoiceNumber() + "</originalInvoiceNumber>"),
                "A cancellation that does not name what it cancels is not a cancellation");
        assertTrue(xml.contains("<modificationIndex>"), "NAV orders a chain of corrections by this index");
    }

    // ------------------------------------------------------------------ the switch

    @Test
    void nothingIsSentWhileReportingIsSwitchedOff() {
        enableReporting(false);
        outgoingInvoice(InvoiceStatus.ISSUED);

        var run = reporting.runQueue();

        assertFalse(run.didSomething());
        assertEquals(List.of(), nav.requests, "Not one request may leave while reporting is off");
        assertEquals(NavReportState.PENDING, reportFor(lastInvoiceId()).state(),
                "The queue still fills, so the backlog is visible when it is switched on");
    }

    @Test
    void credentialsAreNeverStoredInTheDatabaseAndTheFileIsOwnerOnly() throws SQLException, IOException {
        enableReporting(true);

        var store = ctx.getNavCredentialStore();
        assertTrue(store.exists());
        assertTrue(store.isProtected(), "The credentials file must not be readable by other accounts");
        assertEquals("rw-------",
                java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())));

        try (Connection conn = ctx.getDatabaseManager().getConnection();
             Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM app_settings WHERE setting_value IN ('"
                     + SIGN_KEY + "', '" + EXCHANGE_KEY + "', 'Technical-Pass-2026')")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "A secret in the database travels with every backup");
        }
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void anIssuedInvoiceIsSentConfirmedAndRecordedAsReported() {
        enableReporting(true);
        var invoice = outgoingInvoice(InvoiceStatus.ISSUED);

        var run = reporting.runQueue();

        assertEquals(1, run.submitted());
        assertNull(nav.signatureProblem, "The signature NAV would check did not verify: " + nav.signatureProblem);
        assertEquals(1, nav.submittedInvoices.size());
        assertTrue(nav.submittedInvoices.get(0).contains("<invoiceNumber>" + invoice.invoiceNumber()),
                "The document NAV receives must be the invoice that was issued");
        assertTrue(nav.submittedInvoices.get(0).contains("<invoiceNetAmountHUF>"),
                "The report states the forint amounts whatever the invoice was written in");

        NavReport submitted = reportFor(invoice.id());
        assertEquals(NavReportState.SUBMITTED, submitted.state());
        assertEquals("TX-0001", submitted.transactionId(), "The transaction id is the evidence of reporting");

        var second = reporting.runQueue();
        assertEquals(1, second.confirmed());
        NavReport done = reportFor(invoice.id());
        assertEquals(NavReportState.DONE, done.state());
        assertTrue(done.state().isReported());
        assertNotNull(done.completedAt());
    }

    @Test
    void whileNavIsStillProcessingTheReportWaitsRatherThanGuessing() {
        enableReporting(true);
        nav.outcome = FakeNavService.Outcome.PROCESSING;
        var invoice = outgoingInvoice(InvoiceStatus.ISSUED);

        reporting.runQueue();
        var second = reporting.runQueue();

        assertEquals(0, second.confirmed());
        assertEquals(NavReportState.SUBMITTED, reportFor(invoice.id()).state());
    }

    @Test
    void warningsCountAsReportedButSayWhatNavComplainedAbout() {
        enableReporting(true);
        nav.outcome = FakeNavService.Outcome.WARN;
        var invoice = outgoingInvoice(InvoiceStatus.ISSUED);

        reporting.runQueue();
        reporting.runQueue();

        NavReport report = reportFor(invoice.id());
        assertEquals(NavReportState.WARN, report.state());
        assertTrue(report.state().isReported());
        assertTrue(report.message().contains("INCORRECT_EXCHANGE_RATE"));
    }

    // ------------------------------------------------------------------ failures

    @Test
    void aRejectionLeavesTheInvoiceUnreportedAndTellsSomebody() {
        enableReporting(true);
        nav.outcome = FakeNavService.Outcome.ERROR;
        var invoice = outgoingInvoice(InvoiceStatus.ISSUED);

        reporting.runQueue();
        reporting.runQueue();

        NavReport report = reportFor(invoice.id());
        assertEquals(NavReportState.ERROR, report.state());
        assertTrue(report.state().needsAttention());
        assertFalse(report.state().isReported(), "A rejected report is not a report");
        assertTrue(report.message().contains("INVALID_CUSTOMER_VAT"));

        int before = nav.requests.size();
        reporting.runQueue();
        assertEquals(before, nav.requests.size(),
                "A rejection on content must not be retried in a loop; it needs a person");
    }

    @Test
    void anOutageIsRetriedLaterRatherThanTreatedAsARejection() {
        enableReporting(true);
        nav.failWithStatus = 503;
        var invoice = outgoingInvoice(InvoiceStatus.ISSUED);

        var run = reporting.runQueue();

        assertEquals(1, run.deferred());
        NavReport report = reportFor(invoice.id());
        assertEquals(NavReportState.PENDING, report.state(), "The invoice is still reportable");
        assertEquals(1, report.attemptCount());
        assertTrue(report.nextAttemptAt().isAfter(BusinessClock.now()),
                "A deferred report waits; hammering an outage helps nobody");

        nav.failWithStatus = 0;
        // The wait is real, so the queue is told to try now the way an operator would.
        reporting.retry(report.id(), userId);
        assertEquals(1, reporting.runQueue().submitted());
    }

    @Test
    void aZeroRatedInvoiceWithNoExemptionCaseIsRefusedWithSomethingActionable() {
        enableReporting(true);
        var invoice = ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "Wien Textil GmbH", "ATU12345678", "1010 Wien", "buyer@test.at",
                "Sorted clothing", new BigDecimal("500"), "kg", new BigDecimal("1.20"),
                BigDecimal.ZERO, "EU-0", "EUR", BusinessClock.today(),
                BusinessClock.today().plusDays(14), InvoiceStatus.ISSUED, userId);

        reporting.runQueue();

        NavReport report = reportFor(invoice.id());
        assertEquals(NavReportState.ERROR, report.state());
        assertTrue(report.message().toLowerCase().contains("exemption"),
                "The message must name what to fix, not just that something failed: " + report.message());
        assertEquals(List.of(), nav.submittedInvoices, "A document we know NAV would reject is not sent");
    }

    @Test
    void anExemptionCaseConfiguredOnTheTaxTypeIsReported() throws SQLException {
        enableReporting(true);
        ctx.getDatabaseManager().runInTransaction(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("UPDATE tax_types SET nav_vat_exemption_case = 'AAM',"
                        + " nav_vat_exemption_reason = 'Intra-Community supply' WHERE code = 'EU-0'");
            }
        });
        ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "Wien Textil GmbH", "ATU12345678", "1010 Wien", "buyer@test.at",
                "Sorted clothing", new BigDecimal("500"), "kg", new BigDecimal("1.20"),
                BigDecimal.ZERO, "EU-0", "EUR", BusinessClock.today(),
                BusinessClock.today().plusDays(14), InvoiceStatus.ISSUED, userId);

        assertEquals(1, reporting.runQueue().submitted());
        assertTrue(nav.submittedInvoices.get(0).contains("<base:case>AAM</base:case>"));
        assertFalse(nav.submittedInvoices.get(0).contains("<vatPercentage>"),
                "An exempt line carries a case, not a rate");
    }

    // ------------------------------------------------------------------ annulment and the state machine

    @Test
    void annulmentIsRecordedAsAskedForAndNeverAsFinished() {
        enableReporting(true);
        var invoice = outgoingInvoice(InvoiceStatus.ISSUED);
        reporting.runQueue();
        reporting.runQueue();
        long reportId = reportFor(invoice.id()).id();

        assertThrows(IllegalArgumentException.class, () -> reporting.requestAnnulment(reportId, "  ", userId),
                "NAV asks for a reason, so the application does too");
        reporting.requestAnnulment(reportId, "Reported against the wrong taxpayer", userId);

        NavReport report = reportFor(invoice.id());
        assertEquals(NavReportState.ANNUL_REQUESTED, report.state());
        assertFalse(report.state().isReported());
        assertEquals(Set.of(), NavReportLifecycle.allowedFrom(NavReportState.ANNUL_REQUESTED),
                "Only a human at NAV can move it on from here");
    }

    @Test
    void aReportedInvoiceIsNeverQuietlyReportedAgain() {
        assertFalse(NavReportLifecycle.allows(NavReportState.DONE, NavReportState.PENDING));
        assertFalse(NavReportLifecycle.allows(NavReportState.WARN, NavReportState.PENDING));
        assertFalse(NavReportLifecycle.allows(NavReportState.ERROR, NavReportState.DONE),
                "A rejected invoice becomes reported by being accepted, not by being relabelled");
        assertTrue(NavReportLifecycle.allows(NavReportState.ERROR, NavReportState.PENDING));
    }

    @Test
    void issuingTwiceDoesNotQueueTheSameInvoiceTwice() {
        var invoice = outgoingInvoice(InvoiceStatus.ISSUED);
        ctx.getInvoiceService().changeStatus(invoice.id(), InvoiceStatus.PAID, userId);
        ctx.getInvoiceService().changeStatus(invoice.id(), InvoiceStatus.ISSUED, userId);

        assertEquals(1, ctx.getNavReportDao().findAll().stream()
                .filter(report -> report.invoiceId() == invoice.id()).count(),
                "A duplicate report is something the company would then have to annul");
    }

    // ------------------------------------------------------------------ helpers

    private hu.due.rewear.model.Invoice outgoingInvoice(InvoiceStatus status) {
        return ctx.getInvoiceService().createManualInvoice(
                InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                "Pontos Kft.", "11111111-2-07", "2400 Dunaújváros", "buyer@test.hu",
                "Sorted clothing", new BigDecimal("250"), "kg", new BigDecimal("400"),
                new BigDecimal("27"), "HU-27", "HUF", BusinessClock.today(),
                BusinessClock.today().plusDays(14), status, userId);
    }

    private long lastInvoiceId() {
        return ctx.getNavReportDao().findAll().get(0).invoiceId();
    }

    private NavReport reportFor(long invoiceId) {
        return ctx.getNavReportDao().findLatestForInvoice(invoiceId).orElseThrow();
    }

    private void enableReporting(boolean on) {
        Map<Field, String> values = new EnumMap<>(Field.class);
        values.put(Field.ENABLED, Boolean.toString(on));
        values.put(Field.ENVIRONMENT, "TEST");
        values.put(Field.TAX_NUMBER, "27891042");
        values.put(Field.VAT_CODE, "2");
        values.put(Field.COUNTY_CODE, "07");
        values.put(Field.LOGIN, "tech-user-1");
        ctx.getSettingsDao().saveNavSettings(new NavSettings(values));
        ctx.getNavCredentialStore().save(new NavCredentials("Technical-Pass-2026", SIGN_KEY, EXCHANGE_KEY));
    }
}
