package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.PurchaseLotDao;
import hu.due.rewear.model.CollectionChannel;
import hu.due.rewear.model.LotLegalStatus;
import hu.due.rewear.model.PurchaseLot;
import hu.due.rewear.model.PurchaseLotEvent;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Purchase lots: what was bought, from whom, from where, and what it legally is.
 *
 * <p>The judgement this class protects is the one about legal status. Separately collected used textiles are
 * waste from the moment of collection unless the end user handed them over directly and they were
 * professionally assessed as fit for re-use at the collection point. Both halves of that matter, so both are
 * enforced: the channel has to be a direct handover, and the assessment has to name a person, a place and a
 * reason. A status recorded any other way would look exactly like a real one in the database and be worth
 * nothing the first time anybody asked who decided it.
 */
public final class PurchaseLotService {
    private static final Logger LOG = LoggerFactory.getLogger(PurchaseLotService.class);

    private final DatabaseManager db;
    private final SessionContext session;
    private final PurchaseLotDao lots;

    public PurchaseLotService(DatabaseManager db, SessionContext session, PurchaseLotDao lots) {
        this.db = db;
        this.session = session;
        this.lots = lots;
    }

    // ---------------------------------------------------------------- recording a purchase

    /** What somebody fills in when material arrives. */
    public record NewLot(
            String reference,
            Long supplierId,
            String originCountry,
            CollectionChannel channel,
            String customsCode,
            String customsDescription,
            BigDecimal pricePerKg,
            String currency,
            BigDecimal grossWeightKg,
            BigDecimal netWeightKg,
            Long invoiceId,
            String notes,
            LocalDate receivedOn) {
    }

    public long record(NewLot entry) {
        User actor = requireManagement();
        String reference = text(entry.reference(), 60, "a lot reference");
        String country = country(entry.originCountry());
        require(entry.channel() != null, "Choose how the material reached the company.");
        require(entry.grossWeightKg() != null && entry.grossWeightKg().signum() > 0,
                "Enter the gross weight in kilograms.");
        require(entry.netWeightKg() != null && entry.netWeightKg().signum() > 0,
                "Enter the net weight in kilograms.");
        require(entry.netWeightKg().compareTo(entry.grossWeightKg()) <= 0,
                "The net weight cannot be more than the gross weight.");
        BigDecimal price = entry.pricePerKg() == null ? BigDecimal.ZERO : entry.pricePerKg();
        require(price.signum() >= 0, "A purchase price cannot be negative.");
        String currency = entry.currency() == null || entry.currency().isBlank()
                ? "EUR" : entry.currency().trim().toUpperCase(Locale.ROOT);
        LocalDate received = entry.receivedOn() == null ? BusinessClock.today() : entry.receivedOn();
        require(!received.isAfter(BusinessClock.today()), "A lot cannot be received in the future.");

        return db.executeInTransaction(conn -> {
            require(lots.findByReference(conn, reference).isEmpty(),
                    "A purchase lot with reference " + reference + " already exists.");
            LocalDateTime now = BusinessClock.now();
            long id = lots.insert(conn, PurchaseLotDao.draft(reference, entry.supplierId(), country,
                    entry.channel(), trimOrNull(entry.customsCode()), trimOrNull(entry.customsDescription()),
                    price, currency, entry.grossWeightKg(), entry.netWeightKg(), entry.invoiceId(),
                    trimOrNull(entry.notes()), actor.id(), received), now);
            lots.addEvent(conn, id, "RECEIVED",
                    entry.channel() + " · " + country + " · " + entry.netWeightKg().toPlainString() + " kg net",
                    actor.id(), now);
            return id;
        });
    }

