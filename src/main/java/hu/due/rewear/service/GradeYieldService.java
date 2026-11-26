package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.DictionaryDao;
import hu.due.rewear.dao.GradeYieldDao;
import hu.due.rewear.dao.GradeYieldDao.Scope;
import hu.due.rewear.dao.PurchaseLotDao;
import hu.due.rewear.dao.SettingsDao;
import hu.due.rewear.model.GarmentType;
import hu.due.rewear.model.PurchaseLot;
import hu.due.rewear.model.QualityGrade;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import hu.due.rewear.model.YieldBaseline;
import hu.due.rewear.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the sorting line actually produced, by grade, and what that was worth.
 *
 * <p>The application has always measured kilograms through the line. That is the less important number: the
 * premium grade is about a tenth of the weight and over half of the revenue, so a point of grade mix moves
 * the business further than a point of throughput. Everything needed to say so was already in the database —
 * graded bags, their weights, the request they came from, the person who sorted them, the lot the material
 * came out of. What was missing was the arithmetic and something to compare it against.
 *
 * <p>Two choices here are deliberate and worth stating. Shares are of <em>processed input</em>, so the
 * residue — what went in and came out as no graded bag at all — is a line in the cascade like any other,
 * because a mix that counts only saleable output flatters itself. And no target ships with the application:
 * a baseline is captured from this site's own history by a named person, and variance is measured against
 * that, for reasons the baseline record itself sets out.
 */
public final class GradeYieldService {
    private static final Logger LOG = LoggerFactory.getLogger(GradeYieldService.class);

    private final DatabaseManager db;
    private final SessionContext session;
    private final GradeYieldDao yields;
    private final DictionaryDao dictionaries;
    private final PurchaseLotDao purchaseLots;
    private final SettingsDao settings;

    public GradeYieldService(DatabaseManager db, SessionContext session, GradeYieldDao yields,
                             DictionaryDao dictionaries, PurchaseLotDao purchaseLots, SettingsDao settings) {
        this.db = db;
        this.session = session;
        this.yields = yields;
        this.dictionaries = dictionaries;
        this.purchaseLots = purchaseLots;
        this.settings = settings;
    }

    // ---------------------------------------------------------------- the report

    /** One line of the cascade. A null {@code gradeId} is the residue rather than a grade. */
    public record Line(
            Long gradeId,
            String code,
            String name,
            long grams,
            int shareBasisPoints,
            BigDecimal valueEur,
            BigDecimal valueHuf,
            Integer baselineBasisPoints) {

        public boolean isResidue() {
            return gradeId == null;
        }

        public BigDecimal kg() {
            return BigDecimal.valueOf(grams, 3);
        }

        public BigDecimal sharePercent() {
            return BigDecimal.valueOf(shareBasisPoints, 2);
        }

        /** How far this share sits from the baseline, in percentage points, or null without one. */
        public BigDecimal variancePoints() {
            return baselineBasisPoints == null ? null
                    : BigDecimal.valueOf(shareBasisPoints - baselineBasisPoints, 2);
        }
    }

    /** A whole cascade for one scope. */
    public record Report(
            long processedGrams,
            long outputGrams,
            List<Line> lines,
            BigDecimal valueEur,
            BigDecimal valueHuf,
            String baselineName) {

        public boolean isEmpty() {
            return processedGrams == 0;
        }

        public BigDecimal processedKg() {
            return BigDecimal.valueOf(processedGrams, 3);
        }

        /** What came out as a graded bag, against what went in. */
        public BigDecimal recoveryPercent() {
            if (processedGrams == 0) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(outputGrams).multiply(new BigDecimal("100"))
                    .divide(BigDecimal.valueOf(processedGrams), 2, RoundingMode.HALF_UP);
        }

        /** The value of a kilogram that went into the line, which is the figure a purchase price is judged against. */
        public BigDecimal revenuePerProcessedKgEur() {
            if (processedGrams == 0) {
                return BigDecimal.ZERO;
            }
            return valueEur.divide(BigDecimal.valueOf(processedGrams, 3), 4, RoundingMode.HALF_UP);
        }
    }

