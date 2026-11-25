package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.DictionaryDao;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.*;
import hu.due.rewear.util.BusinessClock;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SortingService {
    private static final String VIEW = AppModule.SORTING.viewKey();
    private static final String REQUEST_SELECT = """
            SELECT r.*, u.full_name AS sorter,
                   (SELECT GROUP_CONCAT(h.full_name, ', ') FROM sorting_request_helpers rh
                    JOIN users h ON h.id=rh.helper_id WHERE rh.request_id=r.id) AS helper,
                   d.full_name AS driver,
                   t.name AS team, l.reference AS lot, l.material AS material, s.code AS station,
                   COALESCE((SELECT SUM(b.weight_grams) FROM sorting_bags b WHERE b.request_id=r.id),0) AS output
            FROM sorting_requests r
            JOIN users u ON u.id=r.sorter_id JOIN users d ON d.id=r.driver_id
            JOIN sorting_teams t ON t.id=r.team_id JOIN warehouse_locations s ON s.id=r.station_id
            LEFT JOIN sorting_raw_lots l ON l.id=COALESCE(r.lot_id, r.requested_lot_id)
            """;
    private static final String LOT_SELECT = """
            SELECT l.*, loc.code AS location_code FROM sorting_raw_lots l
            LEFT JOIN warehouse_locations loc ON loc.id=l.location_id
            """;
    private static final String BAG_SELECT = """
            SELECT b.*, tg.code AS target, gt.code AS garment, q.code AS grade, p.code AS packaging,
                   u.full_name AS sorter, s.code AS station, i.qr_code AS qr, packer.full_name AS packer
            FROM sorting_bags b JOIN sorting_requests r ON r.id=b.request_id
            JOIN users u ON u.id=r.sorter_id JOIN warehouse_locations s ON s.id=r.station_id
            JOIN target_groups tg ON tg.id=b.target_group_id JOIN garment_types gt ON gt.id=b.garment_type_id
            JOIN quality_grades q ON q.id=b.quality_grade_id LEFT JOIN package_types p ON p.id=b.package_type_id
            LEFT JOIN inventory_packages i ON i.id=b.inventory_package_id
            LEFT JOIN users packer ON packer.id=b.packed_by
            """;

    public record Team(long id, String name, List<Long> helperIds, long driverId, long stationId, long targetGrams,
                       Integer bonusBasisPoints, List<Long> sorterIds) { }
    /**
     * One raw material line: a daily collection of unsorted clothing from a collection area. It is received at
     * the facility first, and becomes available to sorters once a forklift driver has stored it at a location.
     */
    public record Lot(long id, String reference, String material, String sourceArea, Long locationId, String locationCode,
                      long receivedGrams, long availableGrams, String receivedAt) {
        public boolean stored() { return locationId != null; }
    }
    public record Request(long id, String sorter, String helper, String driver, String team, String station, String lot,
                          Long requestedGrams, Long deliveredGrams, Long processedGrams, long outputGrams, String requestedAt,
                          String deliveredAt, String finishedAt, String cancelledAt, Long requestedLotId, String material) {
        public boolean awaitingDelivery() { return deliveredAt == null && cancelledAt == null; }
        public boolean processing() { return deliveredAt != null && finishedAt == null; }
    }
    public record Bag(long id, long requestId, String sorter, String station, String target, String garment, String grade,
                      String packaging, long weightGrams, String reportedAt, String packedAt, String registeredAt,
                      String qr, String packer) { }
    public record Daily(String sorter, String helper, long processedGrams, long targetGrams, Integer bonusBasisPoints) {
        public boolean reachedTarget() { return processedGrams >= targetGrams; }
    }
    public record Workspace(List<Request> requests, List<Bag> bags, List<Lot> lots, List<Team> teams, List<Daily> daily) { }

    private final DatabaseManager db;
    private final SessionContext session;
    private final DictionaryDao dictionaries;
    private final WarehouseLocationDao locations;
    private final InventoryService inventory;
    private final InventoryPackageDao packages;
    private final NotificationService notifications;
    private final I18nManager i18n;

    private hu.due.rewear.dao.PurchaseLotDao purchaseLots;

    public SortingService(DatabaseManager db, SessionContext session, DictionaryDao dictionaries,
                          WarehouseLocationDao locations, InventoryService inventory, InventoryPackageDao packages,
                          NotificationService notifications, I18nManager i18n) {
        this.db = db;
        this.session = session;
        this.dictionaries = dictionaries;
        this.locations = locations;
        this.inventory = inventory;
        this.packages = packages;
        this.notifications = notifications;
        this.i18n = i18n;
    }

    /**
     * Where received material comes from. Set by the application context after construction, because
     * provenance arrived later than sorting did and the two are otherwise independent.
     */
    public void setPurchaseLots(hu.due.rewear.dao.PurchaseLotDao purchaseLots) {
        this.purchaseLots = purchaseLots;
    }

    public Workspace workspace(LocalDate day) {
        User actor = actor();
        return db.executeInTransaction(conn -> {
            Scope scope = scope(actor, "r");
            List<Request> requests = query(conn, REQUEST_SELECT + scope.where() + " ORDER BY r.id DESC",
                    SortingService::mapRequest, scope.params());
            List<Bag> bags = query(conn, BAG_SELECT + scope.where() + " ORDER BY b.id DESC", SortingService::mapBag, scope.params());
            // Sorters see only what they can actually call for: lots that are stored and still have stock.
            String lotFilter = actor.roleCode() == RoleCode.SORTER
                    ? " WHERE l.location_id IS NOT NULL AND l.available_grams>0" : "";
            List<Lot> lots = management(actor) || actor.roleCode() == RoleCode.SUPPLY_DRIVER || actor.roleCode() == RoleCode.SORTER
                    ? query(conn, LOT_SELECT + lotFilter + " ORDER BY l.available_grams=0, l.material COLLATE NOCASE, l.reference",
                    SortingService::mapLot) : List.of();
            List<Team> teams = management(actor) ? teams(conn) : List.of();
            return new Workspace(requests, bags, lots, teams, daily(conn, actor, day));
        });
    }

    public LocalDate today() { return BusinessClock.today(); }

    /**
     * Records one daily collection arriving at the facility from a collection area. The lot is not yet
     * available to sorters: a forklift driver is notified and stores it ({@link #storeLot}) first.
     */
    public long receive(String reference, String material, String sourceArea, BigDecimal weightKg) {
        return receive(reference, material, sourceArea, weightKg, null);
    }

    /**
     * Records one collection arriving at the facility, against the purchase lot it came from.
     *
     * <p>The purchase lot is what makes the material's origin, channel and legal status reconstructable
     * later; material received without one can be sorted but can never be traced, which is why the
     * collection area is now derived from the lot rather than typed. The lot has to have been assessed
     * first: what the material legally is, is decided where it was collected, not afterwards when somebody
     * needs an answer for a customs form.
     */
    public long receive(String reference, String material, String sourceArea, BigDecimal weightKg,
                        Long purchaseLotId) {
        User actor = actor();
        require(management(actor), "Only warehouse management may receive raw stock.");
        long weight = grams(weightKg, false);
        String label = reference == null ? "" : reference.strip();
        String what = material == null ? "" : material.strip();
        String area = sourceArea == null ? "" : sourceArea.strip();
        require(!label.isEmpty() && label.length() <= 100, "Enter a lot reference of up to 100 characters.");
        require(!what.isEmpty() && what.length() <= 120, "Enter the material of the lot (up to 120 characters).");
        require(purchaseLotId != null || !area.isEmpty(),
                "Choose the purchase lot this material came from, or name the collection area.");
        require(area.length() <= 120, "The collection area may be up to 120 characters.");
        long id = db.executeInTransaction(conn -> {
            String recordedArea = area;
            if (purchaseLotId != null) {
                require(purchaseLots != null, "Purchase lots are not available.");
                var source = purchaseLots.findById(conn, purchaseLotId)
                        .orElseThrow(() -> new IllegalStateException("The purchase lot does not exist."));
                require(source.isAssessed(),
                        "Purchase lot " + source.reference() + " has not been assessed yet. Record whether it"
                                + " is waste or fit for re-use before taking it into sorting.");
                long alreadyTaken = purchaseLots.receivedGrams(conn, purchaseLotId);
                long lotGrams = source.netWeightKg().movePointRight(3).longValue();
                require(alreadyTaken + weight <= lotGrams,
                        "That is more than purchase lot " + source.reference() + " has left: "
                                + kg(lotGrams - alreadyTaken) + " kg remain of " + kg(lotGrams) + " kg.");
                // The area stops being free text the moment there is a lot to read it from.
                recordedArea = source.originCountry() + " · " + source.collectionChannel();
            }
            long lot = insertId(conn, """
                    INSERT INTO sorting_raw_lots(reference,material,source_area,received_grams,available_grams,received_by,received_at,purchase_lot_id)
                    VALUES (?,?,?,?,?,?,?,?) RETURNING id
                    """, label, what, recordedArea, weight, weight, actor.id(), now(), purchaseLotId);
            if (purchaseLotId != null) {
                purchaseLots.addEvent(conn, purchaseLotId, "TAKEN_INTO_STOCK",
                        kg(weight) + " kg received as raw lot " + label, actor.id(),
                        hu.due.rewear.util.BusinessClock.now());
            }
            // Only the supply drivers of sorting teams, never the fulfilment driver who serves customer orders.
            for (long driver : query(conn, """
                    SELECT DISTINCT t.driver_id FROM sorting_teams t JOIN users u ON u.id=t.driver_id
                    WHERE u.active=1
                    """, row -> row.getLong(1))) {
                alert(conn, driver, i18n.t("sorting.alert.lot_received", label), what + " · " + recordedArea + " · " + kg(weight) + " kg");
            }
            return lot;
        });
        notifications.publishCommitted();
        return id;
    }

    /** Puts a received lot away at a storage location, which is what makes it available to sorters. */
    public void storeLot(long lotId, long locationId) {
        User actor = actor();
        require(management(actor) || actor.roleCode() == RoleCode.SUPPLY_DRIVER,
                "Only a supply driver or warehouse management may store raw stock.");
        db.runInTransaction(conn -> {
            locations.findById(conn, locationId).filter(l -> l.active() && l.locationType() == LocationType.STORAGE)
                    .orElseThrow(() -> new IllegalStateException("Select an active storage location."));
            require(changed(conn, "UPDATE sorting_raw_lots SET location_id=? WHERE id=?", locationId, lotId) == 1,
                    "The raw lot does not exist.");
        });
    }

    public void saveTeam(long teamId, String name, List<Long> helpers, long driver, long station, List<Long> sorters,
                         BigDecimal targetKg, BigDecimal bonusPercent) {
        User actor = actor();
        require(management(actor), "Only warehouse management may manage sorting teams.");
        String label = name == null ? "" : name.strip();
        require(!label.isEmpty() && label.length() <= 80, "Enter a team name of up to 80 characters.");
        require(!sorters.isEmpty() && new HashSet<>(sorters).size() == sorters.size(),
                "Choose one or more different sorting operators.");
        require(!helpers.isEmpty() && new HashSet<>(helpers).size() == helpers.size(),
                "Choose one or more different sorting helpers.");
        long target = grams(targetKg, false);
        Integer rate = bonusPercent == null ? null : basisPoints(bonusPercent);
        db.runInTransaction(conn -> {
            for (long helper : helpers) verifyStaff(conn, helper, RoleCode.SORTING_HELPER);
            verifyStaff(conn, driver, RoleCode.SUPPLY_DRIVER);
            verifyStation(conn, station);
            for (long sorter : sorters) verifyStaff(conn, sorter, RoleCode.SORTER);
            long id = teamId;
            if (id == 0) {
                id = insertId(conn, """
                        INSERT INTO sorting_teams(name,driver_id,station_id,target_grams,bonus_basis_points)
                        VALUES (?,?,?,?,?) RETURNING id
                        """, label, driver, station, target, rate);
            } else {
                require(changed(conn, """
                        UPDATE sorting_teams SET name=?,driver_id=?,station_id=?,target_grams=?,bonus_basis_points=?
                        WHERE id=?
                        """, label, driver, station, target, rate, id) == 1, "Sorting team no longer exists.");
            }
            changed(conn, "DELETE FROM sorting_team_helpers WHERE team_id=?", id);
            for (long helper : helpers) {
                changed(conn, "INSERT INTO sorting_team_helpers(team_id,helper_id) VALUES (?,?)", id, helper);
            }
            changed(conn, "DELETE FROM sorting_team_members WHERE team_id=?", id);
            for (long sorter : sorters) {
                require(scalar(conn, "SELECT COUNT(*) FROM sorting_team_members WHERE sorter_id=?", sorter) == 0,
                        "Remove the sorting operator from their previous team before reassigning them.");
                changed(conn, "INSERT INTO sorting_team_members(sorter_id,team_id) VALUES (?,?)", sorter, id);
            }
        });
    }

    /**
     * The sorter calls for a raw material line, not for an amount: how much the driver can bring is only known
     * at delivery, so the request carries no weight. The line must be stored and still have stock.
     */
    public long requestSupply(long lotId) {
        User actor = actor(RoleCode.SORTER);
        long id = db.executeInTransaction(conn -> {
            Lot lot = first(query(conn, LOT_SELECT + " WHERE l.id=?", SortingService::mapLot, lotId),
                    "Select a raw material line.");
            require(lot.stored(), "This raw material has not been stored yet.");
            require(lot.availableGrams() > 0, "This raw material is used up.");
            Team team = first(query(conn, """
                    SELECT t.* FROM sorting_teams t JOIN sorting_team_members m ON m.team_id=t.id WHERE m.sorter_id=?
                    """, r -> mapTeam(r, List.of(), List.of()), actor.id()), "Ask a manager to assign you to a sorting team.");
            verifyStaff(conn, team.driverId(), RoleCode.SUPPLY_DRIVER);
            var helpers = query(conn, """
                    SELECT h.helper_id FROM sorting_team_helpers h JOIN users u ON u.id=h.helper_id
                    JOIN roles role ON role.id=u.role_id WHERE h.team_id=? AND u.active=1 AND role.code='SORTING_HELPER'
                    """, row -> row.getLong(1), team.id());
            require(!helpers.isEmpty(), "Assign an active helper to this sorting team.");
            verifyStation(conn, team.stationId());
            long requestId = insertId(conn, """
                    INSERT INTO sorting_requests(sorter_id,team_id,driver_id,station_id,target_grams,
                    bonus_basis_points,requested_lot_id,requested_at) VALUES (?,?,?,?,?,?,?,?) RETURNING id
                    """, actor.id(), team.id(), team.driverId(), team.stationId(),
                    team.targetGrams(), team.bonusBasisPoints(), lot.id(), now());
            for (long helper : helpers) {
                changed(conn, "INSERT INTO sorting_request_helpers(request_id,helper_id) VALUES (?,?)", requestId, helper);
            }
            alert(conn, team.driverId(), i18n.t("sorting.alert.request", requestId),
                    actor.displayName() + " · " + team.name() + " · " + lot.material() + " · " + lot.reference()
                            + " @ " + lot.locationCode());
            return requestId;
        });
        notifications.publishCommitted();
        return id;
    }

    public void cancelRequest(long requestId) {
        User actor = actor(RoleCode.SORTER);
        db.runInTransaction(conn -> require(changed(conn, """
                UPDATE sorting_requests SET cancelled_at=? WHERE id=? AND sorter_id=?
                    AND delivered_at IS NULL AND cancelled_at IS NULL
                """, now(), requestId, actor.id()) == 1, "Only your undelivered requests can be cancelled."));
    }

    /** Delivers the raw lot the sorter called for; the weight is whatever the driver actually brings. */
    public void deliver(long requestId, BigDecimal deliveredKg) {
        deliverFrom(requestId, null, deliveredKg);
    }

    /** For a request made before lots could be called for: the driver chooses the lot at delivery. */
    public void deliver(long requestId, long lotId, BigDecimal deliveredKg) {
        deliverFrom(requestId, lotId, deliveredKg);
    }

    private void deliverFrom(long requestId, Long chosenLot, BigDecimal deliveredKg) {
        User actor = actor(RoleCode.SUPPLY_DRIVER);
        long weight = grams(deliveredKg, false);
        db.runInTransaction(conn -> {
            List<Long> called = query(conn, "SELECT requested_lot_id FROM sorting_requests WHERE id=? AND driver_id=?",
                    r -> nullableLong(r, "requested_lot_id"), requestId, actor.id());
            require(!called.isEmpty(), "The request is unavailable.");
            Long calledLot = called.get(0);
            require(calledLot == null || chosenLot == null || calledLot.equals(chosenLot),
                    "This request is for a different raw lot.");
            Long lotId = calledLot != null ? calledLot : chosenLot;
            require(lotId != null, "Select the raw lot to deliver from.");
            require(changed(conn, """
                    UPDATE sorting_requests SET lot_id=?,delivered_grams=?,delivered_at=? WHERE id=? AND driver_id=?
                        AND delivered_at IS NULL AND cancelled_at IS NULL AND (requested_grams IS NULL OR requested_grams>=?)
                    """, lotId, weight, now(), requestId, actor.id(), weight) == 1,
                    "The request is unavailable or the delivery exceeds the requested weight.");
            require(changed(conn, "UPDATE sorting_raw_lots SET available_grams=available_grams-? WHERE id=? AND available_grams>=?",
                    weight, lotId, weight) == 1, "The raw lot does not have enough stock.");
            long sorter = scalar(conn, "SELECT sorter_id FROM sorting_requests WHERE id=?", requestId);
            alert(conn, sorter, i18n.t("sorting.alert.delivery", requestId), kg(weight) + " kg");
        });
        notifications.publishCommitted();
    }

    public long reportBag(long requestId, long target, long garment, long grade, BigDecimal weightKg) {
        User actor = actor(RoleCode.SORTER);
        long weight = grams(weightKg, false);
        require(weight % 10 == 0, "Enter finished bag weight with up to two decimal places for the QR label.");
        long id = db.executeInTransaction(conn -> {
            List<Long> ids = query(conn, """
                    INSERT INTO sorting_bags(request_id,target_group_id,garment_type_id,quality_grade_id,weight_grams,reported_at)
                    SELECT r.id,?,?,?,?,? FROM sorting_requests r WHERE r.id=? AND r.sorter_id=?
                        AND r.delivered_at IS NOT NULL AND r.finished_at IS NULL
                        AND r.delivered_grams-COALESCE((SELECT SUM(weight_grams) FROM sorting_bags b WHERE b.request_id=r.id),0)>=?
                        AND EXISTS (SELECT 1 FROM target_groups WHERE id=? AND active=1)
                        AND EXISTS (SELECT 1 FROM garment_types WHERE id=? AND active=1)
                        AND EXISTS (SELECT 1 FROM quality_grades WHERE id=? AND active=1)
                    RETURNING id
                    """, r -> r.getLong(1), target, garment, grade, weight, now(), requestId, actor.id(),
                    weight, target, garment, grade);
            long bagId = first(ids, "The bag needs an open delivery, valid classifications, and enough remaining weight.");
            var helpers = query(conn, "SELECT helper_id FROM sorting_request_helpers WHERE request_id=?",
                    row -> row.getLong(1), requestId);
            for (long helper : helpers) {
                alert(conn, helper, i18n.t("sorting.alert.pack", bagId), actor.displayName() + " · " + kg(weight) + " kg");
            }
            return bagId;
        });
        notifications.publishCommitted();
        return id;
    }

    public void finish(long requestId, BigDecimal processedKg) {
        User actor = actor(RoleCode.SORTER);
        long weight = grams(processedKg, true);
        db.runInTransaction(conn -> {
            require(changed(conn, """
                    UPDATE sorting_requests SET processed_grams=?,finished_at=?,work_day=? WHERE id=? AND sorter_id=?
                        AND delivered_at IS NOT NULL AND finished_at IS NULL AND delivered_grams>=?
                        AND COALESCE((SELECT SUM(weight_grams) FROM sorting_bags WHERE request_id=?),0)<=?
                    """, weight, now(), today().toString(), requestId, actor.id(), weight, requestId, weight) == 1,
                    "Processed weight must cover all recorded bags and fit the delivered weight.");
            changed(conn, """
                    UPDATE sorting_raw_lots SET available_grams=available_grams+(
                        SELECT delivered_grams-processed_grams FROM sorting_requests WHERE id=?
                    ) WHERE id=(SELECT lot_id FROM sorting_requests WHERE id=?)
                    """, requestId, requestId);
        });
    }

    public void pack(long bagId, long packaging) {
        User actor = actor(RoleCode.SORTING_HELPER);
        db.runInTransaction(conn -> {
            require(changed(conn, """
                    UPDATE sorting_bags SET package_type_id=?,packed_at=?,packed_by=?,pack_day=? WHERE id=? AND packed_at IS NULL
                      AND EXISTS (SELECT 1 FROM package_types WHERE id=? AND active=1)
                      AND EXISTS (SELECT 1 FROM sorting_request_helpers h WHERE h.request_id=sorting_bags.request_id AND h.helper_id=?)
                    """, packaging, now(), actor.id(), today().toString(), bagId, packaging, actor.id()) == 1,
                    "The bag is unavailable or the packaging type is inactive.");
            var recipients = query(conn, """
                    SELECT u.id FROM users u JOIN roles role ON role.id=u.role_id
                    JOIN sorting_requests r ON u.assigned_station_id=r.station_id
                    JOIN sorting_bags b ON b.request_id=r.id
                    WHERE b.id=? AND u.active=1 AND role.code='OPERATOR'
                    """, r -> r.getLong(1), bagId);
            for (long recipient : recipients) alert(conn, recipient, i18n.t("sorting.alert.label", bagId), actor.displayName());
        });
        notifications.publishCommitted();
    }

    public InventoryPackage registerBag(long bagId) {
        User actor = actor();
        require(management(actor) || actor.roleCode() == RoleCode.OPERATOR, "Only station managers may register finished bags.");
        record BagInput(long station, long target, long garment, long grade, long packaging, long weight, long request) { }
        BagInput input = db.executeInTransaction(conn -> first(query(conn, """
                SELECT r.station_id,b.* FROM sorting_bags b JOIN sorting_requests r ON r.id=b.request_id
                WHERE b.id=? AND b.packed_at IS NOT NULL AND b.registered_at IS NULL
                """, r -> new BagInput(r.getLong("station_id"), r.getLong("target_group_id"), r.getLong("garment_type_id"),
                r.getLong("quality_grade_id"), r.getLong("package_type_id"), r.getLong("weight_grams"), r.getLong("request_id")),
                bagId), "Bag must be packed and waiting for registration."));
        require(management(actor) || java.util.Objects.equals(actor.assignedStationId(), input.station()),
                "This bag belongs to a different labelling station.");
        WarehouseLocation station = locations.findById(input.station()).orElseThrow();
        var preview = inventory.buildPreviewPackages(
                first(dictionaries.findTargetGroups(false).stream().filter(x -> x.id()==input.target()).toList(), "Unknown target group."),
                first(dictionaries.findGarmentTypes(false).stream().filter(x -> x.id()==input.garment()).toList(), "Unknown product type."),
                first(dictionaries.findQualityGrades(false).stream().filter(x -> x.id()==input.grade()).toList(), "Unknown quality grade."),
                first(dictionaries.findPackageTypes(false).stream().filter(x -> x.id()==input.packaging()).toList(), "Unknown packaging."),
                station, kg(input.weight()), 1, "Sorting bag #" + bagId + " / Raw batch #" + input.request(), null,
                actor.id(), actor.roleCode());
        InventoryPackage registered = db.executeInTransaction(conn -> {
            require(changed(conn, """
                    UPDATE sorting_bags SET registered_at=?,registered_by=? WHERE id=? AND packed_at IS NOT NULL AND registered_at IS NULL
                    """, now(), actor.id(), bagId) == 1, "This bag was already registered.");
            WarehouseLocation current = verifyStation(conn, input.station());
            require(current.usedWeightKg().add(kg(input.weight())).compareTo(current.maxCapacityKg()) <= 0,
                    "There is not enough capacity at the labelling station.");
            List<Long> created = inventory.insertPreviewedPackages(conn, preview, current, actor.id());
            long packageId = created.get(0);
            changed(conn, "UPDATE sorting_bags SET inventory_package_id=? WHERE id=?", packageId, bagId);
            InventoryPackage pkg = packages.findById(conn, packageId).orElseThrow();
            long driver = scalar(conn, "SELECT driver_id FROM sorting_requests WHERE id=?", input.request());
            notifications.record(conn, driver, AppNotification.CAT_PUTAWAY,
                    i18n.t("sorting.alert.putaway", bagId), current.code() + " · " + pkg.qrCode(), AppModule.PUTAWAY.viewKey());
            return pkg;
        });
        notifications.publishCommitted();
        return registered;
    }

    public static BigDecimal kg(long grams) { return BigDecimal.valueOf(grams, 3).stripTrailingZeros(); }

    /** Stock is kept in grams; raw loads are talked about in tonnes. */
    public static BigDecimal tonnes(long grams) { return BigDecimal.valueOf(grams, 6).stripTrailingZeros(); }

    private List<Team> teams(Connection conn) throws SQLException {
        Map<Long, List<Long>> members = new LinkedHashMap<>();
        try (var ps = conn.prepareStatement("SELECT sorter_id,team_id FROM sorting_team_members ORDER BY sorter_id");
             var rows = ps.executeQuery()) {
            while (rows.next()) members.computeIfAbsent(rows.getLong("team_id"), ignored -> new ArrayList<>()).add(rows.getLong("sorter_id"));
        }
        Map<Long, List<Long>> helpers = new LinkedHashMap<>();
        try (var ps = conn.prepareStatement("SELECT team_id,helper_id FROM sorting_team_helpers ORDER BY helper_id");
             var rows = ps.executeQuery()) {
            while (rows.next()) helpers.computeIfAbsent(rows.getLong("team_id"), ignored -> new ArrayList<>()).add(rows.getLong("helper_id"));
        }
        return query(conn, "SELECT * FROM sorting_teams ORDER BY name", r -> {
            long id = r.getLong("id");
            return mapTeam(r, List.copyOf(helpers.getOrDefault(id, List.of())), List.copyOf(members.getOrDefault(id, List.of())));
        });
    }

    private List<Daily> daily(Connection conn, User actor, LocalDate day) throws SQLException {
        if (actor.roleCode() != RoleCode.SORTER && actor.roleCode() != RoleCode.SORTING_HELPER && !management(actor)) return List.of();
        String visibility = switch (actor.roleCode()) {
            case SORTER -> " AND r.sorter_id=?";
            case SORTING_HELPER -> " AND EXISTS (SELECT 1 FROM sorting_request_helpers h WHERE h.request_id=r.id AND h.helper_id=?)";
            default -> "";
        };
        Object[] params = visibility.isEmpty() ? new Object[]{day.toString()} : new Object[]{day.toString(), actor.id()};
        var progress = new ArrayList<>(query(conn, """
                SELECT u.full_name AS sorter,
                       (SELECT GROUP_CONCAT(DISTINCT h.full_name) FROM sorting_requests matched
                        JOIN sorting_request_helpers rh ON rh.request_id=matched.id
                        JOIN users h ON h.id=rh.helper_id
                        WHERE matched.sorter_id=r.sorter_id AND matched.work_day=r.work_day) AS helper,
                       SUM(r.processed_grams) AS processed, MAX(r.target_grams) AS target, MAX(r.bonus_basis_points) AS rate
                FROM sorting_requests r JOIN users u ON u.id=r.sorter_id
                WHERE r.work_day=?
                """ + visibility + " GROUP BY r.sorter_id ORDER BY sorter",
                r -> new Daily(r.getString("sorter"), r.getString("helper"), r.getLong("processed"), r.getLong("target"),
                        nullableInt(r, "rate")), params));
        if (!day.equals(today())) return List.copyOf(progress);

        String rosterVisibility = switch (actor.roleCode()) {
            case SORTER -> " AND m.sorter_id=?";
            case SORTING_HELPER -> " AND EXISTS (SELECT 1 FROM sorting_team_helpers h WHERE h.team_id=t.id AND h.helper_id=?)";
            default -> "";
        };
        progress.addAll(query(conn, """
                SELECT u.full_name AS sorter,
                       (SELECT GROUP_CONCAT(h.full_name, ', ') FROM sorting_team_helpers th
                        JOIN users h ON h.id=th.helper_id WHERE th.team_id=t.id) AS helper,
                       t.target_grams,t.bonus_basis_points
                FROM sorting_team_members m JOIN sorting_teams t ON t.id=m.team_id
                JOIN users u ON u.id=m.sorter_id
                WHERE u.active=1 AND NOT EXISTS (
                    SELECT 1 FROM sorting_requests r WHERE r.sorter_id=m.sorter_id AND r.work_day=?
                )
                """ + rosterVisibility + " ORDER BY sorter",
                r -> new Daily(r.getString("sorter"), r.getString("helper"), 0, r.getLong("target_grams"),
                        nullableInt(r, "bonus_basis_points")), params));
        return List.copyOf(progress);
    }

    private User actor(RoleCode role) {
        User actor = actor();
        require(actor.roleCode() == role, "This action belongs to a different sorting role.");
        return actor;
    }

    private User actor() {
        require(session.canAccessModule(AppModule.SORTING), "Sorting access is required.");
        return session.requireCurrentUser();
    }

    /** Who may receive raw stock, keep the teams and see every request: management and the production leaders. */
    private static boolean management(User user) {
        return user.roleCode().runsProduction();
    }

    private record Scope(String where, Object[] params) { }

    private static Scope scope(User actor, String alias) {
        if (actor.roleCode() == RoleCode.SORTING_HELPER) {
            return new Scope(" WHERE EXISTS (SELECT 1 FROM sorting_request_helpers h WHERE h.request_id="
                    + alias + ".id AND h.helper_id=?)", new Object[]{actor.id()});
        }
        String field = switch (actor.roleCode()) {
            case SORTER -> "sorter_id";
            case SUPPLY_DRIVER -> "driver_id";
            case OPERATOR -> "station_id";
            default -> "";
        };
        if (field.isEmpty()) return new Scope("", new Object[0]);
        Long value = actor.roleCode() == RoleCode.OPERATOR ? actor.assignedStationId() : Long.valueOf(actor.id());
        return new Scope(" WHERE " + alias + "." + field + "=?", new Object[]{value});
    }

    private void alert(Connection conn, long user, String title, String message) throws SQLException {
        notifications.record(conn, user, AppNotification.CAT_SORTING, title, message, VIEW);
    }

    private static void verifyStaff(Connection conn, long user, RoleCode role) throws SQLException {
        require(scalar(conn, "SELECT COUNT(*) FROM users u JOIN roles r ON r.id=u.role_id WHERE u.id=? AND u.active=1 AND r.code=?",
                user, role.name()) == 1, "Select an active user with role " + role + ".");
    }

    private WarehouseLocation verifyStation(Connection conn, long station) throws SQLException {
        return locations.findById(conn, station).filter(l -> l.active() && l.locationType() == LocationType.OPERATOR_STATION)
                .orElseThrow(() -> new IllegalStateException("Select an active labelling station."));
    }

    private static long grams(BigDecimal kg, boolean allowZero) {
        try {
            long amount = kg == null ? -1 : kg.movePointRight(3).longValueExact();
            if (amount >= (allowZero ? 0 : 1) && amount <= 1_000_000_000L) return amount;
        } catch (ArithmeticException ignored) { }
        throw new IllegalArgumentException("Enter a valid weight in kg with up to three decimal places.");
    }

    private static int basisPoints(BigDecimal percent) {
        try {
            int rate = percent.movePointRight(2).intValueExact();
            if (rate >= 0 && rate <= 10_000) return rate;
        } catch (ArithmeticException ignored) { }
        throw new IllegalArgumentException("Enter a bonus percentage between 0 and 100.");
    }

    /** Sorting records UTC instants; only the source of "now" is shared with the rest of the app. */
    private String now() { return BusinessClock.clock().instant().toString(); }

    private static Request mapRequest(ResultSet r) throws SQLException {
        return new Request(r.getLong("id"), r.getString("sorter"), r.getString("helper"), r.getString("driver"),
                r.getString("team"), r.getString("station"), r.getString("lot"), nullableLong(r,"requested_grams"),
                nullableLong(r,"delivered_grams"), nullableLong(r,"processed_grams"), r.getLong("output"), r.getString("requested_at"),
                r.getString("delivered_at"), r.getString("finished_at"), r.getString("cancelled_at"),
                nullableLong(r,"requested_lot_id"), r.getString("material"));
    }

    private static Lot mapLot(ResultSet r) throws SQLException {
        return new Lot(r.getLong("id"), r.getString("reference"), r.getString("material"), r.getString("source_area"),
                nullableLong(r, "location_id"), r.getString("location_code"), r.getLong("received_grams"),
                r.getLong("available_grams"), r.getString("received_at"));
    }

    private static Bag mapBag(ResultSet r) throws SQLException {
        return new Bag(r.getLong("id"), r.getLong("request_id"), r.getString("sorter"), r.getString("station"),
                r.getString("target"), r.getString("garment"), r.getString("grade"), r.getString("packaging"),
                r.getLong("weight_grams"), r.getString("reported_at"), r.getString("packed_at"), r.getString("registered_at"), r.getString("qr"), r.getString("packer"));
    }

    private static Team mapTeam(ResultSet r, List<Long> helpers, List<Long> sorters) throws SQLException {
        return new Team(r.getLong("id"), r.getString("name"), helpers, r.getLong("driver_id"),
                r.getLong("station_id"), r.getLong("target_grams"), nullableInt(r, "bonus_basis_points"), sorters);
    }

    private static Long nullableLong(ResultSet r, String col) throws SQLException {
        long value = r.getLong(col);
        return r.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet r, String col) throws SQLException {
        int value = r.getInt(col);
        return r.wasNull() ? null : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static <T> T first(List<T> values, String message) {
        require(!values.isEmpty(), message);
        return values.get(0);
    }

    @FunctionalInterface private interface Row<T> { T read(ResultSet result) throws SQLException; }

    private static <T> List<T> query(Connection conn, String sql, Row<T> row, Object... params) throws SQLException {
        try (var ps = statement(conn, sql, params); var rs = ps.executeQuery()) {
            var rows = new ArrayList<T>();
            while (rs.next()) rows.add(row.read(rs));
            return List.copyOf(rows);
        }
    }

    private static long insertId(Connection conn, String sql, Object... params) throws SQLException {
        return first(query(conn, sql, r -> r.getLong(1), params), "No record was created.");
    }

    private static long scalar(Connection conn, String sql, Object... params) throws SQLException {
        return query(conn, sql, r -> r.getLong(1), params).get(0);
    }

    private static int changed(Connection conn, String sql, Object... params) throws SQLException {
        try (var ps = statement(conn, sql, params)) { return ps.executeUpdate(); }
    }

    private static PreparedStatement statement(Connection conn, String sql, Object... params) throws SQLException {
        var ps = conn.prepareStatement(sql);
        try {
            for (int i=0; i<params.length; i++) ps.setObject(i+1, params[i]);
            return ps;
        } catch (SQLException ex) {
            ps.close();
            throw ex;
        }
    }
}
