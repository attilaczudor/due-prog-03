package hu.due.rewear.service;

import hu.due.rewear.dao.DowntimeDao;
import hu.due.rewear.dao.GradeYieldDao;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.InvoiceDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.ShiftHandoverDao;
import hu.due.rewear.dao.TodoDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.dao.WorkListDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.DowntimeEvent;
import hu.due.rewear.model.HandoverIssue;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.PersonalTodo;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.ShiftHandover;
import hu.due.rewear.model.SystemTodoItem;
import hu.due.rewear.model.User;
import hu.due.rewear.model.WarehouseLocation;
import hu.due.rewear.ui.MainFrame;
import hu.due.rewear.util.BusinessClock;
import hu.due.rewear.util.FormatUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes logically ordered, role-specific System To-Do lists from live warehouse/order/invoice state,
 * alongside separate user-managed personal to-do items.
 *
 * <p>Every role gets a list. That had to be said out loud, because for a while five of them did not: the
 * list was written when the company had drivers, a station manager and an office, and the roles added
 * afterwards — the two production leaders, the payroll role, and the sorters and helpers who are most of the
 * workforce — fell through every branch and opened the application to an empty screen.
 *
 * <p>What a list may contain is limited on purpose. Each item is a fact somebody can act on, not an opinion
 * about how the day is going: a stoppage that is still open, a call for material nobody has answered, a
 * handover nobody picked up, a payroll day that cannot be computed. Nothing here judges a shift against a
 * pace it is supposed to be keeping, because the application has no honest figure for that.
 */
public final class TodoService {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final TodoDao todoDao;
    private final TransportService transportService;
    private final InventoryPackageDao packageDao;
    private final WarehouseLocationDao locationDao;
    private final OrderDao orderDao;
    private final InvoiceDao invoiceDao;
    private final WorkListDao workList;
    private final DowntimeDao downtimeDao;
    private final ShiftHandoverDao handoverDao;
    private final GradeYieldDao yieldDao;

    public TodoService(TodoDao todoDao,
                       TransportService transportService,
                       InventoryPackageDao packageDao,
                       WarehouseLocationDao locationDao,
                       OrderDao orderDao,
                       InvoiceDao invoiceDao,
                       WorkListDao workList,
                       DowntimeDao downtimeDao,
                       ShiftHandoverDao handoverDao,
                       GradeYieldDao yieldDao) {
        this.todoDao = todoDao;
        this.transportService = transportService;
        this.packageDao = packageDao;
        this.locationDao = locationDao;
        this.orderDao = orderDao;
        this.invoiceDao = invoiceDao;
        this.workList = workList;
        this.downtimeDao = downtimeDao;
        this.handoverDao = handoverDao;
        this.yieldDao = yieldDao;
    }

    /** Builds a prioritized, logically ordered list of live system tasks tailored to {@code user.roleCode()}. */
    public List<SystemTodoItem> buildSystemTodosFor(User user, I18nManager i18n) {
        if (user == null) {
            return List.of();
        }
        List<SystemTodoItem> items = new ArrayList<>();
        RoleCode role = user.roleCode();

        if (role.isDriver()) {
            appendForkliftTodos(user, items, i18n);
            // The supply driver serves the sorting floor, and that was missing from their list entirely:
            // they saw customer orders and putaway, but not the sorter standing at an empty bench.
            if (role == RoleCode.SUPPLY_DRIVER) {
                appendSupplyTodos(items, i18n);
            }
        } else if (role == RoleCode.OPERATOR) {
            appendOperatorTodos(user, items, i18n);
        } else if (role == RoleCode.SORTER || role == RoleCode.SORTING_HELPER) {
            appendOwnBenchTodos(user, items, i18n);
        } else if (role == RoleCode.HR_PAYROLL) {
            appendPayrollTodos(items, i18n);
        } else if (role.runsProduction()) {
            if (RoleCode.MANAGEMENT.contains(role)) {
                appendManagementTodos(items, i18n);
                appendForkliftBacklogSummary(items, i18n);
            }
            appendFloorLeadTodos(user, items, i18n);
        }

        items.sort(Comparator.comparingInt(SystemTodoItem::priorityRank));
        return items;
    }

