package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.DowntimeDao;
import hu.due.rewear.dao.DowntimeDao.Scope;
import hu.due.rewear.model.DowntimeEvent;
import hu.due.rewear.model.DowntimeReason;
import hu.due.rewear.model.LossGroup;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where the hours went that were not spent sorting.
 *
 * <p>This record earns its place twice over. A manual sorting line has no machine to interrogate, so the only
 * availability figure the operation can ever have is the one a supervisor writes down. And a bonus paid on
 * kilograms is only defensible if the time somebody spent waiting for material is separable from the time
 * they spent sorting — the same log that answers "where did the shift go" answers "why is my bonus lower than
 * hers", and the second question is the one that gets asked out loud.
 *
 * <p>Three decisions here are deliberate. Nothing closes without a reason, because a stoppage closed blank is
 * the row that comes back as "unknown" when somebody finally adds the hours up. Every reason is marked as the
 * crew's own time or not, and only the attributable minutes are ever held against anybody. And the waiting
 * time the application can already work out for itself — between a sorter calling for material and a driver
 * bringing it — is reported <em>beside</em> the logged stoppages and never added to them, because the shift
 * leader may well have logged that same wait by hand.
 *
 * <p>No target ships, for the same reason grade yield ships none: there is no published availability
 * benchmark for a manual textile sorting line, and a figure from somebody else's plant is not a target.
 */
public final class DowntimeService {
    private static final Logger LOG = LoggerFactory.getLogger(DowntimeService.class);

    /** Minutes lost to one reason, or to one loss group, as a share of everything lost. */
    public record Line(
            Long reasonId,
            String code,
            LossGroup group,
            String nameEn,
            String nameHu,
            boolean attributable,
            long minutes,
            long personMinutes,
            int shareBasisPoints) {

        public String name(String languageCode) {
            return "hu".equalsIgnoreCase(languageCode) ? nameHu : nameEn;
        }

        public BigDecimal sharePercent() {
            return BigDecimal.valueOf(shareBasisPoints, 2);
        }

        public BigDecimal hours() {
            return BigDecimal.valueOf(minutes).divide(new BigDecimal("60"), 2, java.math.RoundingMode.HALF_UP);
        }
    }

    /** Everything that stopped in one scope, and what it cost against the hours that were rostered. */
    public record Report(
            LocalDate from,
            LocalDate to,
            long stoppedMinutes,
            long personMinutes,
            long attributablePersonMinutes,
            long rosteredMinutes,
            long waitingForStockMinutes,
            List<Line> lines,
            List<Line> groups) {

        public boolean isEmpty() {
            return stoppedMinutes == 0 && waitingForStockMinutes == 0;
        }

        /**
         * The share of rostered sorter-time the line was actually able to sort in, in basis points, or null
         * when nothing was rostered — a figure divided by no hours at all is not an availability.
         */
        public Integer availabilityBasisPoints() {
            if (rosteredMinutes <= 0) {
                return null;
            }
            long working = Math.max(0, rosteredMinutes - personMinutes);
            return (int) (working * 10_000L / rosteredMinutes);
        }

        public BigDecimal availabilityPercent() {
            Integer points = availabilityBasisPoints();
            return points == null ? null : BigDecimal.valueOf(points, 2);
        }

        /** The part of the loss anybody could be asked about. The rest is the operation's own doing. */
        public long unattributablePersonMinutes() {
            return Math.max(0, personMinutes - attributablePersonMinutes);
        }
    }

    private final DatabaseManager db;
    private final SessionContext session;
    private final DowntimeDao downtime;

    public DowntimeService(DatabaseManager db, SessionContext session, DowntimeDao downtime) {
        this.db = db;
        this.session = session;
        this.downtime = downtime;
    }

    // ---------------------------------------------------------------- logging a stoppage

