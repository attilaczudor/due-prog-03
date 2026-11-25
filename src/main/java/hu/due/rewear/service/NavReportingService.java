package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.InvoiceDao;
import hu.due.rewear.dao.NavReportDao;
import hu.due.rewear.dao.SettingsDao;
import hu.due.rewear.model.CompanyProfile;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceItem;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.NavOperation;
import hu.due.rewear.model.NavReport;
import hu.due.rewear.model.NavReportState;
import hu.due.rewear.model.NavSettings;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.nav.NavClient;
import hu.due.rewear.nav.NavCredentialStore;
import hu.due.rewear.nav.NavCredentials;
import hu.due.rewear.nav.NavException;
import hu.due.rewear.nav.NavHttpClient;
import hu.due.rewear.nav.NavInvoiceXmlWriter;
import hu.due.rewear.nav.NavResponses;
import hu.due.rewear.nav.ReportableInvoice;
import hu.due.rewear.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Keeps the tax authority's picture of the company's outgoing invoices in step with the company's own.
 *
 * <p>Two facts shape everything here. The obligation is discharged only when NAV has <em>processed</em> the
 * report, so an accepted submission is a promise and not yet compliance — which is why a report moves to
 * {@code SUBMITTED} and stays there until a status query says what became of it. And a report that fails on
 * its content will fail again identically, so only transport failures are retried; a rejection is put in
 * front of a person instead, because an unreported invoice is somebody's problem to solve and not a queue's.
 *
 * <p>Nothing leaves this machine unless an administrator has entered a technical user and switched reporting
 * on. Until then the queue still fills, so that when it is switched on the backlog is visible rather than
 * lost.
 */
public final class NavReportingService implements InvoiceReportingHook {
    private static final Logger LOG = LoggerFactory.getLogger(NavReportingService.class);

    /** Attempts after which a report stops retrying by itself and waits for a person. */
    private static final int MAX_ATTEMPTS = 6;

    private final DatabaseManager db;
    private final NavReportDao reports;
    private final InvoiceDao invoices;
    private final SettingsDao settings;
    private final NavCredentialStore credentialStore;
    private final NotificationService notifications;
    private final Function<NavSettings, NavClient> clientFactory;

    public NavReportingService(DatabaseManager db, NavReportDao reports, InvoiceDao invoices,
                               SettingsDao settings, NavCredentialStore credentialStore,
                               NotificationService notifications) {
        this(db, reports, invoices, settings, credentialStore, notifications, null);
    }

    /**
     * @param clientFactory how to build a client for the current settings; null means the real HTTPS client.
     *                      The tests pass a factory pointing at a server on this machine.
     */
    public NavReportingService(DatabaseManager db, NavReportDao reports, InvoiceDao invoices,
                               SettingsDao settings, NavCredentialStore credentialStore,
                               NotificationService notifications,
                               Function<NavSettings, NavClient> clientFactory) {
        this.db = db;
        this.reports = reports;
        this.invoices = invoices;
        this.settings = settings;
        this.credentialStore = credentialStore;
        this.notifications = notifications;
        this.clientFactory = clientFactory != null ? clientFactory
                : navSettings -> new NavHttpClient(navSettings.endpoint(), navSettings, credentialStore.load());
    }

    // ---------------------------------------------------------------- queueing

    @Override
    public void onInvoiceIssued(Connection conn, long invoiceId, long userId) throws SQLException {
        Invoice invoice = invoices.findById(conn, invoiceId).orElse(null);
        if (invoice == null || !isReportable(invoice)) {
            return;
        }
        queue(conn, invoiceId, NavOperation.CREATE, userId);
    }

    @Override
    public void onCreditNoteIssued(Connection conn, long creditNoteId, long originalInvoiceId, long userId)
            throws SQLException {
        Invoice original = invoices.findById(conn, originalInvoiceId).orElse(null);
        // The credit note follows the original: a cancellation is reportable exactly when the invoice it
        // cancels was, and a credit note against a purchase invoice is somebody else's report to file.
        if (original == null || original.direction() != InvoiceDirection.OUTGOING) {
            return;
        }
        queue(conn, creditNoteId, NavOperation.STORNO, userId);
    }