    private void appendForkliftTodos(User driver, List<SystemTodoItem> items, I18nManager i18n) {
        // P1: Assigned Order Collection (scan & move reserved packages from shelves -> DISPATCH_ZONE), grouped by order
        Map<String, List<TransportService.StagingTask>> pendingByOrder = new LinkedHashMap<>();
        for (TransportService.StagingTask t : transportService.findOrderStagingTasksForDriver(driver.id())) {
            if (!t.isAtDispatchZone()) {
                pendingByOrder.computeIfAbsent(t.orderNumber() + " — " + t.customerName(), k -> new ArrayList<>()).add(t);
            }
        }
        for (Map.Entry<String, List<TransportService.StagingTask>> e : pendingByOrder.entrySet()) {
            List<TransportService.StagingTask> list = e.getValue();
            String firstLoc = list.get(0).currentLocationCode();
            boolean arrived = list.get(0).customerArrived();
            items.add(new SystemTodoItem(
                    arrived ? 0 : 1, "P1", i18n.t("todo.cat.order_staging"),
                    (arrived ? "[CUSTOMER HERE] " : "") + i18n.t("todo.sys.stage_order", e.getKey(), list.size()),
                    i18n.t("todo.sys.stage_order_detail", firstLoc),
                    MainFrame.VIEW_STAGING
            ));
        }

        // P2: Putaway from Operator Stations -> Storage Shelves, grouped by station
        Map<String, List<InventoryPackage>> putawayByStation = new LinkedHashMap<>();
        for (InventoryPackage p : transportService.findPutawayQueue()) {
            putawayByStation.computeIfAbsent(p.locationCode(), k -> new ArrayList<>()).add(p);
        }
        for (Map.Entry<String, List<InventoryPackage>> e : putawayByStation.entrySet()) {
            BigDecimal totalKg = e.getValue().stream().map(InventoryPackage::weightKg).reduce(BigDecimal.ZERO, BigDecimal::add);
            items.add(new SystemTodoItem(
                    2, "P2", i18n.t("todo.cat.putaway"),
                    i18n.t("todo.sys.putaway_station", e.getKey(), e.getValue().size(), FormatUtil.stripZeros(totalKg)),
                    i18n.t("todo.sys.putaway_detail"),
                    MainFrame.VIEW_PUTAWAY
            ));
        }
    }

    private void appendOperatorTodos(User operator, List<SystemTodoItem> items, I18nManager i18n) {
        // P1: Check operator's assigned station occupancy & packages awaiting forklift pickup
        if (operator.assignedStationId() != null) {
            locationDao.findById(operator.assignedStationId()).ifPresent(st -> {
                long waitingCount = packageDao.findByLocationId(st.id()).stream()
                        .filter(p -> p.status() == hu.due.rewear.model.PackageStatus.IN_STOCK)
                        .count();
                int pct = st.maxCapacityKg().compareTo(BigDecimal.ZERO) > 0
                        ? st.usedWeightKg().multiply(BigDecimal.valueOf(100))
                        .divide(st.maxCapacityKg(), 0, RoundingMode.HALF_UP).intValue()
                        : 0;
                items.add(new SystemTodoItem(
                        pct >= 80 ? 1 : 2, pct >= 80 ? "P1" : "P2",
                        i18n.t("todo.cat.station"),
                        i18n.t("todo.sys.my_station", st.code(), waitingCount, pct),
                        i18n.t("todo.sys.my_station_detail",
                                FormatUtil.stripZeros(st.usedWeightKg()), FormatUtil.stripZeros(st.maxCapacityKg())),
                        MainFrame.VIEW_INTAKE
                ));
            });
        }
        // P3: Daily intake shortcut
        long todayCount = packageDao.countCreatedOnDate(BusinessClock.today());
        items.add(new SystemTodoItem(
                3, "P3", i18n.t("todo.cat.intake"),
                i18n.t("todo.sys.today_intake", todayCount),
                i18n.t("todo.sys.today_intake_detail"),
                MainFrame.VIEW_INTAKE
        ));
    }