    /**
     * Starts the clock on a stoppage that is happening now.
     *
     * @param teamId   the team that stopped, or null for the whole line
     * @param sorterId one person, or null for everybody in scope
     * @return the new stoppage id
     */
    public long open(Long teamId, Long sorterId, Long stationId, LocalDateTime startedAt, Long reasonId,
                     String note) {
        User actor = requireSupervisor();
        LocalDateTime now = BusinessClock.now();
        LocalDateTime started = startedAt == null ? now : startedAt;
        if (started.isAfter(now)) {
            throw new IllegalArgumentException("A stoppage cannot be recorded before it has happened.");
        }
        if (started.isBefore(now.minusDays(1))) {
            throw new IllegalArgumentException("A stoppage that started more than a day ago is over by now."
                    + " Record it with the time it started again.");
        }
        // A reason is welcome this early but not required: what is required is that nothing *closes* blank.
        Long reason = reasonId == null ? null : activeReason(reasonId).id();
        long id = db.executeInTransaction(conn -> {
            if (downtime.hasRunning(conn, teamId, sorterId)) {
                throw new IllegalStateException("A stoppage is already running for these people."
                        + " Close that one first, or the two will count the same minutes twice.");
            }
            int people = downtime.peopleAffected(conn, teamId, sorterId, started.toLocalDate());
            return downtime.insertEvent(conn, started.toLocalDate(), teamId, sorterId, stationId, started,
                    null, null, people, reason, blankToNull(note), actor.id(), now, null, null);
        });
        LOG.info("Downtime {} opened by {} (team {}, sorter {})", id, actor.username(), teamId, sorterId);
        return id;
    }

    /** Closes a running stoppage. The reason is required: that is the whole point of the record. */
    public void close(long eventId, long reasonId, String note) {
        User actor = requireSupervisor();
        LocalDateTime now = BusinessClock.now();
        DowntimeEvent event = downtime.findEvent(eventId)
                .orElseThrow(() -> new IllegalArgumentException("No such stoppage."));
        if (!event.running()) {
            throw new IllegalStateException("This stoppage was already closed.");
        }
        DowntimeReason reason = activeReason(reasonId);
        int minutes = minutesBetween(event.startedAt(), now);
        db.runInTransaction(conn -> {
            if (downtime.closeEvent(conn, eventId, now, minutes, reason.id(), blankToNull(note),
                    actor.id(), now) != 1) {
                throw new IllegalStateException("This stoppage was already closed.");
            }
        });
        LOG.info("Downtime {} closed by {} after {} min as {}", eventId, actor.username(), minutes,
                reason.code());
    }

    /**
     * Records a stoppage that is already over — the ordinary case, since somebody who is dealing with a
     * stopped line is not standing at a screen while it is stopped.
     */
    public long log(Long teamId, Long sorterId, Long stationId, LocalDateTime startedAt,
                    LocalDateTime endedAt, long reasonId, String note) {
        User actor = requireSupervisor();
        if (startedAt == null || endedAt == null) {
            throw new IllegalArgumentException("Give the time it started and the time it started again.");
        }
        LocalDateTime now = BusinessClock.now();
        if (startedAt.isAfter(now)) {
            throw new IllegalArgumentException("A stoppage cannot be recorded before it has happened.");
        }
        DowntimeReason reason = activeReason(reasonId);
        int minutes = minutesBetween(startedAt, endedAt);
        LocalDate day = startedAt.toLocalDate();
        long id = db.executeInTransaction(conn -> {
            int people = downtime.peopleAffected(conn, teamId, sorterId, day);
            return downtime.insertEvent(conn, day, teamId, sorterId, stationId, startedAt, endedAt,
                    minutes, people, reason.id(), blankToNull(note), actor.id(), now, actor.id(), now);
        });
        LOG.info("Downtime {} logged by {}: {} min as {}", id, actor.username(), minutes, reason.code());
        return id;
    }

    // ---------------------------------------------------------------- reading

    public List<DowntimeEvent> events(Scope scope) {
        return downtime.findEvents(readable(scope));
    }

    public List<DowntimeEvent> running() {
        requireSupervisor();
        return downtime.findRunning();
    }

