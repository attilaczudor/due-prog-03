package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.CustomerDao;
import hu.due.rewear.dao.InvoiceDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
import hu.due.rewear.dao.SettingsDao;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.FinancialSummary;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceItem;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Manages multi-currency (EUR & HUF) Outgoing (revenue) and Incoming (expense) invoices,
 * order-linked invoice generation, reversible payment statuses, and consolidated P&L reporting.
 */
public final class InvoiceService {

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceService.class);
    public static final BigDecimal DEFAULT_VAT_RATE = new BigDecimal("27.00");

    private final DatabaseManager db;
    private final InvoiceDao invoiceDao;
    private final OrderDao orderDao;
    private final OrderItemDao orderItemDao;
    private final CustomerDao customerDao;
    private final SettingsDao settingsDao;

    public InvoiceService(DatabaseManager db,
                          InvoiceDao invoiceDao,
                          OrderDao orderDao,
                          OrderItemDao orderItemDao,
                          CustomerDao customerDao) {
        this(db, invoiceDao, orderDao, orderItemDao, customerDao, new SettingsDao(db));
    }

    public InvoiceService(DatabaseManager db,
                          InvoiceDao invoiceDao,
                          OrderDao orderDao,
                          OrderItemDao orderItemDao,
                          CustomerDao customerDao,
                          SettingsDao settingsDao) {
        this.db = db;
        this.invoiceDao = invoiceDao;
        this.orderDao = orderDao;
        this.orderItemDao = orderItemDao;
        this.customerDao = customerDao;
        this.settingsDao = settingsDao;
    }

    public BigDecimal getCurrentEurToHufRate() {
        return settingsDao.getEurToHufRate();
    }

    public void updateEurToHufRate(BigDecimal rate) {
        settingsDao.setEurToHufRate(rate);
    }

    public CurrencyCode getReportingCurrency() {
        return settingsDao.getReportingCurrency();
    }

    public void setReportingCurrency(CurrencyCode currency) {
        settingsDao.setReportingCurrency(currency);
    }

    /**
     * Returns the existing Outgoing invoice for an order (refreshing its line items & currency if still DRAFT/ISSUED),
     * or creates a new Outgoing invoice populated from the order and customer data in the order's currency.
     */
    public Invoice ensureInvoiceForOrder(long orderId, BigDecimal vatRate, long userId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        Customer customer = customerDao.findById(order.customerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + order.customerId()));
        List<OrderItem> orderItems = orderItemDao.findByOrderId(orderId);
        BigDecimal effectiveVat = vatRate != null ? vatRate : DEFAULT_VAT_RATE;
        CurrencyCode cur = order.effectiveCurrency();
        BigDecimal fxRate = settingsDao.getEurToHufRate();

        Optional<Invoice> existingOpt = invoiceDao.findByOrderId(orderId);
        if (existingOpt.isPresent()) {
            Invoice existing = existingOpt.get();
            if (existing.status() == InvoiceStatus.DRAFT || existing.status() == InvoiceStatus.ISSUED) {
                syncOrderItemsIntoInvoice(existing.id(), orderItems, existing.vatRate(), cur, fxRate);
                return invoiceDao.findById(existing.id()).orElseThrow();
            }
            return existing;
        }

        BigDecimal netTotal = cur.round(orderItems.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal vatAmount = cur.round(netTotal.multiply(effectiveVat)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal grossAmount = cur.round(netTotal.add(vatAmount));

        LocalDate today = LocalDate.now();
        LocalDate dueDate = today.plusDays(14);
        LocalDateTime now = LocalDateTime.now();

        long invoiceId = db.executeInTransaction(conn -> {
            String invNo = invoiceDao.generateNextInvoiceNumber(conn, InvoiceDirection.OUTGOING);
            long id = invoiceDao.insert(conn, invNo, InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES,
                    order.id(), customer.id(), customer.name(), customer.taxNumber(), customer.address(),
                    customer.email(), InvoiceStatus.ISSUED, today, dueDate, null,
                    netTotal, effectiveVat, vatAmount, grossAmount, cur.name(), fxRate, userId, now);
            for (OrderItem oi : orderItems) {
                String desc = oi.packageQrCode() + " (" + oi.summaryEn() + ")";
                invoiceDao.insertItem(conn, id, desc, oi.weightKg(), "kg",
                        cur.round(oi.pricePerKg()), cur.round(oi.lineTotal()));
            }
            return id;
        });

        Invoice created = invoiceDao.findById(invoiceId).orElseThrow();
        LOG.info("Created outgoing invoice {} ({}) for order {}", created.invoiceNumber(), cur, order.orderNumber());
        return created;
    }

    private void syncOrderItemsIntoInvoice(long invoiceId, List<OrderItem> orderItems,
                                           BigDecimal vatRate, CurrencyCode cur, BigDecimal fxRate) {
        BigDecimal netTotal = cur.round(orderItems.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal vatAmount = cur.round(netTotal.multiply(vatRate)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal grossAmount = cur.round(netTotal.add(vatAmount));

        db.runInTransaction(conn -> {
            invoiceDao.deleteItemsByInvoiceId(conn, invoiceId);
            for (OrderItem oi : orderItems) {
                String desc = oi.packageQrCode() + " (" + oi.summaryEn() + ")";
                invoiceDao.insertItem(conn, invoiceId, desc, oi.weightKg(), "kg",
                        cur.round(oi.pricePerKg()), cur.round(oi.lineTotal()));
            }
            invoiceDao.updateAmounts(conn, invoiceId, netTotal, vatRate, vatAmount, grossAmount, cur.name(), fxRate);
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
        return createManualInvoice(direction, category, optionalInvoiceNumber, partnerName,
                partnerTaxNumber, partnerAddress, partnerEmail, itemDescription, quantity, unit,
                unitPrice, vatRate, CurrencyCode.EUR, issueDate, dueDate, status, userId);
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
                                       CurrencyCode currency,
                                       LocalDate issueDate,
                                       LocalDate dueDate,
                                       InvoiceStatus status,
                                       long userId) {
        if (partnerName == null || partnerName.isBlank()) {
            throw new IllegalArgumentException("Partner name is required.");
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive.");
        }
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Unit price must be >= 0.");
        }
        CurrencyCode cur = currency != null ? currency : CurrencyCode.EUR;
        BigDecimal fxRate = settingsDao.getEurToHufRate();
        BigDecimal effectiveVat = vatRate != null && vatRate.compareTo(BigDecimal.ZERO) >= 0
                ? vatRate.setScale(2, RoundingMode.HALF_UP) : DEFAULT_VAT_RATE;
        BigDecimal roundedUnit = cur.round(unitPrice);
        BigDecimal net = cur.round(quantity.multiply(roundedUnit));
        BigDecimal vat = cur.round(net.multiply(effectiveVat).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal gross = cur.round(net.add(vat));

        LocalDate effIssue = issueDate != null ? issueDate : LocalDate.now();
        LocalDate effDue = dueDate != null ? dueDate : effIssue.plusDays(14);
        InvoiceStatus effStatus = status != null ? status : InvoiceStatus.ISSUED;
        LocalDate paidDate = effStatus == InvoiceStatus.PAID ? effIssue : null;
        String desc = (itemDescription != null && !itemDescription.isBlank())
                ? itemDescription.trim() : category.name();

        long invoiceId = db.executeInTransaction(conn -> {
            String invNo = (optionalInvoiceNumber != null && !optionalInvoiceNumber.isBlank())
                    ? optionalInvoiceNumber.trim()
                    : invoiceDao.generateNextInvoiceNumber(conn, direction);
            long id = invoiceDao.insert(conn, invNo, direction, category, null, null,
                    partnerName.trim(), partnerTaxNumber, partnerAddress, partnerEmail,
                    effStatus, effIssue, effDue, paidDate, net, effectiveVat, vat, gross,
                    cur.name(), fxRate, userId, LocalDateTime.now());
            invoiceDao.insertItem(conn, id, desc, quantity, unit != null ? unit : "db", roundedUnit, net);
            return id;
        });

        Invoice created = invoiceDao.findById(invoiceId).orElseThrow();
        LOG.info("Created {} invoice {} ({} gross @ {} HUF/EUR)", direction, created.invoiceNumber(),
                cur.format(created.grossAmount()), fxRate);
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

    public List<InvoiceItem> findItems(long invoiceId) {
        return invoiceDao.findItemsByInvoiceId(invoiceId);
    }

    /** Computes consolidated P&L summary using the workspace's active reporting currency and EUR/HUF rate. */
    public FinancialSummary computeSummary(List<Invoice> invoices) {
        return computeSummary(invoices, settingsDao.getReportingCurrency(), settingsDao.getEurToHufRate());
    }

    /**
     * Computes consolidated multi-currency P&L in {@code reportingCurrency}, converting each invoice
     * using its recorded exchange rate (or {@code fallbackEurToHufRate}), while also returning exact
     * native EUR and HUF breakdowns.
     */
    public FinancialSummary computeSummary(List<Invoice> invoices, CurrencyCode reportingCurrency, BigDecimal fallbackEurToHufRate) {
        CurrencyCode target = reportingCurrency != null ? reportingCurrency : CurrencyCode.HUF;
        BigDecimal defaultRate = (fallbackEurToHufRate != null && fallbackEurToHufRate.compareTo(BigDecimal.ZERO) > 0)
                ? fallbackEurToHufRate : CurrencyCode.DEFAULT_EUR_HUF_RATE;

        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal paidIncome = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal paidExpenses = BigDecimal.ZERO;
        int unpaidCount = 0;
        BigDecimal unpaidAmount = BigDecimal.ZERO;

        BigDecimal incomeEur = BigDecimal.ZERO;
        BigDecimal incomeHuf = BigDecimal.ZERO;
        BigDecimal expenseEur = BigDecimal.ZERO;
        BigDecimal expenseHuf = BigDecimal.ZERO;
        BigDecimal unpaidEur = BigDecimal.ZERO;
        BigDecimal unpaidHuf = BigDecimal.ZERO;

        for (Invoice inv : invoices) {
            if (inv.status() == InvoiceStatus.CANCELLED) {
                continue;
            }
            CurrencyCode invCur = inv.currencyCode();
            BigDecimal nativeAmt = invCur.round(inv.grossAmount() != null ? inv.grossAmount() : BigDecimal.ZERO);
            BigDecimal rate = inv.exchangeRate() != null && inv.exchangeRate().compareTo(BigDecimal.ZERO) > 0
                    ? inv.exchangeRate() : defaultRate;
            BigDecimal converted = invCur.convertTo(nativeAmt, target, rate);

            if (inv.direction() == InvoiceDirection.OUTGOING) {
                totalIncome = totalIncome.add(converted);
                if (invCur == CurrencyCode.EUR) {
                    incomeEur = incomeEur.add(nativeAmt);
                } else {
                    incomeHuf = incomeHuf.add(nativeAmt);
                }
                if (inv.status() == InvoiceStatus.PAID) {
                    paidIncome = paidIncome.add(converted);
                }
            } else {
                totalExpenses = totalExpenses.add(converted);
                if (invCur == CurrencyCode.EUR) {
                    expenseEur = expenseEur.add(nativeAmt);
                } else {
                    expenseHuf = expenseHuf.add(nativeAmt);
                }
                if (inv.status() == InvoiceStatus.PAID) {
                    paidExpenses = paidExpenses.add(converted);
                }
            }
            if (inv.status() == InvoiceStatus.ISSUED || inv.status() == InvoiceStatus.OVERDUE) {
                unpaidCount++;
                unpaidAmount = unpaidAmount.add(converted);
                if (invCur == CurrencyCode.EUR) {
                    unpaidEur = unpaidEur.add(nativeAmt);
                } else {
                    unpaidHuf = unpaidHuf.add(nativeAmt);
                }
            }
        }

        BigDecimal netBalance = target.round(totalIncome.subtract(totalExpenses));
        return new FinancialSummary(
                target,
                target.round(totalIncome),
                target.round(paidIncome),
                target.round(totalExpenses),
                target.round(paidExpenses),
                netBalance,
                unpaidCount,
                target.round(unpaidAmount),
                CurrencyCode.EUR.round(incomeEur),
                CurrencyCode.HUF.round(incomeHuf),
                CurrencyCode.EUR.round(expenseEur),
                CurrencyCode.HUF.round(expenseHuf),
                CurrencyCode.EUR.round(unpaidEur),
                CurrencyCode.HUF.round(unpaidHuf)
        );
    }
}