    private void appendManagementTodos(List<SystemTodoItem> items, I18nManager i18n) {
        // P1: Overdue Invoices
        List<Invoice> overdue = invoiceDao.findOverdue();
        for (Invoice inv : overdue) {
            String amt = hu.due.rewear.model.CurrencyDef.formatAmount(
                    inv.grossAmount(), inv.effectiveCurrencyCode(), inv.currencyDecimals());
            items.add(new SystemTodoItem(
                    1, "P1", i18n.t("todo.cat.overdue"),
                    i18n.t("todo.sys.overdue_inv", inv.invoiceNumber(), inv.partnerName()),
                    amt + " — " + i18n.t("invoice.due_date") + ": " + inv.dueDate(),
                    MainFrame.VIEW_INVOICES
            ));
        }

        // P2: Active orders (DRAFT/PACKING) — distinguish Ready-to-Ship (all at DOCK) vs Awaiting Staging
        List<TransportService.StagingTask> staging = transportService.findOrderStagingTasks();
        for (Order ord : orderDao.findOpenOrders()) {
            List<TransportService.StagingTask> ordTasks = staging.stream()
                    .filter(t -> t.orderId() == ord.id()).toList();
            if (ord.isCustomerArrived()) {
                items.add(new SystemTodoItem(
                        0, "P1", i18n.t("todo.cat.customer_arrived"),
                        i18n.t("todo.sys.customer_arrived", ord.orderNumber(), ord.customerName()),
                        i18n.t("todo.sys.customer_arrived_detail",
                                ord.dispatchLocationCode() != null ? ord.dispatchLocationCode() : "DOCK-01"),
                        MainFrame.VIEW_ORDERS
                ));
            } else if (!ordTasks.isEmpty() && ordTasks.stream().allMatch(TransportService.StagingTask::isAtDispatchZone)) {
                items.add(new SystemTodoItem(
                        2, "P2", i18n.t("todo.cat.ready_ship"),
                        i18n.t("todo.sys.order_ready_ship", ord.orderNumber(), ord.customerName()),
                        i18n.t("todo.sys.order_ready_detail", ordTasks.size()),
                        MainFrame.VIEW_ORDERS
                ));
            } else {
                items.add(new SystemTodoItem(
                        3, "P3", i18n.t("todo.cat.open_order"),
                        i18n.t("todo.sys.open_order", ord.orderNumber(), ord.customerName()),
                        i18n.t("status.order." + ord.status().name()) + " — " + FormatUtil.stripZeros(ord.totalWeightKg()) + " kg",
                        MainFrame.VIEW_ORDERS
                ));
            }
        }

        // P4: Warehouse capacity warnings (>= 85%)
        for (WarehouseLocation loc : locationDao.findAll(true)) {
            if (loc.maxCapacityKg().compareTo(BigDecimal.ZERO) <= 0) continue;
            int pct = loc.usedWeightKg().multiply(BigDecimal.valueOf(100))
                    .divide(loc.maxCapacityKg(), 0, RoundingMode.HALF_UP).intValue();
            if (pct >= 85) {
                items.add(new SystemTodoItem(
                        4, "P4", i18n.t("todo.cat.capacity"),
                        i18n.t("todo.sys.loc_near_full", loc.code(), pct),
                        FormatUtil.stripZeros(loc.usedWeightKg()) + " / " + FormatUtil.stripZeros(loc.maxCapacityKg()) + " kg",
                        MainFrame.VIEW_LOCATIONS
                ));
            }
        }
    }

    private void appendForkliftBacklogSummary(List<SystemTodoItem> items, I18nManager i18n) {
        int putawayCount = transportService.findPutawayQueue().size();
        if (putawayCount > 0) {
            items.add(new SystemTodoItem(
                    3, "P3", i18n.t("todo.cat.putaway"),
                    i18n.t("todo.sys.putaway_backlog", putawayCount),
                    i18n.t("todo.sys.putaway_detail"),
                    MainFrame.VIEW_PUTAWAY
            ));
        }
    }

    // ----------------------------------------------------------------- the floor

