package hu.due.rewear.service;

import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.InvoiceDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.TodoDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.PersonalTodo;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.SystemTodoItem;
import hu.due.rewear.model.User;
import hu.due.rewear.model.WarehouseLocation;
import hu.due.rewear.ui.MainFrame;
import hu.due.rewear.util.BusinessClock;
import hu.due.rewear.util.FormatUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes logically ordered, role-specific System To-Do lists from live warehouse/order/invoice state,
 * alongside separate user-managed personal to-do items.
 */
public final class TodoService {

    private final TodoDao todoDao;
    private final TransportService transportService;
    private final InventoryPackageDao packageDao;
    private final WarehouseLocationDao locationDao;
    private final OrderDao orderDao;
    private final InvoiceDao invoiceDao;

    public TodoService(TodoDao todoDao,
                       TransportService transportService,
                       InventoryPackageDao packageDao,
                       WarehouseLocationDao locationDao,
                       OrderDao orderDao,
                       InvoiceDao invoiceDao) {
        this.todoDao = todoDao;
        this.transportService = transportService;
        this.packageDao = packageDao;
        this.locationDao = locationDao;
        this.orderDao = orderDao;
        this.invoiceDao = invoiceDao;
    }

    /** Builds a prioritized, logically ordered list of live system tasks tailored to {@code user.roleCode()}. */
    public List<SystemTodoItem> buildSystemTodosFor(User user, I18nManager i18n) {
        if (user == null) {
            return List.of();
        }
        List<SystemTodoItem> items = new ArrayList<>();
        RoleCode role = user.roleCode();

        if (role == RoleCode.FORKLIFT) {
            appendForkliftTodos(user, items, i18n);
        } else if (role == RoleCode.OPERATOR) {
            appendOperatorTodos(user, items, i18n);
        } else if (role == RoleCode.MANAGER || role == RoleCode.ADMIN || role == RoleCode.SUPER_ADMIN) {
            appendManagementTodos(items, i18n);
            appendForkliftBacklogSummary(items, i18n);
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