    /** The whole picture for a scope: what stopped, for how long, and against how many rostered hours. */
    public Report report(Scope requested) {
        Scope scope = readable(requested);
        Map<Long, DowntimeReason> reasons = new HashMap<>();
        for (DowntimeReason reason : downtime.findReasons(false)) {
            reasons.put(reason.id(), reason);
        }

        long stopped = 0;
        long personMinutes = 0;
        long attributable = 0;
        List<DowntimeDao.ReasonMinutes> rows = downtime.minutesByReason(scope);
        for (DowntimeDao.ReasonMinutes row : rows) {
            stopped += row.minutes();
            personMinutes += row.personMinutes();
            DowntimeReason reason = reasons.get(row.reasonId());
            if (reason != null && reason.attributable()) {
                attributable += row.personMinutes();
            }
        }

        List<Line> lines = new ArrayList<>();
        Map<LossGroup, long[]> byGroup = new LinkedHashMap<>();
        for (DowntimeDao.ReasonMinutes row : rows) {
            DowntimeReason reason = reasons.get(row.reasonId());
            LossGroup group = reason == null ? null : reason.lossGroup();
            // Floored, the way yield shares are, so the parts can never add up to more than the whole.
            int share = stopped == 0 ? 0 : (int) (row.minutes() * 10_000L / stopped);
            lines.add(new Line(row.reasonId(),
                    reason == null ? "?" : reason.code(),
                    group,
                    reason == null ? "?" : reason.nameEn(),
                    reason == null ? "?" : reason.nameHu(),
                    reason != null && reason.attributable(),
                    row.minutes(), row.personMinutes(), share));
            if (group != null) {
                long[] totals = byGroup.computeIfAbsent(group, key -> new long[2]);
                totals[0] += row.minutes();
                totals[1] += row.personMinutes();
            }
        }
        lines.sort(Comparator.comparingLong(Line::minutes).reversed());

        List<Line> groups = new ArrayList<>();
        for (Map.Entry<LossGroup, long[]> entry : byGroup.entrySet()) {
            long minutes = entry.getValue()[0];
            groups.add(new Line(null, entry.getKey().name(), entry.getKey(), entry.getKey().name(),
                    entry.getKey().name(), false, minutes, entry.getValue()[1],
                    stopped == 0 ? 0 : (int) (minutes * 10_000L / stopped)));
        }
        groups.sort(Comparator.comparingLong(Line::minutes).reversed());

        long rostered = downtime.rosteredMinutes(scope.from(), scope.to(), scope.teamId(), scope.sorterId());
        long waiting = downtime.waitingForStockMinutes(scope);
        return new Report(scope.from(), scope.to(), stopped, personMinutes, attributable, rostered, waiting,
                List.copyOf(lines), List.copyOf(groups));
    }

    /**
     * One person's non-productive minutes, which is the figure a bonus conversation turns on. Sorters may
     * read their own and nobody else's: their own figures are theirs to see, and the rest of the floor's are
     * not theirs to compare themselves against row by row.
     */
    public Report forSorter(long sorterId, LocalDate from, LocalDate to) {
        return report(new Scope(from, to, null, sorterId));
    }

    // ---------------------------------------------------------------- the reason list

    public List<DowntimeReason> reasons(boolean activeOnly) {
        session.requireCurrentUser();
        return downtime.findReasons(activeOnly);
    }

    /**
     * Adds a reason in the supervisor's own words.
     *
     * <p>Keeping the list short is the feature, not a limitation of it: a plant running two hundred reasons
     * on a touchscreen gets people picking the first plausible option while the line is still stopped. The
     * groups are fixed in code; the wording inside them belongs to whoever runs the floor.
     */
    public long createReason(String code, LossGroup group, String nameEn, String nameHu,
                             boolean attributable) {
        User actor = requireReasonKeeper();
        String reference = code == null ? "" : code.strip().toUpperCase(java.util.Locale.ROOT);
        if (reference.isEmpty() || reference.length() > 16) {
            throw new IllegalArgumentException("Give the reason a code of up to 16 characters.");
        }
        requireText(nameEn, "an English wording");
        requireText(nameHu, "a Hungarian wording");
        if (group == null) {
            throw new IllegalArgumentException("Choose which kind of loss this is.");
        }
        LocalDateTime now = BusinessClock.now();
        return db.executeInTransaction(conn ->
                downtime.insertReason(conn, reference, group, nameEn.strip(), nameHu.strip(),
                        attributable, actor.id(), now));
    }

