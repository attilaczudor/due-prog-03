package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.CustomerDao;
import hu.due.rewear.dao.InvoiceDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
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
 * Manages Outgoing (revenue) and Incoming (expense) invoices, order-linked invoice generation,
 * reversible payment statuses, and live Income / Outcome financial summaries.
 */
public final class InvoiceService {

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceService.class);
    public static final BigDecimal DEFAULT_VAT_RATE = new BigDecimal("27.00");

    private final DatabaseManager db;
    private final InvoiceDao invoiceDao;
    private final OrderDao orderDao;
    private final OrderItemDao orderItemDao;
    private final CustomerDao customerDao;

    public InvoiceService(DatabaseManager db,
                          InvoiceDao invoiceDao,
                          OrderDao orderDao,
                          OrderItemDao orderItemDao,
                          CustomerDao customerDao) {
        this.db = db;
        this.invoiceDao = invoiceDao;
        this.orderDao = orderDao;
        this.orderItemDao = orderItemDao;
        this.customerDao = customerDao;
    }

    /**
     * Returns the existing Outgoing invoice for an order (refreshing its line items if still DRAFT/ISSUED),
     * or creates a new Outgoing invoice populated from the order and customer data.
     */
    public Invoice ensureInvoiceForOrder(long orderId, BigDecimal vatRate, long userId) {
        Order order = orderDao.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        Customer customer = customerDao.findById(order.customerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + order.customerId()));
        List<OrderItem> orderItems = orderItemDao.findByOrderId(orderId);
        BigDecimal effectiveVat = vatRate != null ? vatRate : DEFAULT_VAT_RATE;

        Optional<Invoice> existingOpt = invoiceDao.findByOrderId(orderId);
        if (existingOpt.isPresent()) {
            Invoice existing = existingOpt.get();
            if (existing.status() == InvoiceStatus.DRAFT || existing.status() == InvoiceStatus.ISSUED) {
                syncOrderItemsIntoInvoice(existing.id(), orderItems, existing.vatRate());
                return invoiceDao.findById(existing.id()).orElseThrow();
            }
            return existing;
        }

        BigDecimal netTotal = orderItems.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal vatAmount = netTotal.multiply(effectiveVat)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal grossAmount = netTotal.add(vatAmount).setScale(2, RoundingMode.HALF_UP);

        LocalDate today = LocalDate.now();
        LocalDate dueDate = today.plusDays(14);
        LocalDateTime now = LocalDateTime.now();

        long invoiceId = db.executeInTransaction(conn -> {
            String invNo = invoiceDao.generateNextInvoiceNumber(conn, InvoiceDirection.OUTGOING);
            long id = invoiceDao.insert(conn, invNo, InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES,
                    order.id(), customer.id(), customer.name(), customer.taxNumber(), customer.address(),
                    customer.email(), InvoiceStatus.ISSUED, today, dueDate, null,
                    netTotal, effectiveVat, vatAmount, grossAmount, "EUR", userId, now);
            for (OrderItem oi : orderItems) {
                String desc = oi.packageQrCode() + " (" + oi.summaryEn() + ")";
                invoiceDao.insertItem(conn, id, desc, oi.weightKg(), "kg", oi.pricePerKg(), oi.lineTotal());
            }
            return id;
        });

        Invoice created = invoiceDao.findById(invoiceId).orElseThrow();
        LOG.info("Created outgoing invoice {} for order {}", created.invoiceNumber(), order.orderNumber());
        return created;
    }

    private void syncOrderItemsIntoInvoice(long invoiceId, List<OrderItem> orderItems, BigDecimal vatRate) {
        BigDecimal netTotal = orderItems.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal vatAmount = netTotal.multiply(vatRate)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal grossAmount = netTotal.add(vatAmount).setScale(2, RoundingMode.HALF_UP);

        db.runInTransaction(conn -> {
            invoiceDao.deleteItemsByInvoiceId(conn, invoiceId);
            for (OrderItem oi : orderItems) {
                String desc = oi.packageQrCode() + " (" + oi.summaryEn() + ")";
                invoiceDao.insertItem(conn, invoiceId, desc, oi.weightKg(), "kg", oi.pricePerKg(), oi.lineTotal());
            }
            invoiceDao.updateAmounts(conn, invoiceId, netTotal, vatRate, vatAmount, grossAmount);
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
        if (partnerName == null || partnerName.isBlank()) {
            throw new IllegalArgumentException("Partner name is required.");
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive.");
        }
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Unit price must be >= 0.");
        }
        BigDecimal effectiveVat = vatRate != null && vatRate.compareTo(BigDecimal.ZERO) >= 0
                ? vatRate.setScale(2, RoundingMode.HALF_UP) : DEFAULT_VAT_RATE;
        BigDecimal net = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vat = net.multiply(effectiveVat).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal gross = net.add(vat).setScale(2, RoundingMode.HALF_UP);

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
                    "EUR", userId, LocalDateTime.now());
            invoiceDao.insertItem(conn, id, desc, quantity, unit != null ? unit : "db", unitPrice, net);
            return id;
        });

        Invoice created = invoiceDao.findById(invoiceId).orElseThrow();
        LOG.info("Created {} invoice {} ({} EUR gross)", direction, created.invoiceNumber(), created.grossAmount());
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

    public FinancialSummary computeSummary(List<Invoice> invoices) {
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal paidIncome = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal paidExpenses = BigDecimal.ZERO;
        int unpaidCount = 0;
        BigDecimal unpaidAmount = BigDecimal.ZERO;

        for (Invoice inv : invoices) {
            if (inv.status() == InvoiceStatus.CANCELLED) {
                continue;
            }
            BigDecimal amt = inv.grossAmount() != null ? inv.grossAmount() : BigDecimal.ZERO;
            if (inv.direction() == InvoiceDirection.OUTGOING) {
                totalIncome = totalIncome.add(amt);
                if (inv.status() == InvoiceStatus.PAID) {
                    paidIncome = paidIncome.add(amt);
                }
            } else {
                totalExpenses = totalExpenses.add(amt);
                if (inv.status() == InvoiceStatus.PAID) {
                    paidExpenses = paidExpenses.add(amt);
                }
            }
            if (inv.status() == InvoiceStatus.ISSUED || inv.status() == InvoiceStatus.OVERDUE) {
                unpaidCount++;
                unpaidAmount = unpaidAmount.add(amt);
            }
        }
        BigDecimal netBalance = totalIncome.subtract(totalExpenses).setScale(2, RoundingMode.HALF_UP);
        return new FinancialSummary(
                totalIncome.setScale(2, RoundingMode.HALF_UP),
                paidIncome.setScale(2, RoundingMode.HALF_UP),
                totalExpenses.setScale(2, RoundingMode.HALF_UP),
                paidExpenses.setScale(2, RoundingMode.HALF_UP),
                netBalance,
                unpaidCount,
                unpaidAmount.setScale(2, RoundingMode.HALF_UP)
        );
    }
}