    /**
     * A sorter's or a helper's own bench: what is still open in front of them, then how the day stands.
     *
     * <p>The progress line is last on purpose. A sorter does not need the application to tell them how much
     * they have sorted — they need it to tell them the thing they cannot see from the bench, which is that a
     * stoppage of theirs was never closed or that bags they reported are still sitting unpacked.
     */
    private void appendOwnBenchTodos(User worker, List<SystemTodoItem> items, I18nManager i18n) {
        RoleCode role = worker.roleCode();
        WorkListDao.OwnShift mine = workList.ownShift(worker.id(), role, BusinessClock.today());

        // P1: a stoppage covering this person that nobody has closed. Until it is closed it has no reason
        // and no minutes, so it is missing from every figure the shift is judged by.
        for (DowntimeEvent event : downtimeDao.findRunningCovering(null, worker.id())) {
            items.add(openStoppageItem(event, i18n));
        }

        // P1: called for material and still standing there.
        if (mine.awaitingDelivery() > 0) {
            items.add(new SystemTodoItem(
                    1, "P1", i18n.t("todo.cat.own_bench"),
                    i18n.t("todo.sys.own_waiting", mine.awaitingDelivery(), minutesSince(mine.oldestCallAt())),
                    i18n.t("todo.sys.own_waiting_detail"),
                    MainFrame.VIEW_SORTING
            ));
        }

        // P2: a run that was delivered and never finished. Its input weight is what the whole day is
        // measured from, and only the sorter can close it, so only the sorter is asked.
        if (role == RoleCode.SORTER && mine.onBench() > 0) {
            items.add(new SystemTodoItem(
                    2, "P2", i18n.t("todo.cat.own_bench"),
                    i18n.t("todo.sys.own_bench_open", mine.onBench()),
                    i18n.t("todo.sys.own_bench_open_detail"),
                    MainFrame.VIEW_SORTING
            ));
        }

        // P2: bags waiting to be packed. This is the helper's own job rather than the sorter's, so it is
        // only their item: telling a sorter about work they cannot do is how a list starts being ignored.
        if (role == RoleCode.SORTING_HELPER && mine.unpackedBags() > 0) {
            items.add(new SystemTodoItem(
                    2, "P2", i18n.t("todo.cat.own_bench"),
                    i18n.t("todo.sys.own_unpacked", mine.unpackedBags()),
                    i18n.t("todo.sys.own_unpacked_detail"),
                    MainFrame.VIEW_SORTING
            ));
        }

        // P3: how the day stands. A helper has no target of their own, so they are told what they worked
        // rather than measured against a number that belongs to somebody else's bench.
        if (role == RoleCode.SORTER && mine.targetGrams() > 0) {
            items.add(new SystemTodoItem(
                    3, "P3", i18n.t("todo.cat.own_bench"),
                    i18n.t("todo.sys.own_target",
                            FormatUtil.stripZeros(SortingService.kg(mine.processedGrams())),
                            FormatUtil.stripZeros(SortingService.kg(mine.targetGrams())),
                            percentOf(mine.processedGrams(), mine.targetGrams())),
                    i18n.t("todo.sys.own_target_detail"),
                    MainFrame.VIEW_SORTING
            ));
        } else if (role == RoleCode.SORTING_HELPER && mine.processedGrams() > 0) {
            items.add(new SystemTodoItem(
                    3, "P3", i18n.t("todo.cat.own_bench"),
                    i18n.t("todo.sys.own_helped", FormatUtil.stripZeros(SortingService.kg(mine.processedGrams()))),
                    i18n.t("todo.sys.own_helped_detail"),
                    MainFrame.VIEW_SORTING
            ));
        }
    }

    /** What the supply driver owes the sorting floor: material called for, and collections not yet stored. */
    private void appendSupplyTodos(List<SystemTodoItem> items, I18nManager i18n) {
        for (WorkListDao.SupplyCall call : workList.openSupplyCalls()) {
            String lot = call.lotReference() == null ? i18n.t("todo.sys.supply_any_lot")
                    : call.lotReference() + (call.material() == null || call.material().isBlank()
                    ? "" : " — " + call.material());
            items.add(new SystemTodoItem(
                    1, "P1", i18n.t("todo.cat.supply"),
                    i18n.t("todo.sys.supply_call", call.sorter(),
                            call.station() == null ? "—" : call.station(), minutesSince(call.requestedAt())),
                    i18n.t("todo.sys.supply_call_detail", lot, call.requestedGrams() == null
                            ? i18n.t("todo.sys.supply_no_amount")
                            : FormatUtil.stripZeros(SortingService.kg(call.requestedGrams())) + " "
                            + i18n.t("common.kg")),
                    MainFrame.VIEW_SORTING
            ));
        }
        for (WorkListDao.UnstoredLot lot : workList.unstoredLots()) {
            items.add(new SystemTodoItem(
                    2, "P2", i18n.t("todo.cat.supply"),
                    i18n.t("todo.sys.lot_unstored", lot.reference(),
                            FormatUtil.stripZeros(SortingService.kg(lot.receivedGrams()))),
                    i18n.t("todo.sys.lot_unstored_detail", lot.material() == null || lot.material().isBlank()
                            ? "—" : lot.material()),
                    MainFrame.VIEW_SORTING
            ));
        }
    }