    /** The cascade for a scope, compared against the active baseline where there is one. */
    public Report report(Scope scope) {
        requireReader();
        long processed = yields.processedGrams(scope);
        List<GradeYieldDao.OutputRow> output = yields.output(scope);
        Optional<YieldBaseline> baseline = yields.findActive();

        Map<Long, QualityGrade> grades = new HashMap<>();
        for (QualityGrade grade : dictionaries.findQualityGrades(false)) {
            grades.put(grade.id(), grade);
        }
        Map<Long, GarmentType> garments = new HashMap<>();
        for (GarmentType garment : dictionaries.findGarmentTypes(false)) {
            garments.put(garment.id(), garment);
        }

        Map<Long, long[]> gramsByGrade = new LinkedHashMap<>();
        Map<Long, BigDecimal[]> valueByGrade = new LinkedHashMap<>();
        long outputGrams = 0;
        for (GradeYieldDao.OutputRow row : output) {
            outputGrams += row.grams();
            gramsByGrade.computeIfAbsent(row.qualityGradeId(), key -> new long[1])[0] += row.grams();
            BigDecimal[] value = valueByGrade.computeIfAbsent(row.qualityGradeId(),
                    key -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal kg = BigDecimal.valueOf(row.grams(), 3);
            QualityGrade grade = grades.get(row.qualityGradeId());
            GarmentType garment = garments.get(row.garmentTypeId());
            value[0] = value[0].add(kg.multiply(priceEur(garment, grade)));
            value[1] = value[1].add(kg.multiply(priceHuf(garment, grade)));
        }

        List<Line> lines = new ArrayList<>();
        BigDecimal totalEur = BigDecimal.ZERO;
        BigDecimal totalHuf = BigDecimal.ZERO;
        long accountedBasisPoints = 0;
        for (Map.Entry<Long, long[]> entry : gramsByGrade.entrySet()) {
            QualityGrade grade = grades.get(entry.getKey());
            long grams = entry.getValue()[0];
            // Floored, so the shares of the grades never add up to more than the whole and the residue
            // absorbs the rounding rather than going negative.
            int share = processed == 0 ? 0 : (int) (grams * 10_000L / processed);
            accountedBasisPoints += share;
            BigDecimal[] value = valueByGrade.get(entry.getKey());
            totalEur = totalEur.add(value[0]);
            totalHuf = totalHuf.add(value[1]);
            lines.add(new Line(entry.getKey(),
                    grade == null ? "?" : grade.code(),
                    grade == null ? "?" : grade.nameEn(),
                    grams, share,
                    value[0].setScale(2, RoundingMode.HALF_UP),
                    value[1].setScale(2, RoundingMode.HALF_UP),
                    baseline.map(b -> b.shareOf(entry.getKey())).orElse(null)));
        }
        lines.sort(Comparator.comparingLong(Line::grams).reversed());

        if (processed > 0) {
            long residueGrams = Math.max(0, processed - outputGrams);
            lines.add(new Line(null, "—", "Residue", residueGrams,
                    (int) Math.max(0, 10_000L - accountedBasisPoints),
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    baseline.map(b -> b.shareOf(null)).orElse(null)));
        }

        return new Report(processed, outputGrams, List.copyOf(lines),
                totalEur.setScale(2, RoundingMode.HALF_UP), totalHuf.setScale(2, RoundingMode.HALF_UP),
                baseline.map(YieldBaseline::name).orElse(null));
    }

    /**
     * The price convention already used for stock valuation: a product's own price where one is set, the
     * grade's price otherwise. Keeping to it means the two screens cannot quietly disagree.
     */
    private static BigDecimal priceEur(GarmentType garment, QualityGrade grade) {
        if (garment != null && garment.priceEurPerKg() != null && garment.priceEurPerKg().signum() > 0) {
            return garment.priceEurPerKg();
        }
        return grade == null || grade.defaultPricePerKg() == null ? BigDecimal.ZERO : grade.defaultPricePerKg();
    }

    private static BigDecimal priceHuf(GarmentType garment, QualityGrade grade) {
        if (garment != null && garment.priceHufPerKg() != null && garment.priceHufPerKg().signum() > 0) {
            return garment.priceHufPerKg();
        }
        return grade == null || grade.defaultPriceHufPerKg() == null
                ? BigDecimal.ZERO : grade.defaultPriceHufPerKg();
    }

    // ---------------------------------------------------------------- margin on a lot

    /**
     * What a purchase lot earned against what it cost.
     *
     * <p>The currency is stated rather than assumed. Grade prices are held in euro and in forint, a lot is
     * bought in one or the other, and the figure is worth nothing if somebody has to guess which it is in —
     * so the lot's own currency wins and the conversion rate is reported alongside.
     */
    public record LotMargin(
            String lotReference,
            String currency,
            BigDecimal processedKg,
            BigDecimal cost,
            BigDecimal revenue,
            BigDecimal margin,
            BigDecimal eurToHufRate,
            Report yield) {

        /** Margin as a share of cost, or null when the lot cost nothing — own collection, typically. */
        public BigDecimal marginPercent() {
            if (cost.signum() == 0) {
                return null;
            }
            return margin.multiply(new BigDecimal("100")).divide(cost, 1, RoundingMode.HALF_UP);
        }
    }

    public LotMargin marginForPurchaseLot(long purchaseLotId) {
        requireReader();
        PurchaseLot lot = purchaseLots.findById(purchaseLotId)
                .orElseThrow(() -> new IllegalArgumentException("No such purchase lot."));
        Report yield = report(new Scope(purchaseLotId, null, null, null, null, null));
        BigDecimal processedKg = yield.processedKg();
        String currency = lot.currency() == null ? "EUR" : lot.currency();
        BigDecimal rate = settings.getEurToHufRate();
        // Only what has actually been sorted is costed: material still sitting in the warehouse has not
        // earned anything yet, and charging the whole lot against a part of its output would read as a loss.
        BigDecimal cost = processedKg.multiply(lot.purchasePricePerKg()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal revenue = "HUF".equalsIgnoreCase(currency) ? yield.valueHuf() : yield.valueEur();
        return new LotMargin(lot.reference(), currency, processedKg, cost, revenue,
                revenue.subtract(cost), rate, yield);
    }

    // ---------------------------------------------------------------- baselines

    /**
     * Freezes what this line produced over a stated period as the figure to measure against.
     *
     * @param activate whether this becomes the baseline variance is reported against
     */
    public long captureBaseline(String name, LocalDate from, LocalDate to, String notes, boolean activate) {
        User actor = requireProductionLead();
        String label = name == null ? "" : name.strip();
        if (label.isEmpty() || label.length() > 80) {
            throw new IllegalArgumentException("Give the baseline a name of up to 80 characters.");
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Give a period that starts before it ends.");
        }
        if (to.isAfter(BusinessClock.today())) {
            throw new IllegalArgumentException("A baseline can only be taken from work already done.");
        }
        Scope scope = Scope.all().withPeriod(from, to);
        long processed = yields.processedGrams(scope);
        if (processed == 0) {
            throw new IllegalArgumentException("Nothing was sorted between " + from + " and " + to
                    + ", so there is no mix to learn from.");
        }
        Report cascade = report(scope);
        Map<Long, Integer> shares = new LinkedHashMap<>();
        for (Line line : cascade.lines()) {
            shares.put(line.gradeId(), line.shareBasisPoints());
        }
        long id = db.executeInTransaction(conn -> {
            long baselineId = yields.insertBaseline(conn, label, from, to, processed, shares,
                    actor.id(), BusinessClock.now(), notes == null || notes.isBlank() ? null : notes.strip());
            if (activate) {
                yields.activate(conn, baselineId);
            }
            return baselineId;
        });
        LOG.info("Captured yield baseline {} over {}..{} from {} g of processed input",
                label, from, to, processed);
        return id;
    }

    public void activateBaseline(long baselineId) {
        requireProductionLead();
        db.runInTransaction(conn -> yields.activate(conn, baselineId));
    }

    public List<YieldBaseline> baselines() {
        requireReader();
        return yields.findAll();
    }

    public Optional<YieldBaseline> activeBaseline() {
        return yields.findActive();
    }

    // ---------------------------------------------------------------- guards

    private User requireReader() {
        User actor = session.requireCurrentUser();
        if (!RoleCode.MANAGEMENT.contains(actor.roleCode())
                && actor.roleCode() != RoleCode.PRODUCTION_LEAD
                && actor.roleCode() != RoleCode.SHIFT_LEAD) {
            throw new IllegalStateException("Only management and the production leaders may read yield figures.");
        }
        return actor;
    }

    /** Capturing a baseline sets what everybody is then measured against, so it is the production lead's call. */
    private User requireProductionLead() {
        User actor = session.requireCurrentUser();
        if (!RoleCode.MANAGEMENT.contains(actor.roleCode()) && actor.roleCode() != RoleCode.PRODUCTION_LEAD) {
            throw new IllegalStateException("Only management or the production lead may set the yield baseline.");
        }
        return actor;
    }
}