    private void queue(Connection conn, long invoiceId, NavOperation operation, long userId) throws SQLException {
        if (reports.hasLiveReport(conn, invoiceId, operation)) {
            return;
        }
        LocalDateTime now = BusinessClock.now();
        long reportId = reports.insert(conn, invoiceId, operation, now);
        reports.addEvent(conn, reportId, null, NavReportState.PENDING, "Queued on issue", userId, now);
    }

    private static boolean isReportable(Invoice invoice) {
        return invoice.direction() == InvoiceDirection.OUTGOING && invoice.status() != InvoiceStatus.DRAFT;
    }

    // ---------------------------------------------------------------- the queue

    /** What one run of the queue did. */
    public record QueueRun(int submitted, int confirmed, int failed, int deferred) {
        public boolean didSomething() {
            return submitted + confirmed + failed + deferred > 0;
        }
    }

    /** True when an administrator has switched reporting on and the credentials are present and usable. */
    public boolean isReady() {
        NavSettings navSettings = settings.getNavSettings();
        if (!navSettings.enabled()) {
            return false;
        }
        NavCredentials credentials = credentialStore.load();
        return credentials.isComplete() && credentials.hasUsableExchangeKey()
                && !navSettings.value(NavSettings.Field.LOGIN).isBlank()
                && !navSettings.value(NavSettings.Field.TAX_NUMBER).isBlank();
    }

    /**
     * Submits what is due and asks after what was submitted. Safe to call on a timer: it does nothing at all
     * when reporting is switched off, and each report is handled on its own so one failure cannot stop the rest.
     */
    public QueueRun runQueue() {
        if (!isReady()) {
            return new QueueRun(0, 0, 0, 0);
        }
        NavSettings navSettings = settings.getNavSettings();
        NavClient client = clientFactory.apply(navSettings);
        int submitted = 0;
        int confirmed = 0;
        int failed = 0;
        int deferred = 0;
        // Reports sent on this pass are not asked after on the same pass: NAV has had no time to process
        // them, so the answer would be "still working" and the round trip would be spent for nothing.
        java.util.Set<Long> sentJustNow = new java.util.HashSet<>();

        for (NavReport report : reports.findDue(BusinessClock.now())) {
            try {
                submitOne(client, report);
                sentJustNow.add(report.id());
                submitted++;
            } catch (NavException ex) {
                if (ex.retryable() && report.attemptCount() + 1 < MAX_ATTEMPTS) {
                    defer(report, ex.getMessage());
                    deferred++;
                } else {
                    fail(report, ex.errorCode(), ex.getMessage());
                    failed++;
                }
            } catch (RuntimeException ex) {
                LOG.warn("Report {} could not be prepared: {}", report.id(), ex.getMessage());
                fail(report, null, ex.getMessage());
                failed++;
            }
        }

        for (NavReport report : reports.findByState(NavReportState.SUBMITTED)) {
            if (sentJustNow.contains(report.id())) {
                continue;
            }
            try {
                if (pollOne(client, report)) {
                    confirmed++;
                }
            } catch (NavException ex) {
                // A status query that fails leaves the report where it is. The transaction id is already
                // stored, so asking again later costs nothing and loses nothing.
                LOG.info("Status of transaction {} is not available yet: {}", report.transactionId(), ex.getMessage());
            }
        }
        return new QueueRun(submitted, confirmed, failed, deferred);
    }