    /**
     * What the people who run the floor are answerable for: the shift that has just ended, what is still
     * open on it, and the floor's own backlog.
     */
    private void appendFloorLeadTodos(User leader, List<SystemTodoItem> items, I18nManager i18n) {
        LocalDateTime now = BusinessClock.now();

        for (DowntimeEvent event : downtimeDao.findRunningCovering(null, null)) {
            items.add(openStoppageItem(event, i18n));
        }

        // The shift that has ended is the one a handover is owed for — not the one being worked, which is
        // not late until it finishes. Writing it is the outgoing leader's closing task; picking it up is the
        // incoming leader's first.
        ShiftHandoverService.ShiftSlot slot =
                ShiftHandoverService.previousSlot(ShiftHandoverService.currentSlot(now));
        Optional<ShiftHandover> sheet = handoverDao.find(slot.workDay(), slot.shift());
        String shiftName = i18n.t("rota.shift." + slot.shift().name());
        if (sheet.isEmpty()) {
            items.add(new SystemTodoItem(
                    1, "P1", i18n.t("todo.cat.handover"),
                    i18n.t("todo.sys.handover_missing", slot.workDay().toString(), shiftName),
                    i18n.t("todo.sys.handover_missing_detail"),
                    MainFrame.VIEW_HANDOVER
            ));
        } else if (!sheet.get().acknowledged()) {
            items.add(new SystemTodoItem(
                    1, "P1", i18n.t("todo.cat.handover"),
                    i18n.t("todo.sys.handover_unacknowledged", shiftName),
                    i18n.t("todo.sys.handover_unacknowledged_detail", sheet.get().outgoingByName(),
                            sheet.get().submittedAt() == null ? "—" : HHMM.format(sheet.get().submittedAt())),
                    MainFrame.VIEW_HANDOVER
            ));
        }

        for (HandoverIssue issue : handoverDao.findOpenIssues()) {
            boolean mine = issue.ownerId() == leader.id();
            boolean pressing = mine || issue.escalated() || issue.overdue(BusinessClock.today());
            items.add(new SystemTodoItem(
                    pressing ? 1 : 2, pressing ? "P1" : "P2", i18n.t("todo.cat.handover"),
                    i18n.t(mine ? "todo.sys.issue_mine" : "todo.sys.issue_open",
                            issue.ownerName(), FormatUtil.truncate(issue.summary(), 70)),
                    i18n.t("todo.sys.issue_detail", issue.dueOn().toString(), issue.hoursOpen(now))
                            + (issue.escalated() ? " · " + i18n.t("todo.sys.issue_escalated") : "")
                            + (issue.overdue(BusinessClock.today()) ? " · " + i18n.t("todo.sys.issue_overdue") : ""),
                    MainFrame.VIEW_HANDOVER
            ));
        }

        List<WorkListDao.SupplyCall> calls = workList.openSupplyCalls();
        if (!calls.isEmpty()) {
            items.add(new SystemTodoItem(
                    2, "P2", i18n.t("todo.cat.supply"),
                    i18n.t("todo.sys.calls_waiting", calls.size(), minutesSince(calls.get(0).requestedAt())),
                    i18n.t("todo.sys.calls_waiting_detail", calls.get(0).sorter()),
                    MainFrame.VIEW_SORTING
            ));
        }

        int unstored = workList.unstoredLots().size();
        if (unstored > 0) {
            items.add(new SystemTodoItem(
                    2, "P2", i18n.t("todo.cat.supply"),
                    i18n.t("todo.sys.lots_unstored", unstored),
                    i18n.t("todo.sys.lots_unstored_detail"),
                    MainFrame.VIEW_SORTING
            ));
        }

        WorkListDao.BagBacklog backlog = workList.bagBacklog();
        if (!backlog.isEmpty()) {
            items.add(new SystemTodoItem(
                    3, "P3", i18n.t("todo.cat.own_bench"),
                    i18n.t("todo.sys.bag_backlog", backlog.unpacked(), backlog.unregistered()),
                    i18n.t("todo.sys.bag_backlog_detail"),
                    MainFrame.VIEW_SORTING
            ));
        }

        // The research's central finding is that grade mix rather than throughput is the profit lever, and
        // the application can now measure it. Until somebody captures a baseline from a period they are
        // willing to stand behind, there is nothing to compare a shift's mix against — and that is a
        // decision to prompt for, not one to make up a number for.
        if (yieldDao.findActive().isEmpty()) {
            items.add(new SystemTodoItem(
                    4, "P4", i18n.t("todo.cat.yield"),
                    i18n.t("todo.sys.no_yield_baseline"),
                    i18n.t("todo.sys.no_yield_baseline_detail"),
                    MainFrame.VIEW_SORTING
            ));
        }
    }

