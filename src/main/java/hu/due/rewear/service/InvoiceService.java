package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.CustomerDao;
import hu.due.rewear.dao.FinanceConfigDao;
import hu.due.rewear.dao.InvoiceDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CompanyProfile;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.FinancialSummary;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceEvent;
import hu.due.rewear.model.InvoiceItem;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.model.PartnerType;
import hu.due.rewear.model.TaxType;
import hu.due.rewear.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

/**
 * Manages multi-currency Outgoing (revenue) and Incoming (expense) invoices with country-aware
 * configurable VAT/Tax types and strictly separated per-currency financial summaries (no forced FX conversion).
 */
public final class InvoiceService {

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceService.class);
    public static final BigDecimal DEFAULT_VAT_RATE = new BigDecimal("27.00");

    /** Immutable line item input for multi-line manual invoice creation. */
    public record InvoiceLineInput(String description, BigDecimal quantity, String unit, BigDecimal unitPrice) {
    }

    public record ReviewedOrder(Order order, Customer customer, List<OrderItem> items) {
        public ReviewedOrder {
            items = List.copyOf(items);
        }
    }

    public static final class OrderChangedException extends IllegalStateException {
        public OrderChangedException() {
            super("The order changed while the invoice was open.");
        }
    }

    public record ManualPartner(Long id, String name, String taxNumber, String address, String email,
                                String country, boolean allowBoth) { }

    public record ManualEntry(InvoiceDirection direction, InvoiceCategory category, String invoiceNumber,
                              ManualPartner partner, List<InvoiceLineInput> lines, BigDecimal totalNet,
                              TaxType tax, String currency, LocalDate issueDate, LocalDate dueDate,
                              InvoiceStatus status, long userId, Path attachment) { }

    private final DatabaseManager db;
    private final InvoiceDao invoiceDao;
    private final OrderDao orderDao;
    private final OrderItemDao orderItemDao;
    private final CustomerDao customerDao;
    private final FinanceConfigDao financeConfigDao;
    private final InvoiceArchiveService archive;
    private final Access access;

    /**
     * Decides whether a user may carry out money actions on invoices. The application wires this to the
     * Invoices module of the role matrix, so the service enforces exactly what the screen shows rather
     * than trusting that only the right people can open it.
     */
    @FunctionalInterface
    public interface Access {
        boolean mayManage(long userId);
    }

    public InvoiceService(DatabaseManager db,
                          InvoiceDao invoiceDao,
                          OrderDao orderDao,
                          OrderItemDao orderItemDao,
                          CustomerDao customerDao,
                          FinanceConfigDao financeConfigDao,
                          InvoiceArchiveService archive,
                          Access access) {
        this.db = db;
        this.invoiceDao = invoiceDao;
        this.orderDao = orderDao;
        this.orderItemDao = orderItemDao;
        this.customerDao = customerDao;
        this.financeConfigDao = financeConfigDao;
        this.archive = archive;
        this.access = java.util.Objects.requireNonNull(access, "access");
    }

    /** Whether this user may change invoice statuses and issue credit notes. */
    public boolean mayManage(long userId) {
        return access.mayManage(userId);
    }

    private void requireAccess(long userId) {
        if (!access.mayManage(userId)) {
            throw new SecurityException("You do not have access to invoice actions.");
        }
    }

    /**
     * Resolves the automatic tax type for a partner's country:
     * Hungary (HU) -> HU-27 (27%), other EU countries (AT, SK, DE, ...) -> EU-0 (0%), Non-EU -> EXPORT-0 (0%).
     */
    public TaxType resolveDefaultTaxForCustomer(Customer customer) {
        String country = customer != null ? customer.effectiveCountryCode() : "HU";
        return financeConfigDao.resolveDefaultTaxTypeForCountry(country);
    }

    /** Auto-resolves tax type from the order customer's country. */
    public Invoice ensureInvoiceForOrder(long orderId, long userId) {
        return ensureInvoiceForOrder(orderId, (TaxType) null, userId);
    }

    /** Backwards-compatible overload accepting an explicit VAT rate. */
    public Invoice ensureInvoiceForOrder(long orderId, BigDecimal vatRate, long userId) {
        if (vatRate == null) {
            return ensureInvoiceForOrder(orderId, (TaxType) null, userId);
        }
        String code = vatRate.compareTo(BigDecimal.ZERO) == 0 ? "EU-0" : "HU-27";
        TaxType matched = financeConfigDao.findTaxTypes(false).stream()
                .filter(t -> t.ratePercent().compareTo(vatRate) == 0)
                .findFirst()
                .orElse(new TaxType(0, code, code, code, vatRate, "ANY", true));
        return ensureInvoiceForOrder(orderId, matched, userId);
    }

    /**
     * Returns the existing outgoing invoice for an order (refreshing its lines, currency and tax type while in DRAFT),
     * or creates a new Outgoing invoice populated from the order and customer data in the order's currency.
     */
    public Invoice ensureInvoiceForOrder(long orderId, TaxType chosenTax, long userId) {
        return saveReviewedOrder(reviewOrder(orderId), chosenTax, userId);
    }

    public ReviewedOrder reviewOrder(long orderId) {
        return db.executeInTransaction(conn -> reviewOrder(conn, orderId));
    }

    private ReviewedOrder reviewOrder(Connection conn, long orderId) throws SQLException {
        Order order = orderDao.findById(conn, orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        Customer customer = customerDao.findById(conn, order.customerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + order.customerId()));
        return new ReviewedOrder(order, customer, orderItemDao.findByOrderId(conn, orderId));
    }

    public Invoice saveReviewedOrder(ReviewedOrder reviewed, TaxType chosenTax, long userId) {
        Order order = reviewed.order();
        Customer customer = reviewed.customer();
        List<OrderItem> orderItems = reviewed.items();
        Invoice prior = invoiceDao.findByOrderId(order.id()).orElse(null);
        TaxType effectiveTax = chosenTax != null ? chosenTax
                : prior != null ? financeConfigDao.findTaxTypeByCode(prior.effectiveTaxTypeCode())
                        .orElseGet(() -> resolveDefaultTaxForCustomer(customer))
                        : resolveDefaultTaxForCustomer(customer);
        BigDecimal vatRate = effectiveTax.ratePercent();
        String taxCode = effectiveTax.code();
        String cur = order.effectiveCurrencyCode();
        int decimals = financeConfigDao.decimalPlacesFor(cur);

        BigDecimal netTotal = CurrencyDef.roundForScale(orderItems.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add), decimals);
        BigDecimal vatAmount = CurrencyDef.roundForScale(netTotal.multiply(vatRate)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP), decimals);
        BigDecimal grossAmount = CurrencyDef.roundForScale(netTotal.add(vatAmount), decimals);

        LocalDate today = BusinessClock.today();
        LocalDate dueDate = today.plusDays(14);
        LocalDateTime now = BusinessClock.now();

        return saveWithDocuments(conn -> {
            Invoice existing = invoiceDao.findByOrderId(conn, order.id()).orElse(null);
            if (existing != null && existing.status() != InvoiceStatus.DRAFT) return existing.id();
            if (!reviewed.equals(reviewOrder(conn, order.id()))) throw new OrderChangedException();
            if (order.deliveryDate() != null && order.status() != OrderStatus.SHIPPED) {
                throw new IllegalStateException("Complete the verified order handover before invoicing.");
            }
            if (order.status() == OrderStatus.CANCELLED || orderItems.isEmpty()) {
                throw new IllegalArgumentException("An active order with at least one line is required.");
            }
            if (existing != null) {
                invoiceDao.deleteItemsByInvoiceId(conn, existing.id());
                insertOrderLines(conn, existing.id(), orderItems, decimals);
                invoiceDao.updateAmounts(conn, existing.id(), netTotal, vatRate, taxCode, vatAmount, grossAmount,
                        cur, CurrencyCode.DEFAULT_EUR_HUF_RATE);
                return existing.id();
            }
            String invNo = invoiceDao.generateNextInvoiceNumber(conn, InvoiceDirection.OUTGOING);
            long id = invoiceDao.insert(conn, invNo, InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES,
                    order.id(), customer.id(), customer.name(), customer.taxNumber(), customer.address(),
                    customer.email(), InvoiceStatus.ISSUED, today, dueDate, null,
                    netTotal, vatRate, taxCode, vatAmount, grossAmount, cur,
                    CurrencyCode.DEFAULT_EUR_HUF_RATE, userId, now);
            insertOrderLines(conn, id, orderItems, decimals);
            return id;
        }, true, null, null);
    }

    private void insertOrderLines(Connection conn, long id, List<OrderItem> items, int decimals) throws SQLException {
        for (OrderItem item : items) {
            String desc = item.packageQrCode() + " (" + item.summaryEn() + ")";
            invoiceDao.insertItem(conn, id, desc, item.weightKg(), "kg",
                    CurrencyDef.roundForScale(item.pricePerKg(), decimals),
                    CurrencyDef.roundForScale(item.lineTotal(), decimals));
        }
    }

    public Invoice createManualInvoice(InvoiceDirection direction,
                                       InvoiceCategory category,
                                       String optionalInvoiceNumber,
                                       String partnerName,
                                       String partnerTaxNumber,
                                       String partnerAddress,
                                       String partnerEmail,
                                       String itemDescription,
                                       BigDecimal quantity,
                                       String unit,
                                       BigDecimal unitPrice,
                                       BigDecimal vatRate,
                                       LocalDate issueDate,
                                       LocalDate dueDate,
                                       InvoiceStatus status,
                                       long userId) {
        return createManualInvoice(direction, category, optionalInvoiceNumber, null, partnerName,
                partnerTaxNumber, partnerAddress, partnerEmail, itemDescription, quantity, unit,
                unitPrice, vatRate, vatRate != null && vatRate.compareTo(BigDecimal.ZERO) == 0 ? "EU-0" : "HU-27",
                "EUR", issueDate, dueDate, status, userId);
    }

    public Invoice createManualInvoice(InvoiceDirection direction,
                                       InvoiceCategory category,
                                       String optionalInvoiceNumber,
                                       Long partnerId,
                                       String partnerName,
                                       String partnerTaxNumber,
                                       String partnerAddress,
                                       String partnerEmail,
                                       String itemDescription,
                                       BigDecimal quantity,
                                       String unit,
                                       BigDecimal unitPrice,
                                       TaxType taxType,
                                       String currencyCode,
                                       LocalDate issueDate,
                                       LocalDate dueDate,
                                       InvoiceStatus status,
                                       long userId) {
        BigDecimal vatRate = taxType != null ? taxType.ratePercent() : DEFAULT_VAT_RATE;
        String taxCode = taxType != null ? taxType.code() : "HU-27";
        return createManualInvoice(direction, category, optionalInvoiceNumber, partnerId, partnerName,
                partnerTaxNumber, partnerAddress, partnerEmail, itemDescription, quantity, unit,
                unitPrice, vatRate, taxCode, currencyCode, issueDate, dueDate, status, userId);
    }

    public Invoice createManualInvoice(InvoiceDirection direction,
                                       InvoiceCategory category,
                                       String optionalInvoiceNumber,
                                       Long partnerId,
                                       String partnerName,
                                       String partnerTaxNumber,
                                       String partnerAddress,
                                       String partnerEmail,
                                       String itemDescription,
                                       BigDecimal quantity,
                                       String unit,
                                       BigDecimal unitPrice,
                                       BigDecimal vatRate,
                                       String taxTypeCode,
                                       String currencyCode,
                                       LocalDate issueDate,
                                       LocalDate dueDate,
                                       InvoiceStatus status,
                                       long userId) {
        String desc = (itemDescription != null && !itemDescription.isBlank())
                ? itemDescription.trim() : category.name();
        return createMultiLineManualInvoice(direction, category, optionalInvoiceNumber, partnerId,
                partnerName, partnerTaxNumber, partnerAddress, partnerEmail,
                List.of(new InvoiceLineInput(desc, quantity, unit, unitPrice)),
                vatRate, taxTypeCode, currencyCode, issueDate, dueDate, status, userId);
    }

    /**
     * Atomically creates a manual invoice with one or more line items (e.g. product bales + freight + pallet fees),
     * computing native-currency Net, VAT, and Gross totals from the rounded sum of all lines.
     */
    public Invoice createMultiLineManualInvoice(InvoiceDirection direction,
                                                InvoiceCategory category,
                                                String optionalInvoiceNumber,
                                                Long partnerId,
                                                String partnerName,
                                                String partnerTaxNumber,
                                                String partnerAddress,
                                                String partnerEmail,
                                                List<InvoiceLineInput> lines,
                                                TaxType taxType,
                                                String currencyCode,
                                                LocalDate issueDate,
                                                LocalDate dueDate,
                                                InvoiceStatus status,
                                                long userId) {
        BigDecimal vatRate = taxType != null ? taxType.ratePercent() : DEFAULT_VAT_RATE;
        String taxCode = taxType != null ? taxType.code() : "HU-27";
        return createMultiLineManualInvoice(direction, category, optionalInvoiceNumber, partnerId,
                partnerName, partnerTaxNumber, partnerAddress, partnerEmail, lines,
                vatRate, taxCode, currencyCode, issueDate, dueDate, status, userId);
    }

    public Invoice createMultiLineManualInvoice(InvoiceDirection direction,
                                                InvoiceCategory category,
                                                String optionalInvoiceNumber,
                                                Long partnerId,
                                                String partnerName,
                                                String partnerTaxNumber,
                                                String partnerAddress,
                                                String partnerEmail,
                                                List<InvoiceLineInput> lines,
                                                BigDecimal vatRate,
                                                String taxTypeCode,
                                                String currencyCode,
                                                LocalDate issueDate,
                                                LocalDate dueDate,
                                                InvoiceStatus status,
                                                long userId) {
        return saveManualInvoice(direction, category, optionalInvoiceNumber,
                new ManualPartner(partnerId, partnerName, partnerTaxNumber, partnerAddress, partnerEmail, null, false),
                lines, vatRate, taxTypeCode, currencyCode, issueDate, dueDate, status, userId, false, null, null);
    }

    public Invoice saveManualEntry(ManualEntry entry, ReceiptVaultService receipts) throws IOException {
        if (entry.tax() == null) throw new IllegalArgumentException("Choose a VAT type.");
        List<InvoiceLineInput> lines = entry.lines() == null ? List.of() : entry.lines();
        if (lines.isEmpty()) {
            if (entry.totalNet() == null || entry.totalNet().signum() < 0) {
                throw new IllegalArgumentException("Enter a non-negative net total or add invoice lines.");
            }
            lines = List.of(new InvoiceLineInput(entry.category().name(), BigDecimal.ONE, "db", entry.totalNet()));
        }
        try {
            return saveManualInvoice(entry.direction(), entry.category(), entry.invoiceNumber(), entry.partner(), lines,
                    entry.tax().ratePercent(), entry.tax().code(), entry.currency(), entry.issueDate(), entry.dueDate(),
                    entry.status(), entry.userId(), true, receipts, entry.attachment());
        } catch (DocumentWriteException ex) {
            throw (IOException) ex.getCause();
        }
    }

    private Invoice saveManualInvoice(InvoiceDirection direction, InvoiceCategory category, String optionalInvoiceNumber,
                                   ManualPartner partner, List<InvoiceLineInput> lines, BigDecimal vatRate,
                                   String taxTypeCode, String currencyCode, LocalDate issueDate, LocalDate dueDate,
                                   InvoiceStatus status, long userId, boolean savePartner,
                                   ReceiptVaultService receipts, Path attachment) {
        String partnerName = partner.name();
        if (partnerName == null || partnerName.isBlank()) {
            throw new IllegalArgumentException("Partner name is required.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("At least one invoice line item is required.");
        }
        String cur = (currencyCode != null && !currencyCode.isBlank()) ? currencyCode.trim().toUpperCase(Locale.ROOT) : "EUR";
        int decimals = financeConfigDao.decimalPlacesFor(cur);
        BigDecimal effectiveVat = vatRate != null && vatRate.compareTo(BigDecimal.ZERO) >= 0
                ? vatRate.setScale(2, RoundingMode.HALF_UP) : DEFAULT_VAT_RATE;
        String effectiveTaxCode = (taxTypeCode != null && !taxTypeCode.isBlank())
                ? taxTypeCode.trim().toUpperCase(Locale.ROOT)
                : (effectiveVat.compareTo(BigDecimal.ZERO) == 0 ? "EU-0" : "HU-27");

        record ComputedLine(String desc, BigDecimal qty, String unit, BigDecimal unitPrice, BigDecimal lineNet) {}
        java.util.List<ComputedLine> computed = new java.util.ArrayList<>(lines.size());
        BigDecimal net = BigDecimal.ZERO;
        for (InvoiceLineInput line : lines) {
            if (line == null || line.quantity() == null || line.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Quantity must be positive.");
            }
            if (line.unitPrice() == null || line.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Unit price must be >= 0.");
            }
            String desc = (line.description() != null && !line.description().isBlank())
                    ? line.description().trim() : category.name();
            String unit = (line.unit() != null && !line.unit().isBlank()) ? line.unit().trim() : "db";
            BigDecimal rUnit = CurrencyDef.roundForScale(line.unitPrice(), decimals);
            BigDecimal lineNet = CurrencyDef.roundForScale(line.quantity().multiply(rUnit), decimals);
            net = net.add(lineNet);
            computed.add(new ComputedLine(desc, line.quantity(), unit, rUnit, lineNet));
        }
        net = CurrencyDef.roundForScale(net, decimals);
        BigDecimal vat = CurrencyDef.roundForScale(
                net.multiply(effectiveVat).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP), decimals);
        BigDecimal gross = CurrencyDef.roundForScale(net.add(vat), decimals);

        LocalDate effIssue = issueDate != null ? issueDate : BusinessClock.today();
        LocalDate effDue = dueDate != null ? dueDate : effIssue.plusDays(14);
        InvoiceStatus effStatus = status != null ? status : InvoiceStatus.ISSUED;
        LocalDate paidDate = effStatus == InvoiceStatus.PAID ? effIssue : null;

        final BigDecimal finalNet = net;
        return saveWithDocuments(conn -> {
            Long partnerId = partner.id();
            if (savePartner) partnerId = linkManualPartner(conn, partner, direction, cur);
            String invNo = (optionalInvoiceNumber != null && !optionalInvoiceNumber.isBlank())
                    ? optionalInvoiceNumber.trim()
                    : invoiceDao.generateNextInvoiceNumber(conn, direction);
            long id = invoiceDao.insert(conn, invNo, direction, category, null, partnerId,
                    partnerName.trim(), partner.taxNumber(), partner.address(), partner.email(),
                    effStatus, effIssue, effDue, paidDate, finalNet, effectiveVat, effectiveTaxCode, vat, gross,
                    cur, CurrencyCode.DEFAULT_EUR_HUF_RATE, userId, BusinessClock.now());
            for (ComputedLine cl : computed) {
                invoiceDao.insertItem(conn, id, cl.desc(), cl.qty(), cl.unit(), cl.unitPrice(), cl.lineNet());
            }
            return id;
        }, false, receipts, attachment);
    }

    private static final class DocumentWriteException extends IllegalStateException {
        DocumentWriteException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private static final class StoredDocuments {
        String pdf;
        String previousPdf;
        String receipt;
    }

    private Invoice saveWithDocuments(DatabaseManager.SqlFunction<Long> save, boolean refreshDraft,
                                      ReceiptVaultService receipts, Path attachment) {
        return saveWithDocuments(save, refreshDraft, receipts, attachment, false);
    }

    private Invoice saveWithDocuments(DatabaseManager.SqlFunction<Long> save, boolean refreshDraft,
                                      ReceiptVaultService receipts, Path attachment, boolean rewritePdf) {
        var files = new StoredDocuments();
        Invoice result;
        try {
            result = db.executeInTransaction(conn -> {
                long id = save.apply(conn);
                Invoice invoice = invoiceDao.findById(conn, id).orElseThrow();
                try {
                    if (attachment != null) {
                        files.receipt = receipts.storeReceipt(invoice, attachment);
                        if (!invoiceDao.replaceAttachmentPath(conn, id, null, files.receipt)) {
                            throw new IllegalStateException("Could not link invoice receipt.");
                        }
                    }
                    String previous = invoiceDao.findArchivedPdfPath(conn, id);
                    if (archive.resolve(previous).isEmpty() || rewritePdf
                            || (refreshDraft && invoice.status() == InvoiceStatus.DRAFT)) {
                        files.pdf = archive.store(invoice, invoiceDao.findItemsByInvoiceId(conn, id));
                        invoiceDao.setArchivedPdfPath(conn, id, files.pdf);
                        files.previousPdf = previous;
                    }
                } catch (IOException ex) {
                    throw new DocumentWriteException(ex);
                }
                return files.receipt == null ? invoice : invoiceDao.findById(conn, id).orElseThrow();
            });
        } catch (RuntimeException ex) {
            archive.delete(files.pdf);
            if (receipts != null) receipts.deleteStoredReceipt(files.receipt);
            throw ex;
        }
        archive.delete(files.previousPdf);
        return result;
    }

    private long linkManualPartner(Connection conn, ManualPartner partner, InvoiceDirection direction,
                                   String currency) throws SQLException {
        PartnerType needed = direction == InvoiceDirection.OUTGOING ? PartnerType.CUSTOMER : PartnerType.SUPPLIER;
        if (partner.id() == null) {
            if (!customerDao.findByTaxNumber(conn, partner.taxNumber()).isEmpty()) {
                throw new IllegalArgumentException("A partner already uses this tax number. Select that partner first.");
            }
            return customerDao.insert(conn, partner.name(), needed, null, partner.email(), null, partner.address(),
                    partner.taxNumber(), partner.country(), currency, BusinessClock.now());
        }
        Customer existing = customerDao.findById(conn, partner.id())
                .filter(Customer::active).orElseThrow(() -> new IllegalArgumentException("Select an active partner."));
        if (existing.partnerType() != needed && existing.partnerType() != PartnerType.BOTH) {
            if (!partner.allowBoth()) throw new IllegalArgumentException("Confirm the additional partner role.");
            customerDao.enableBothRoles(conn, existing.id());
        }
        return existing.id();
    }

    /**
     * Issues an auditable Credit Note ({@code CN-YYYY-NNNN} / Sztornó számla) reversing {@code originalInvoiceId}.
     * <ul>
     *   <li>If the original invoice was already {@code PAID}, it stays {@code PAID} for cashflow audit history and
     *       the credit note is booked in the opposite cashflow direction ({@code PAID}) so the currency ledger nets to zero.</li>
     *   <li>If the original invoice was unpaid ({@code DRAFT}/{@code ISSUED}/{@code OVERDUE}), the original is marked
     *       {@code CANCELLED} and the credit note is stored as {@code CANCELLED} (printable/emailable without distorting P&amp;L).</li>
     * </ul>
     */
    public Invoice issueCreditNote(long originalInvoiceId, long userId) {
        requireAccess(userId);
        Invoice created = saveWithDocuments(conn -> {
            Invoice orig = invoiceDao.findById(conn, originalInvoiceId)
                    .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + originalInvoiceId));
            if (orig.category() == InvoiceCategory.CREDIT_NOTE) {
                throw new IllegalStateException("Cannot issue a credit note for an existing credit note.");
            }
            if (orig.status() == InvoiceStatus.CANCELLED) {
                throw new IllegalStateException("Invoice " + orig.invoiceNumber() + " is already cancelled.");
            }
            String refPrefix = creditNoteReference(orig);
            if (invoiceDao.hasCreditNoteFor(conn, originalInvoiceId, refPrefix)) {
                throw new IllegalStateException("Invoice " + orig.invoiceNumber() + " already has a credit note.");
            }
            List<InvoiceItem> origItems = invoiceDao.findItemsByInvoiceId(conn, originalInvoiceId);
            boolean wasPaid = orig.status() == InvoiceStatus.PAID;
            InvoiceDirection cnDir = wasPaid
                    ? (orig.direction() == InvoiceDirection.OUTGOING ? InvoiceDirection.INCOMING : InvoiceDirection.OUTGOING)
                    : orig.direction();
            InvoiceStatus cnStatus = wasPaid ? InvoiceStatus.PAID : InvoiceStatus.CANCELLED;
            LocalDate today = BusinessClock.today();
            LocalDate paidDate = wasPaid ? today : null;
            if (!wasPaid) {
                invoiceDao.updateStatus(conn, originalInvoiceId, InvoiceStatus.CANCELLED, null);
            }
            String cnNo = invoiceDao.generateNextCreditNoteNumber(conn);
            long id = invoiceDao.insert(conn, cnNo, cnDir, InvoiceCategory.CREDIT_NOTE,
                    orig.orderId(), orig.customerId(),
                    orig.partnerName(), orig.partnerTaxNumber(), orig.partnerAddress(), orig.partnerEmail(),
                    cnStatus, today, today, paidDate,
                    orig.netAmount(), orig.vatRate(), orig.effectiveTaxTypeCode(),
                    orig.vatAmount(), orig.grossAmount(), orig.effectiveCurrencyCode(),
                    orig.exchangeRate(), userId, BusinessClock.now());
            invoiceDao.setCreditFor(conn, id, originalInvoiceId);
            invoiceDao.insertEvent(conn, originalInvoiceId, "CREDIT_NOTE_ISSUED", orig.status(),
                    wasPaid ? orig.status() : InvoiceStatus.CANCELLED, cnNo, userId, BusinessClock.now());
            invoiceDao.insertEvent(conn, id, "CREDIT_NOTE_FOR", null, cnStatus, orig.invoiceNumber(), userId,
                    BusinessClock.now());
            if (origItems.isEmpty()) {
                invoiceDao.insertItem(conn, id, refPrefix + orig.category().name(),
                        BigDecimal.ONE, "db", orig.netAmount(), orig.netAmount());
            } else {
                for (InvoiceItem item : origItems) {
                    invoiceDao.insertItem(conn, id, refPrefix + item.description(),
                            item.quantity(), item.unit(), item.unitPrice(), item.lineNet());
                }
            }
            return id;
        }, false, null, null);
        LOG.info("Issued credit note {} reversing invoice id={}", created.invoiceNumber(), originalInvoiceId);
        return created;
    }

    /**
     * Moves an invoice along its lifecycle (see {@link InvoiceLifecycle}) and records who did it.
     * <p>
     * Issuing a draft sets its issue date to today and keeps its payment terms (the number of days between
     * issue and due date), then refreshes the archived PDF so the stored document shows the real issue date.
     */
    public Invoice changeStatus(long invoiceId, InvoiceStatus targetStatus, long userId) {
        requireAccess(userId);
        // Only a draft's stored PDF is rewritten (to carry its real issue date); an issued invoice's archive is never touched.
        boolean wasDraft = invoiceDao.findById(invoiceId).map(i -> i.status() == InvoiceStatus.DRAFT).orElse(false);
        Invoice changed = saveWithDocuments(conn -> {
            Invoice existing = invoiceDao.findById(conn, invoiceId)
                    .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));
            boolean reversed = invoiceDao.hasCreditNoteFor(conn, invoiceId, creditNoteReference(existing));
            InvoiceLifecycle.require(existing, targetStatus, reversed);

            LocalDate today = BusinessClock.today();
            LocalDate paidDate = targetStatus == InvoiceStatus.PAID
                    ? (existing.paidDate() != null ? existing.paidDate() : today)
                    : null;
            String event = targetStatus == InvoiceStatus.PAID ? "PAID"
                    : targetStatus == InvoiceStatus.ISSUED && existing.status() == InvoiceStatus.PAID ? "PAYMENT_WITHDRAWN"
                    : "ISSUED";
            if (existing.status() == InvoiceStatus.DRAFT) {
                long termDays = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(existing.issueDate(), existing.dueDate()));
                invoiceDao.updateIssueDates(conn, invoiceId, today, today.plusDays(termDays));
            }
            invoiceDao.updateStatus(conn, invoiceId, targetStatus, paidDate);
            invoiceDao.insertEvent(conn, invoiceId, event, existing.status(), targetStatus, null, userId,
                    BusinessClock.now());
            return invoiceId;
        }, false, null, null, wasDraft);
        LOG.info("Invoice {} status changed to {} by user {}", changed.invoiceNumber(), targetStatus, userId);
        return changed;
    }

    /** The invoice's lifecycle events, oldest first. */
    public List<InvoiceEvent> history(long invoiceId) {
        return invoiceDao.findEvents(invoiceId);
    }

    /** Statuses the invoice may be moved to by hand right now, current one excluded. */
    public Set<InvoiceStatus> allowedStatusChanges(Invoice invoice) {
        boolean reversed = db.executeInTransaction(conn ->
                invoiceDao.hasCreditNoteFor(conn, invoice.id(), creditNoteReference(invoice)));
        return InvoiceLifecycle.allowedTargets(invoice, reversed);
    }

    private static String creditNoteReference(Invoice original) {
        return "CREDIT NOTE [Ref: " + original.invoiceNumber() + "] — ";
    }

    /** Automatically flags all past-due {@code ISSUED} invoices as {@code OVERDUE} relative to today. */
    public int syncOverdueStatuses() {
        return syncOverdueStatuses(BusinessClock.today());
    }

    /** Deterministic overload flagging {@code ISSUED} invoices with {@code due_date < asOfDate} as {@code OVERDUE}. */
    public int syncOverdueStatuses(LocalDate asOfDate) {
        int updated = invoiceDao.markPastDueAsOverdue(asOfDate);
        if (updated > 0) {
            LOG.info("Marked {} past-due invoice(s) as OVERDUE (as of {})", updated, asOfDate);
        }
        return updated;
    }

    /**
     * Builds an RFC-6068 {@code mailto:} URI pre-filled with a localized payment reminder containing
     * the invoice number, native-currency gross amount, due date, and the seller's matching currency IBAN.
     */
    public String buildPaymentReminderMailtoUri(Invoice inv, CompanyProfile company, I18nManager i18n) {
        String cur = inv.effectiveCurrencyCode();
        int dec = financeConfigDao.decimalPlacesFor(cur);
        String amountStr = CurrencyDef.formatAmount(inv.grossAmount(), cur, dec);
        CompanyProfile effectiveCompany = company != null ? company : CompanyProfile.defaults();
        String iban = effectiveCompany.ibanForCurrency(cur);
        String recipient = inv.partnerEmail() != null ? inv.partnerEmail().trim() : "";

        String subject = i18n.t("invoice.reminder.subject", inv.invoiceNumber(), amountStr);
        String body = i18n.t(iban.isBlank() ? "invoice.reminder.body_no_iban" : "invoice.reminder.body",
                inv.partnerName(),
                inv.invoiceNumber(),
                amountStr,
                inv.dueDate().toString(),
                cur,
                iban,
                effectiveCompany.name());

        return "mailto:" + recipient
                + "?subject=" + encodeMailParam(subject)
                + "&body=" + encodeMailParam(body);
    }

    private static String encodeMailParam(String raw) {
        return URLEncoder.encode(raw != null ? raw : "", StandardCharsets.UTF_8).replace("+", "%20");
    }

    public List<InvoiceItem> findItems(long invoiceId) {
        return invoiceDao.findItemsByInvoiceId(invoiceId);
    }

    /**
     * Computes strictly separated per-currency P&L totals (HUF, EUR, USD, ...) with zero FX conversion.
     */
    public FinancialSummary computeSummary(List<Invoice> invoices) {
        Map<String, MutableTotals> acc = new LinkedHashMap<>();

        // Single query loads all configured currencies (active + inactive) to avoid N+1 decimal lookups
        List<CurrencyDef> allCurrencies = financeConfigDao.findCurrencies(false);
        Map<String, Integer> configuredDecimals = new LinkedHashMap<>();
        for (CurrencyDef c : allCurrencies) {
            configuredDecimals.put(c.code(), c.decimalPlaces());
            if (c.active()) {
                acc.put(c.code(), new MutableTotals(c.code(), c.decimalPlaces()));
            }
        }

        for (Invoice inv : invoices) {
            if (inv.status() == InvoiceStatus.CANCELLED) {
                continue;
            }
            String cur = inv.effectiveCurrencyCode();
            MutableTotals bucket = acc.computeIfAbsent(cur,
                    k -> new MutableTotals(k, configuredDecimals.getOrDefault(k, CurrencyDef.defaultDecimalsFor(k))));
            BigDecimal amt = CurrencyDef.roundForScale(
                    inv.grossAmount() != null ? inv.grossAmount() : BigDecimal.ZERO, bucket.decimals);
            if (inv.direction() == InvoiceDirection.OUTGOING) {
                bucket.totalIncome = bucket.totalIncome.add(amt);
                if (inv.status() == InvoiceStatus.PAID) {
                    bucket.paidIncome = bucket.paidIncome.add(amt);
                }
            } else {
                bucket.totalExpenses = bucket.totalExpenses.add(amt);
                if (inv.status() == InvoiceStatus.PAID) {
                    bucket.paidExpenses = bucket.paidExpenses.add(amt);
                }
            }
            if (inv.status() == InvoiceStatus.ISSUED || inv.status() == InvoiceStatus.OVERDUE) {
                bucket.unpaidCount++;
                bucket.unpaidAmount = bucket.unpaidAmount.add(amt);
            }
        }

        Map<String, FinancialSummary.CurrencyTotals> result = new LinkedHashMap<>();
        for (MutableTotals m : acc.values()) {
            BigDecimal net = CurrencyDef.roundForScale(m.totalIncome.subtract(m.totalExpenses), m.decimals);
            result.put(m.currency, new FinancialSummary.CurrencyTotals(
                    m.currency,
                    m.decimals,
                    CurrencyDef.roundForScale(m.totalIncome, m.decimals),
                    CurrencyDef.roundForScale(m.paidIncome, m.decimals),
                    CurrencyDef.roundForScale(m.totalExpenses, m.decimals),
                    CurrencyDef.roundForScale(m.paidExpenses, m.decimals),
                    net,
                    m.unpaidCount,
                    CurrencyDef.roundForScale(m.unpaidAmount, m.decimals)
            ));
        }
        return new FinancialSummary(result);
    }

    private static final class MutableTotals {
        final String currency;
        final int decimals;
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal paidIncome = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal paidExpenses = BigDecimal.ZERO;
        int unpaidCount = 0;
        BigDecimal unpaidAmount = BigDecimal.ZERO;

        MutableTotals(String currency, int decimals) {
            this.currency = currency;
            this.decimals = decimals;
        }
    }
}