    /** Corrects the commercial details of a lot. The legal status is not touched here; that is an assessment. */
    public void update(long lotId, NewLot entry) {
        User actor = requireManagement();
        require(entry.grossWeightKg() != null && entry.netWeightKg() != null
                && entry.netWeightKg().compareTo(entry.grossWeightKg()) <= 0,
                "The net weight cannot be more than the gross weight.");
        String country = country(entry.originCountry());
        db.runInTransaction(conn -> {
            PurchaseLot existing = lots.findById(conn, lotId)
                    .orElseThrow(() -> new IllegalArgumentException("No such purchase lot."));
            // Changing the channel can invalidate an assessment that only that channel permitted, so it is
            // refused outright rather than silently leaving a status the new channel could never justify.
            require(existing.collectionChannel() == entry.channel()
                            || existing.legalStatus() != LotLegalStatus.FIT_FOR_REUSE,
                    "This lot is recorded as fit for re-use, which only a direct handover allows."
                            + " Re-assess it before changing how it arrived.");
            LocalDateTime now = BusinessClock.now();
            lots.update(conn, new PurchaseLot(lotId, existing.reference(), entry.supplierId(), null, country,
                    entry.channel(), trimOrNull(entry.customsCode()), trimOrNull(entry.customsDescription()),
                    entry.pricePerKg() == null ? BigDecimal.ZERO : entry.pricePerKg(),
                    entry.currency(), entry.grossWeightKg(), entry.netWeightKg(), existing.legalStatus(),
                    existing.assessedBy(), null, existing.assessedAt(), existing.assessmentBasis(),
                    existing.assessmentPlace(), entry.invoiceId(), null, trimOrNull(entry.notes()),
                    existing.receivedBy(), null, existing.receivedOn(), null, null), now);
            lots.addEvent(conn, lotId, "UPDATED", "Commercial details corrected", actor.id(), now);
        });
    }

    // ---------------------------------------------------------------- the assessment

    /**
     * Records the human judgement of whether the material is waste or fit for re-use.
     *
     * <p>Fit for re-use is only available where the end user handed the material over directly, and only with
     * a place and a reason recorded alongside the person. Everything else is waste from the moment it was
     * collected, which is the default this application will not let anybody quietly type their way out of.
     */
    public void assess(long lotId, LotLegalStatus status, String basis, String place) {
        User actor = requireAssessor();
        require(status == LotLegalStatus.WASTE || status == LotLegalStatus.FIT_FOR_REUSE,
                "An assessment has to reach a conclusion.");
        String reason = text(basis, 500, "what the assessment was based on");
        String where = text(place, 120, "where the assessment was made");
        db.runInTransaction(conn -> {
            PurchaseLot lot = lots.findById(conn, lotId)
                    .orElseThrow(() -> new IllegalArgumentException("No such purchase lot."));
            if (status == LotLegalStatus.FIT_FOR_REUSE) {
                require(lot.collectionChannel().allowsReuseAssessment(),
                        "Material that arrived as " + lot.collectionChannel() + " is waste from the moment it"
                                + " was collected. Only a direct handover from the end user can be assessed"
                                + " as fit for re-use.");
            }
            LocalDateTime now = BusinessClock.now();
            lots.recordAssessment(conn, lotId, status, actor.id(), reason, where, now);
            lots.addEvent(conn, lotId, "ASSESSED",
                    status + " at " + where + " — " + reason, actor.id(), now);
        });
        LOG.info("Purchase lot {} assessed as {} by user {}", lotId, status, actor.id());
    }

    // ---------------------------------------------------------------- provenance

    /** Where one package came from, as far back as the records go. */
    public record Provenance(
            String packageQr,
            String rawLotReference,
            String purchaseLotReference,
            String supplierName,
            String originCountry,
            CollectionChannel channel,
            LotLegalStatus legalStatus) {

        public boolean isKnown() {
            return purchaseLotReference != null;
        }
    }

