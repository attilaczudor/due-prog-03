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
import hu.due.rewear.model.InvoiceItem;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import hu.due.rewear.model.TaxType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
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

    private final DatabaseManager db;
    private final InvoiceDao invoiceDao;
    private final OrderDao orderDao;
    private final OrderItemDao orderItemDao;
    private final CustomerDao customerDao;
    private final FinanceConfigDao financeConfigDao;

    public InvoiceService(DatabaseManager db,
                          InvoiceDao invoiceDao,
                          OrderDao orderDao,
                          OrderItemDao orderItemDao,
                          CustomerDao customerDao,
                          FinanceConfigDao financeConfigDao) {
        this.db = db;
        this.invoiceDao = invoiceDao;
        this.orderDao = orderDao;
        this.orderItemDao = orderItemDao;
        this.customerDao = customerDao;
        this.financeConfigDao = financeConfigDao;
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
     * Returns the existing Outgoing invoice for an order (refreshing line items, currency & tax type if DRAFT/ISSUED),
     * or creates a new Outgoing invoice populated from the order and customer data in the order's currency.
     */
    public Invoice ensureInvoiceForOrder(long orderId, TaxType chosenTax, long userId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        Customer customer = customerDao.findById(order.customerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + order.customerId()));
        List<OrderItem> orderItems = orderItemDao.findByOrderId(orderId);
        TaxType effectiveTax = chosenTax != null ? chosenTax : resolveDefaultTaxForCustomer(customer);
        BigDecimal vatRate = effectiveTax.ratePercent();
        String taxCode = effectiveTax.code();

        String cur = order.effectiveCurrencyCode();
        int decimals = financeConfigDao.decimalPlacesFor(cur);

        Optional<Invoice> existingOpt = invoiceDao.findByOrderId(orderId);
        if (existingOpt.isPresent()) {
            Invoice existing = existingOpt.get();
            if (existing.status() == InvoiceStatus.DRAFT || existing.status() == InvoiceStatus.ISSUED) {
                TaxType syncTax = chosenTax != null ? chosenTax : financeConfigDao.findTaxTypeByCode(existing.effectiveTaxTypeCode())
                        .orElse(effectiveTax);
                syncOrderItemsIntoInvoice(existing.id(), orderItems, syncTax.ratePercent(), syncTax.code(), cur, decimals);
                return invoiceDao.findById(existing.id()).orElseThrow();
            }
            return existing;
        }

        BigDecimal netTotal = CurrencyDef.roundForScale(orderItems.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add), decimals);
        BigDecimal vatAmount = CurrencyDef.roundForScale(netTotal.multiply(vatRate)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP), decimals);
        BigDecimal grossAmount = CurrencyDef.roundForScale(netTotal.add(vatAmount), decimals);

        LocalDate today = LocalDate.now();
        LocalDate dueDate = today.plusDays(14);
        LocalDateTime now = LocalDateTime.now();

        long invoiceId = db.executeInTransaction(conn -> {
            String invNo = invoiceDao.generateNextInvoiceNumber(conn, InvoiceDirection.OUTGOING);
            long id = invoiceDao.insert(conn, invNo, InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES,
                    order.id(), customer.id(), customer.name(), customer.taxNumber(), customer.address(),
                    customer.email(), InvoiceStatus.ISSUED, today, dueDate, null,
                    netTotal, vatRate, taxCode, vatAmount, grossAmount, cur,
                    CurrencyCode.DEFAULT_EUR_HUF_RATE, userId, now);
            for (OrderItem oi : orderItems) {
                String desc = oi.packageQrCode() + " (" + oi.summaryEn() + ")";
                invoiceDao.insertItem(conn, id, desc, oi.weightKg(), "kg",
                        CurrencyDef.roundForScale(oi.pricePerKg(), decimals),
                        CurrencyDef.roundForScale(oi.lineTotal(), decimals));
            }
            return id;
        });

        Invoice created = invoiceDao.findById(invoiceId).orElseThrow();
        LOG.info("Created outgoing invoice {} ({} / {}) for order {}",
                created.invoiceNumber(), cur, taxCode, order.orderNumber());
        return created;
    }

    private void syncOrderItemsIntoInvoice(long invoiceId, List<OrderItem> orderItems,
                                           BigDecimal vatRate, String taxCode, String cur, int decimals) {
        BigDecimal netTotal = CurrencyDef.roundForScale(orderItems.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add), decimals);
        BigDecimal vatAmount = CurrencyDef.roundForScale(netTotal.multiply(vatRate)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP), decimals);
        BigDecimal grossAmount = CurrencyDef.roundForScale(netTotal.add(vatAmount), decimals);

        db.runInTransaction(conn -> {
            invoiceDao.deleteItemsByInvoiceId(conn, invoiceId);
            for (OrderItem oi : orderItems) {
                String desc = oi.packageQrCode() + " (" + oi.summaryEn() + ")";
                invoiceDao.insertItem(conn, invoiceId, desc, oi.weightKg(), "kg",
                        CurrencyDef.roundForScale(oi.pricePerKg(), decimals),
                        CurrencyDef.roundForScale(oi.lineTotal(), decimals));
            }
            invoiceDao.updateAmounts(conn, invoiceId, netTotal, vatRate, taxCode, vatAmount, grossAmount,
                    cur, CurrencyCode.DEFAULT_EUR_HUF_RATE);
        });
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

        LocalDate effIssue = issueDate != null ? issueDate : LocalDate.now();
        LocalDate effDue = dueDate != null ? dueDate : effIssue.plusDays(14);
        InvoiceStatus effStatus = status != null ? status : InvoiceStatus.ISSUED;
        LocalDate paidDate = effStatus == InvoiceStatus.PAID ? effIssue : null;

        final BigDecimal finalNet = net;
        long invoiceId = db.executeInTransaction(conn -> {
            String invNo = (optionalInvoiceNumber != null && !optionalInvoiceNumber.isBlank())
                    ? optionalInvoiceNumber.trim()
                    : invoiceDao.generateNextInvoiceNumber(conn, direction);
            long id = invoiceDao.insert(conn, invNo, direction, category, null, partnerId,
                    partnerName.trim(), partnerTaxNumber, partnerAddress, partnerEmail,
                    effStatus, effIssue, effDue, paidDate, finalNet, effectiveVat, effectiveTaxCode, vat, gross,
                    cur, CurrencyCode.DEFAULT_EUR_HUF_RATE, userId, LocalDateTime.now());
            for (ComputedLine cl : computed) {
                invoiceDao.insertItem(conn, id, cl.desc(), cl.qty(), cl.unit(), cl.unitPrice(), cl.lineNet());
            }
            return id;
        });

        Invoice created = invoiceDao.findById(invoiceId).orElseThrow();
        LOG.info("Created {} invoice {} ({} line(s), {} / {})", direction, created.invoiceNumber(),
                computed.size(), CurrencyDef.formatAmount(created.grossAmount(), cur, decimals), effectiveTaxCode);
        return created;
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
        Invoice orig = invoiceDao.findById(originalInvoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + originalInvoiceId));
        if (orig.category() == InvoiceCategory.CREDIT_NOTE) {
            throw new IllegalStateException("Cannot issue a credit note for an existing credit note.");
        }
        if (orig.status() == InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Invoice " + orig.invoiceNumber() + " is already cancelled.");
        }
        List<InvoiceItem> origItems = invoiceDao.findItemsByInvoiceId(originalInvoiceId);
        boolean wasPaid = orig.status() == InvoiceStatus.PAID;
        InvoiceDirection cnDir = wasPaid
                ? (orig.direction() == InvoiceDirection.OUTGOING ? InvoiceDirection.INCOMING : InvoiceDirection.OUTGOING)
                : orig.direction();
        InvoiceStatus cnStatus = wasPaid ? InvoiceStatus.PAID : InvoiceStatus.CANCELLED;
        LocalDate today = LocalDate.now();
        LocalDate paidDate = wasPaid ? today : null;

        long cnId = db.executeInTransaction(conn -> {
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
                    orig.exchangeRate(), userId, LocalDateTime.now());
            String refPrefix = "CREDIT NOTE [Ref: " + orig.invoiceNumber() + "] — ";
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
        });

        Invoice created = invoiceDao.findById(cnId).orElseThrow();
        LOG.info("Issued credit note {} reversing {} (wasPaid={})", created.invoiceNumber(), orig.invoiceNumber(), wasPaid);
        return created;
    }

    public Invoice changeStatus(long invoiceId, InvoiceStatus targetStatus) {
        Invoice existing = invoiceDao.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));
        LocalDate paidDate = targetStatus == InvoiceStatus.PAID
                ? (existing.paidDate() != null ? existing.paidDate() : LocalDate.now())
                : null;
        invoiceDao.updateStatus(invoiceId, targetStatus, paidDate);
        LOG.info("Invoice {} status changed {} -> {}", existing.invoiceNumber(), existing.status(), targetStatus);
        return invoiceDao.findById(invoiceId).orElseThrow();
    }

    /** Automatically flags all past-due {@code ISSUED} invoices as {@code OVERDUE} relative to today. */
    public int syncOverdueStatuses() {
        return syncOverdueStatuses(LocalDate.now());
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
        String body = i18n.t("invoice.reminder.body",
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