    private void submitOne(NavClient client, NavReport report) {
        ReportableInvoice document = assemble(report);
        String xml = NavInvoiceXmlWriter.toXml(document);
        String token = client.tokenExchange();
        String transactionId = client.manageInvoice(token,
                List.of(new NavClient.Submission(report.operation(), xml)));
        LocalDateTime now = BusinessClock.now();
        db.runInTransaction(conn -> {
            NavReportLifecycle.require(report.state(), NavReportState.SUBMITTED);
            reports.markSubmitted(conn, report.id(), null, transactionId, 1, now);
            reports.addEvent(conn, report.id(), report.state(), NavReportState.SUBMITTED,
                    "Transaction " + transactionId, null, now);
        });
        LOG.info("Reported invoice {} to NAV as transaction {}", report.invoiceNumber(), transactionId);
    }

    /** @return true when the report reached a final state on this pass */
    private boolean pollOne(NavClient client, NavReport report) {
        List<NavResponses.ProcessingResult> results = client.queryTransactionStatus(report.transactionId());
        NavResponses.ProcessingResult mine = results.stream()
                .filter(result -> result.index() == report.invoiceIndex())
                .findFirst()
                .orElse(results.isEmpty() ? null : results.get(0));
        if (mine == null) {
            return false;
        }
        String status = mine.invoiceStatus() == null ? "" : mine.invoiceStatus().toUpperCase(Locale.ROOT);
        NavReportState target = switch (status) {
            case "DONE", "SAVED" -> mine.hasError() ? NavReportState.ERROR
                    : mine.hasWarning() ? NavReportState.WARN : NavReportState.DONE;
            case "ABORTED" -> NavReportState.ABORTED;
            // RECEIVED and PROCESSING mean NAV has it and is still working; there is nothing to decide yet.
            default -> null;
        };
        if (target == null) {
            return false;
        }
        LocalDateTime now = BusinessClock.now();
        db.runInTransaction(conn -> {
            NavReportLifecycle.require(report.state(), target);
            reports.markOutcome(conn, report.id(), target, firstCode(mine), mine.messageText(), now);
            reports.addEvent(conn, report.id(), report.state(), target, mine.messageText(), null, now);
        });
        if (target.needsAttention()) {
            alert(report, mine.messageText());
        }
        return true;
    }

    private static String firstCode(NavResponses.ProcessingResult result) {
        return result.messages().isEmpty() ? null : result.messages().get(0).code();
    }

    private void defer(NavReport report, String message) {
        // The wait doubles each time, so a short outage is ridden out quickly and a long one is not hammered.
        long minutes = (long) Math.pow(2, Math.min(report.attemptCount(), 5));
        LocalDateTime now = BusinessClock.now();
        db.runInTransaction(conn ->
                reports.scheduleRetry(conn, report.id(), message, now.plusMinutes(minutes), now));
        LOG.info("Report {} deferred for {} minute(s): {}", report.id(), minutes, message);
    }

    private void fail(NavReport report, String code, String message) {
        LocalDateTime now = BusinessClock.now();
        db.runInTransaction(conn -> {
            reports.markOutcome(conn, report.id(), NavReportState.ERROR, code, message, now);
            reports.addEvent(conn, report.id(), report.state(), NavReportState.ERROR, message, null, now);
        });
        alert(report, message);
    }