    /** Rewording is what the fortnightly review is for, so the text and the group can both change. */
    public void updateReason(long reasonId, LossGroup group, String nameEn, String nameHu,
                             boolean attributable) {
        requireReasonKeeper();
        requireText(nameEn, "an English wording");
        requireText(nameHu, "a Hungarian wording");
        if (group == null) {
            throw new IllegalArgumentException("Choose which kind of loss this is.");
        }
        db.runInTransaction(conn -> {
            if (downtime.updateReason(conn, reasonId, group, nameEn.strip(), nameHu.strip(),
                    attributable) != 1) {
                throw new IllegalArgumentException("No such reason.");
            }
        });
    }

    /**
     * Stops a reason being offered, without touching the stoppages already logged under it: those happened
     * under that wording, and restating last quarter's history in this quarter's vocabulary would make the
     * report a work of fiction.
     */
    public void retireReason(long reasonId) {
        requireReasonKeeper();
        LocalDateTime now = BusinessClock.now();
        db.runInTransaction(conn -> {
            if (downtime.retireReason(conn, reasonId, now) != 1) {
                throw new IllegalArgumentException("That reason is not in use.");
            }
        });
    }

    public void reinstateReason(long reasonId) {
        requireReasonKeeper();
        db.runInTransaction(conn -> {
            if (downtime.reinstateReason(conn, reasonId) != 1) {
                throw new IllegalArgumentException("That reason is already in use.");
            }
        });
    }

    // ---------------------------------------------------------------- guards and plumbing

    private DowntimeReason activeReason(long reasonId) {
        DowntimeReason reason = downtime.findReason(reasonId)
                .orElseThrow(() -> new IllegalArgumentException("Choose a reason for the stoppage."));
        if (!reason.active()) {
            throw new IllegalArgumentException("That reason has been retired. Choose one of the current ones.");
        }
        return reason;
    }

    private static int minutesBetween(LocalDateTime from, LocalDateTime to) {
        long minutes = Duration.between(from, to).toMinutes();
        if (minutes < 1) {
            throw new IllegalArgumentException("A stoppage has to have started before it ended, and last at"
                    + " least a minute.");
        }
        if (minutes > 24 * 60) {
            throw new IllegalArgumentException("A stoppage longer than a day is a shift that did not run."
                    + " Record it a day at a time.");
        }
        return (int) minutes;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Give the reason " + what + ".");
        }
    }

    /** Running the shift and dealing with stoppages is the shift leader's job, and the floor above them. */
    private User requireSupervisor() {
        User actor = session.requireCurrentUser();
        if (!actor.roleCode().runsProduction()) {
            throw new IllegalStateException("Only the production leaders may record stoppages.");
        }
        return actor;
    }

    /**
     * The list everybody's figures are reported against is the production lead's, the same way the yield
     * baseline is: a shift leader logs against the list, and changing what the list says is a level up.
     */
    private User requireReasonKeeper() {
        User actor = session.requireCurrentUser();
        if (!RoleCode.MANAGEMENT.contains(actor.roleCode()) && actor.roleCode() != RoleCode.PRODUCTION_LEAD) {
            throw new IllegalStateException("Only management or the production lead may change the reason"
                    + " list.");
        }
        return actor;
    }

    /**
     * Narrows a request to what the person asking is allowed to see. A sorter sees their own non-productive
     * time and nobody else's; everyone else on the production side sees the floor.
     */
    private Scope readable(Scope scope) {
        User actor = session.requireCurrentUser();
        if (actor.roleCode().runsProduction()) {
            return scope;
        }
        if (actor.roleCode() == RoleCode.SORTER) {
            if (scope.sorterId() != null && scope.sorterId() != actor.id()) {
                throw new IllegalStateException("Your own figures are the ones you can see.");
            }
            return new Scope(scope.from(), scope.to(), null, actor.id());
        }
        throw new IllegalStateException("Only the sorting floor and its leaders may read stoppage figures.");
    }

    public Optional<DowntimeEvent> find(long eventId) {
        session.requireCurrentUser();
        return downtime.findEvent(eventId);
    }
}