    /**
     * The payroll role's list, built on the last completed day rather than on today: a day still being
     * worked has nothing to pay yet, and asking about it would make the list cry wolf every morning.
     */
    private void appendPayrollTodos(List<SystemTodoItem> items, I18nManager i18n) {
        LocalDate day = BusinessClock.today().minusDays(1);
        for (WorkListDao.MissingRate gap : workList.daysWithoutRate(day)) {
            items.add(new SystemTodoItem(
                    1, "P1", i18n.t("todo.cat.payroll"),
                    i18n.t("todo.sys.payroll_no_rate", gap.worker(), gap.department(),
                            i18n.t("role." + gap.role().name())),
                    i18n.t("todo.sys.payroll_no_rate_detail", day.toString()),
                    MainFrame.VIEW_PAYROLL
            ));
        }
        // Payroll is a day's job rather than a person's, so a whole shift missing from the draft is one
        // item with the names in it. Ten rows saying the same thing push everything else off the screen.
        List<WorkListDao.UnpaidDay> unpaid = workList.sortedWithoutPayrollDay(day);
        if (unpaid.size() == 1) {
            WorkListDao.UnpaidDay only = unpaid.get(0);
            items.add(new SystemTodoItem(
                    2, "P2", i18n.t("todo.cat.payroll"),
                    i18n.t("todo.sys.payroll_missing_day", only.worker(), day.toString()),
                    i18n.t("todo.sys.payroll_missing_day_detail",
                            FormatUtil.stripZeros(SortingService.kg(only.processedGrams()))),
                    MainFrame.VIEW_PAYROLL
            ));
        } else if (!unpaid.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (WorkListDao.UnpaidDay row : unpaid.subList(0, Math.min(3, unpaid.size()))) {
                names.append(names.isEmpty() ? "" : ", ").append(row.worker());
            }
            if (unpaid.size() > 3) {
                names.append(" ").append(i18n.t("todo.sys.and_others", unpaid.size() - 3));
            }
            items.add(new SystemTodoItem(
                    2, "P2", i18n.t("todo.cat.payroll"),
                    i18n.t("todo.sys.payroll_missing_days", unpaid.size(), day.toString()),
                    i18n.t("todo.sys.payroll_missing_days_detail", names.toString()),
                    MainFrame.VIEW_PAYROLL
            ));
        }
    }

    /** One open stoppage, said the same way to the person it stopped and to the person who runs the shift. */
    private SystemTodoItem openStoppageItem(DowntimeEvent event, I18nManager i18n) {
        String reason = event.reasonName(i18n.getActiveLanguage());
        return new SystemTodoItem(
                1, "P1", i18n.t("todo.cat.stoppage"),
                i18n.t("todo.sys.stoppage_open", event.scopeLabel()),
                i18n.t("todo.sys.stoppage_open_detail", HHMM.format(event.startedAt()),
                        event.peopleAffected(), reason == null ? i18n.t("todo.sys.no_reason") : reason),
                MainFrame.VIEW_SORTING
        );
    }

    /**
     * How long ago a sorting timestamp was, in whole minutes.
     *
     * <p>Sorting stores instants rather than local date-times, so this parses one and measures it against
     * the business clock — never against the database's own idea of now, which no test can pin.
     */
    private static long minutesSince(String instantText) {
        if (instantText == null || instantText.isBlank()) {
            return 0;
        }
        return Math.max(0, Duration.between(hu.due.rewear.dao.DaoDateUtil.parseInstant(instantText),
                BusinessClock.clock().instant()).toMinutes());
    }

    /** A whole-number percentage, floored, so a bench one gram short of target never reads as finished. */
    private static long percentOf(long part, long whole) {
        return whole <= 0 ? 0 : part * 100 / whole;
    }

    public List<PersonalTodo> listPersonalTodos(long userId) {
        return todoDao.findByUser(userId);
    }

    /**
     * Counts a user's open personal to-dos without loading them.
     * <p>
     * Used by the sidebar badge refresh, which runs on a timer and only needs the number.
     */
    public int countOpenPersonalTodos(long userId) {
        return todoDao.countOpenByUser(userId);
    }

    public long addPersonalTodo(long userId, String title, String priority, LocalDate dueDate) {
        return todoDao.insert(userId, title, priority, dueDate);
    }

    public void togglePersonalTodo(long todoId, boolean completed) {
        todoDao.setCompleted(todoId, completed);
    }

    public void deletePersonalTodo(long todoId) {
        todoDao.delete(todoId);
    }
}