    /**
     * Tells the people who answer for the company's tax position that an invoice is not reported. This is the
     * alert path the obligation needs: a failure that only ever reaches a log file is a failure nobody acts on.
     */
    private void alert(NavReport report, String message) {
        try {
            notifications.notifyRoles(RoleCode.MANAGEMENT, "NAV",
                    "Invoice " + report.invoiceNumber() + " is not reported to NAV",
                    message == null || message.isBlank() ? "NAV rejected the report." : message,
                    "ADMIN");
        } catch (RuntimeException ex) {
            LOG.warn("Could not raise the NAV reporting alert: {}", ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- operator actions

    /** Puts a failed report back in the queue, by hand, after somebody has fixed what NAV complained about. */
    public void retry(long reportId, long userId) {
        NavReport report = reports.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("No such report: " + reportId));
        NavReportLifecycle.require(report.state(), NavReportState.PENDING);
        LocalDateTime now = BusinessClock.now();
        db.runInTransaction(conn -> {
            reports.scheduleRetry(conn, reportId, null, now, now);
            reports.addEvent(conn, reportId, report.state(), NavReportState.PENDING, "Retried by hand", userId, now);
        });
    }

    /**
     * Records that a technical annulment has been asked for.
     *
     * <p>This application cannot complete one. Annulment says an earlier report was wrong, and NAV requires a
     * human to approve that in its own web interface before it takes effect — so what happens here is the
     * request and the evidence of it, and the state says plainly that the matter is not closed.
     */
    public void requestAnnulment(long reportId, String reason, long userId) {
        NavReport report = reports.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("No such report: " + reportId));
        NavReportLifecycle.require(report.state(), NavReportState.ANNUL_REQUESTED);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An annulment needs a reason; NAV asks for one.");
        }
        LocalDateTime now = BusinessClock.now();
        db.runInTransaction(conn -> {
            reports.markOutcome(conn, reportId, NavReportState.ANNUL_REQUESTED, null, reason, now);
            reports.addEvent(conn, reportId, report.state(), NavReportState.ANNUL_REQUESTED, reason, userId, now);
        });
        LOG.info("Annulment requested for invoice {} by user {}", report.invoiceNumber(), userId);
    }

    public List<NavReport> all() {
        return reports.findAll();
    }

    public NavReportDao dao() {
        return reports;
    }

    // ---------------------------------------------------------------- assembling the document

    /** Builds the reportable document for a queued row, from the invoice as it stands now. */
    public ReportableInvoice assemble(NavReport report) {
        return db.executeInTransaction(conn -> {
            Invoice invoice = invoices.findById(conn, report.invoiceId())
                    .orElseThrow(() -> new NavException("The invoice has gone", false));
            List<InvoiceItem> items = invoices.findItemsByInvoiceId(conn, invoice.id());
            CompanyProfile company = settings.getCompanyProfile();
            NavSettings navSettings = settings.getNavSettings();
            Exemption exemption = exemptionFor(conn, invoice.effectiveTaxTypeCode());
            ReportableInvoice.Reference reference = null;
            if (report.operation() == NavOperation.STORNO || report.operation() == NavOperation.MODIFY) {
                Long originalId = invoices.findCreditForInvoiceId(conn, invoice.id());
                Invoice original = originalId == null ? null : invoices.findById(conn, originalId).orElse(null);
                if (original == null) {
                    throw new NavException("A cancellation must name the invoice it cancels, and this one"
                            + " has no link to an original.", false);
                }
                reference = new ReportableInvoice.Reference(original.invoiceNumber(),
                        reports.countAmendments(conn, originalId));
            }
            return build(invoice, items, company, navSettings, exemption, report.operation(), reference);
        });
    }