    /**
     * Traces every package on an order back to the material it was sorted from.
     *
     * <p>The join does the work rather than a copied field on the package: the channel is recorded once, on
     * the lot, so there is no second copy to drift out of step with the first.
     */
    public List<Provenance> traceOrder(long orderId) {
        String sql = """
                SELECT ip.qr_code, rl.reference AS raw_reference, pl.reference AS purchase_reference,
                       c.name AS supplier_name, pl.origin_country, pl.collection_channel, pl.legal_status
                FROM order_items oi
                JOIN inventory_packages ip ON ip.id = oi.package_id
                LEFT JOIN sorting_bags b ON b.inventory_package_id = ip.id
                LEFT JOIN sorting_requests r ON r.id = b.request_id
                LEFT JOIN sorting_raw_lots rl ON rl.id = r.lot_id
                LEFT JOIN purchase_lots pl ON pl.id = rl.purchase_lot_id
                LEFT JOIN customers c ON c.id = pl.supplier_id
                WHERE oi.order_id = ?
                ORDER BY ip.qr_code
                """;
        return db.executeInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Provenance> trace = new ArrayList<>();
                    while (rs.next()) {
                        String channel = rs.getString("collection_channel");
                        String status = rs.getString("legal_status");
                        trace.add(new Provenance(
                                rs.getString("qr_code"),
                                rs.getString("raw_reference"),
                                rs.getString("purchase_reference"),
                                rs.getString("supplier_name"),
                                rs.getString("origin_country"),
                                channel == null ? null : CollectionChannel.valueOf(channel),
                                status == null ? null : LotLegalStatus.valueOf(status)));
                    }
                    return trace;
                }
            }
        });
    }

    /**
     * The collection channels present on one order.
     *
     * <p>More than one is worth knowing about before the goods leave. Mixing destroys the classification of
     * the whole consignment, and a shipment whose paperwork claims a single channel while its contents came
     * from several is the kind of error that is discovered by somebody else, at a border.
     */
    public Set<CollectionChannel> channelsOnOrder(long orderId) {
        Set<CollectionChannel> channels = new LinkedHashSet<>();
        for (Provenance line : traceOrder(orderId)) {
            if (line.channel() != null) {
                channels.add(line.channel());
            }
        }
        return channels;
    }

    /** True when an order draws on more than one collection channel, or on material of unknown provenance. */
    public boolean isMixedConsignment(long orderId) {
        List<Provenance> trace = traceOrder(orderId);
        if (trace.isEmpty()) {
            return false;
        }
        return channelsOnOrder(orderId).size() > 1 || trace.stream().anyMatch(line -> !line.isKnown());
    }

    // ---------------------------------------------------------------- reading

    public List<PurchaseLot> all() {
        return lots.findAll();
    }

    public Optional<PurchaseLot> find(long lotId) {
        return lots.findById(lotId);
    }

    public List<PurchaseLotEvent> history(long lotId) {
        return lots.findEvents(lotId);
    }

    /** Lots that can still be drawn on: assessed, and not yet fully taken into the warehouse. */
    public List<PurchaseLot> openLots() {
        return db.executeInTransaction(conn -> {
            List<PurchaseLot> open = new ArrayList<>();
            for (PurchaseLot lot : lots.findAll()) {
                long takenGrams = lots.receivedGrams(conn, lot.id());
                BigDecimal taken = BigDecimal.valueOf(takenGrams, 3);
                if (taken.compareTo(lot.netWeightKg()) < 0) {
                    open.add(lot);
                }
            }
            return open;
        });
    }

    /** What is left of a lot to take into the warehouse, in kilograms. */
    public BigDecimal remainingKg(long lotId) {
        return db.executeInTransaction(conn -> {
            PurchaseLot lot = lots.findById(conn, lotId)
                    .orElseThrow(() -> new IllegalArgumentException("No such purchase lot."));
            return lot.netWeightKg().subtract(BigDecimal.valueOf(lots.receivedGrams(conn, lotId), 3));
        });
    }

    // ---------------------------------------------------------------- guards

    private User requireManagement() {
        User actor = session.requireCurrentUser();
        require(RoleCode.MANAGEMENT.contains(actor.roleCode()),
                "Only warehouse management may record purchase lots.");
        return actor;
    }

    /**
     * Who may make the legal judgement. Management and the station manager, because the assessment is made
     * where the goods are and the person making it has to be accountable for it by name.
     */
    private User requireAssessor() {
        User actor = session.requireCurrentUser();
        require(RoleCode.MANAGEMENT.contains(actor.roleCode())
                        || actor.roleCode() == RoleCode.OPERATOR
                        || actor.roleCode() == RoleCode.PRODUCTION_LEAD,
                "Only warehouse management, the production lead or a station manager may assess a lot.");
        return actor;
    }

    private static String country(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        require(code.matches("[A-Z]{2}"), "Enter the country of origin as a two-letter code.");
        return code;
    }

    private static String text(String raw, int max, String what) {
        String value = raw == null ? "" : raw.strip();
        require(!value.isEmpty() && value.length() <= max, "Enter " + what + ", up to " + max + " characters.");
        return value;
    }

    private static String trimOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.strip();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
