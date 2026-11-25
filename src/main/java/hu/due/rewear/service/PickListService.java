package hu.due.rewear.service;

import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.model.WarehouseLocation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the open order staging tasks into a pick list a driver can walk: packages that still have to be
 * collected come first, in walking order by zone, aisle and shelf, followed by those already at the dock.
 * <p>
 * Nothing is stored. A package counts as picked when it is at the dispatch zone, which is exactly what
 * {@link TransportService#scanAndCollectForOrder} produces, so the progress shown here moves with every
 * scan and cannot drift from the real state.
 */
public final class PickListService {

    /** One package to collect. {@code zone}, {@code aisle} and {@code shelf} come from its current location. */
    public record PickLine(long packageId, String qrCode, BigDecimal weightKg, String locationCode,
                           String zone, String aisle, String shelf, String dockCode,
                           String orderNumber, String customerName, boolean picked) { }

    public record OrderProgress(String orderNumber, String customerName, int picked, int total) {
        public boolean complete() {
            return total > 0 && picked == total;
        }
    }

    /** Lines in walking order: still to collect first, then already staged. */
    public record PickList(List<PickLine> lines) {

        public PickList {
            lines = List.copyOf(lines);
        }

        public int total() {
            return lines.size();
        }

        public int picked() {
            return (int) lines.stream().filter(PickLine::picked).count();
        }

        public int remaining() {
            return total() - picked();
        }

        public boolean isEmpty() {
            return lines.isEmpty();
        }

        public boolean isComplete() {
            return !lines.isEmpty() && remaining() == 0;
        }

        /** Whole percent of packages already collected; 0 for an empty list. */
        public int percentDone() {
            return lines.isEmpty() ? 0 : (int) Math.floor(100.0 * picked() / total());
        }

        public BigDecimal totalWeightKg() {
            return sum(lines);
        }

        public BigDecimal remainingWeightKg() {
            return sum(lines.stream().filter(l -> !l.picked()).toList());
        }

        /** Packages still to collect, in walking order. */
        public List<PickLine> pending() {
            return lines.stream().filter(l -> !l.picked()).toList();
        }

        /** Per-order progress in first-appearance order. */
        public List<OrderProgress> byOrder() {
            Map<String, int[]> counts = new LinkedHashMap<>();
            Map<String, String> customers = new HashMap<>();
            for (PickLine line : lines) {
                int[] c = counts.computeIfAbsent(line.orderNumber(), k -> new int[2]);
                c[1]++;
                if (line.picked()) {
                    c[0]++;
                }
                customers.putIfAbsent(line.orderNumber(), line.customerName());
            }
            List<OrderProgress> result = new ArrayList<>();
            counts.forEach((order, c) -> result.add(new OrderProgress(order, customers.get(order), c[0], c[1])));
            return result;
        }

        private static BigDecimal sum(List<PickLine> list) {
            return list.stream().map(PickLine::weightKg).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private final TransportService transportService;
    private final WarehouseLocationDao locationDao;

    public PickListService(TransportService transportService, WarehouseLocationDao locationDao) {
        this.transportService = transportService;
        this.locationDao = locationDao;
    }

    /** The pick list of a single open order. Empty if the order has nothing staged for it. */
    public PickList forOrder(long orderId) {
        return build(transportService.findOrderStagingTasks(), Set.of(orderId));
    }

    /**
     * One list across several open orders, so a driver can collect for all of them in a single walk.
     * A null or empty selection means every open order.
     */
    public PickList forOrders(Collection<Long> orderIds) {
        return build(transportService.findOrderStagingTasks(), orderIds == null ? Set.of() : Set.copyOf(orderIds));
    }

    /** Every open order assigned to this driver, or all open orders when {@code driverId} is null. */
    public PickList forDriver(Long driverId) {
        return build(transportService.findOrderStagingTasksForDriver(driverId), Set.of());
    }

    private PickList build(List<TransportService.StagingTask> tasks, Set<Long> onlyOrders) {
        Map<Long, WarehouseLocation> locations = new HashMap<>();
        List<PickLine> lines = new ArrayList<>();
        for (TransportService.StagingTask task : tasks) {
            if (!onlyOrders.isEmpty() && !onlyOrders.contains(task.orderId())) {
                continue;
            }
            WarehouseLocation where = locations.computeIfAbsent(task.currentLocationId(),
                    id -> locationDao.findById(id).orElse(null));
            lines.add(new PickLine(task.packageId(), task.qrCode(), task.weightKg(), task.currentLocationCode(),
                    where == null ? "" : where.zone(), where == null ? "" : where.aisle(),
                    where == null ? "" : where.shelf(),
                    task.targetDispatchLocationCode() == null ? "" : task.targetDispatchLocationCode(),
                    task.orderNumber(), task.customerName(), task.isAtDispatchZone()));
        }
        lines.sort(Comparator.comparing(PickLine::picked)
                .thenComparing(PickLine::zone, PickListService::naturalCompare)
                .thenComparing(PickLine::aisle, PickListService::naturalCompare)
                .thenComparing(PickLine::shelf, PickListService::naturalCompare)
                .thenComparing(PickLine::qrCode));
        return new PickList(lines);
    }

    /**
     * Compares like a person reads location labels: digit runs by value, so aisle 2 comes before aisle 10
     * (plain text order would put "10" before "2"), other text case-insensitively.
     */
    static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            boolean digitsA = Character.isDigit(a.charAt(i));
            boolean digitsB = Character.isDigit(b.charAt(j));
            int endA = run(a, i, digitsA);
            int endB = run(b, j, digitsB);
            String chunkA = a.substring(i, endA);
            String chunkB = b.substring(j, endB);
            int cmp;
            if (digitsA && digitsB) {
                String numA = chunkA.replaceFirst("^0+(?=.)", "");
                String numB = chunkB.replaceFirst("^0+(?=.)", "");
                cmp = numA.length() != numB.length() ? Integer.compare(numA.length(), numB.length())
                        : numA.compareTo(numB);
            } else if (digitsA != digitsB) {
                cmp = digitsA ? -1 : 1;
            } else {
                cmp = chunkA.compareToIgnoreCase(chunkB);
            }
            if (cmp != 0) {
                return cmp;
            }
            i = endA;
            j = endB;
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static int run(String s, int from, boolean digits) {
        int end = from;
        while (end < s.length() && Character.isDigit(s.charAt(end)) == digits) {
            end++;
        }
        return end;
    }
}