    private static ReportableInvoice build(Invoice invoice, List<InvoiceItem> items, CompanyProfile company,
                                           NavSettings navSettings, Exemption exemption,
                                           NavOperation operation, ReportableInvoice.Reference reference) {
        BigDecimal rate = exchangeRate(invoice);
        List<ReportableInvoice.Line> lines = new ArrayList<>();
        int number = 1;
        for (InvoiceItem item : items) {
            BigDecimal net = item.lineNet();
            BigDecimal vat = net.multiply(invoice.vatRate()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            lines.add(new ReportableInvoice.Line(
                    number++, item.description(), item.quantity(), unitOfMeasure(item.unit()), item.unitPrice(),
                    invoice.vatRate(), exemption.code(), exemption.reason(),
                    amounts(net, vat, rate)));
        }
        ReportableInvoice.Amounts summary =
                amounts(invoice.netAmount(), invoice.vatAmount(), rate);
        return new ReportableInvoice(
                operation,
                invoice.invoiceNumber(),
                invoice.issueDate(),
                invoice.issueDate(),
                invoice.paidDate(),
                supplier(company, navSettings),
                customer(invoice),
                vatStatus(invoice),
                invoice.effectiveCurrencyCode(),
                rate,
                lines,
                summary,
                reference);
    }

    private static ReportableInvoice.Amounts amounts(BigDecimal net, BigDecimal vat, BigDecimal rate) {
        BigDecimal gross = net.add(vat);
        return new ReportableInvoice.Amounts(
                net, huf(net, rate), vat, huf(vat, rate), gross, huf(gross, rate));
    }

    /** The forint equivalent the report must carry, whatever the invoice was written in. */
    private static BigDecimal huf(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal exchangeRate(Invoice invoice) {
        if ("HUF".equalsIgnoreCase(invoice.effectiveCurrencyCode())) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = invoice.exchangeRate();
        if (rate == null || rate.signum() <= 0) {
            throw new NavException("Invoice " + invoice.invoiceNumber() + " is in "
                    + invoice.effectiveCurrencyCode() + " but carries no exchange rate, and the report must"
                    + " state the forint amounts.", false);
        }
        return rate;
    }

    private static ReportableInvoice.Party supplier(CompanyProfile company, NavSettings navSettings) {
        CompanyProfile.Address address = company.addressParts();
        return new ReportableInvoice.Party(
                company.name(),
                navSettings.value(NavSettings.Field.TAX_NUMBER),
                navSettings.value(NavSettings.Field.VAT_CODE),
                navSettings.value(NavSettings.Field.COUNTY_CODE),
                "HU",
                address.postalCode(),
                address.city(),
                address.street());
    }

    private static ReportableInvoice.Party customer(Invoice invoice) {
        String tax = invoice.partnerTaxNumber();
        String core = tax == null ? null : tax.replaceAll("[^0-9]", "");
        // A Hungarian tax number is eight digits, a VAT code and a county code; anything else is reported
        // as it stands, because a foreign number has no such parts to split.
        boolean hungarian = core != null && core.length() >= 8 && isHungarian(tax);
        return new ReportableInvoice.Party(
                invoice.partnerName(),
                hungarian ? core.substring(0, 8) : tax,
                hungarian && core.length() >= 9 ? core.substring(8, 9) : null,
                hungarian && core.length() >= 11 ? core.substring(9, 11) : null,
                hungarian ? "HU" : "",
                "",
                invoice.partnerAddress(),
                invoice.partnerAddress());
    }

    private static boolean isHungarian(String taxNumber) {
        String upper = taxNumber.trim().toUpperCase(Locale.ROOT);
        return !upper.startsWith("HU") || upper.replaceAll("[^0-9]", "").length() >= 8;
    }

    private static ReportableInvoice.CustomerVatStatus vatStatus(Invoice invoice) {
        String tax = invoice.partnerTaxNumber();
        if (tax == null || tax.isBlank()) {
            return ReportableInvoice.CustomerVatStatus.PRIVATE_PERSON;
        }
        String upper = tax.trim().toUpperCase(Locale.ROOT);
        boolean domestic = upper.startsWith("HU") || upper.replaceAll("[^0-9]", "").length() == 11
                || upper.matches("\\d{8}-\\d-\\d{2}");
        return domestic ? ReportableInvoice.CustomerVatStatus.DOMESTIC
                : ReportableInvoice.CustomerVatStatus.OTHER;
    }

    private static String unitOfMeasure(String unit) {
        if (unit == null) {
            return "OWN";
        }
        return switch (unit.trim().toLowerCase(Locale.ROOT)) {
            case "kg" -> "KILOGRAM";
            case "db", "pcs", "piece" -> "PIECE";
            default -> "OWN";
        };
    }

    /** The NAV exemption code configured against a tax type, if any. */
    private record Exemption(String code, String reason) { }

    private Exemption exemptionFor(Connection conn, String taxCode) throws SQLException {
        String sql = "SELECT nav_vat_exemption_case, nav_vat_exemption_reason FROM tax_types"
                + " WHERE upper(code) = upper(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taxCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Exemption(rs.getString(1), rs.getString(2)) : new Exemption(null, null);
            }
        }
    }
}
