package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.ShiftHandoverDao;
import hu.due.rewear.dao.ShiftHandoverDao.ShiftFigures;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.AppNotification;
import hu.due.rewear.model.HandoverIssue;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.RotaShift.ShiftCode;
import hu.due.rewear.model.ShiftHandover;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * What one shift hands to the next, and what happens to the things it could not finish.
 *
 * <p>This is the smallest feature in the roadmap and the best specified. A published template asks for six
 * fields written in ten minutes before the outgoing supervisor leaves: the shift's figures, open issues each
 * with an owner and a date, equipment, staffing, safety, and one sentence on the single thing the next
 * supervisor must do before the line starts. Four of those six are not figures at all, which is why an
 * application that offers a shift report and calls it a handover has covered one field of six.
 *
 * <p>Three rules are built in rather than left to habit. The figures are <em>frozen</em> at close, because a
 * handover is a document of what was said at that moment and recomputing it a week later would rewrite it.
 * The record belongs to shift close rather than shift open, so the incoming supervisor's first screen is the
 * last shift rather than today so far. And an issue nobody has closed within forty-eight hours goes up a tier
 * by itself, so that the person who raised it does not have to keep pushing it.
 */
public final class ShiftHandoverService {
    private static final Logger LOG = LoggerFactory.getLogger(ShiftHandoverService.class);
    private static final String VIEW = AppModule.HANDOVER.viewKey();
    private static final int FIELD_LIMIT = 2000;
    private static final int MUST_KNOW_LIMIT = 300;

    /** A work day and the shift on it: the pair that identifies one handover. */
    public record ShiftSlot(LocalDate workDay, ShiftCode shift) { }

    /** An issue being handed over, before it has an id. */
    public record NewIssue(String summary, long ownerId, LocalDate dueOn) { }

    /** The six fields, as they are written. The figures are not here: they are measured, not typed. */
    public record NewHandover(
            LocalDate workDay,
            ShiftCode shift,
            String equipmentStatus,
            String staffingNotes,
            String safetyItems,
            String mustKnow,
            List<NewIssue> issues) { }

    private final DatabaseManager db;
    private final SessionContext session;
    private final ShiftHandoverDao handovers;
    private final UserDao users;
    private final NotificationService notifications;

    public ShiftHandoverService(DatabaseManager db, SessionContext session, ShiftHandoverDao handovers,
                                UserDao users, NotificationService notifications) {
        this.db = db;
        this.session = session;
        this.handovers = handovers;
        this.users = users;
        this.notifications = notifications;
    }

    // ---------------------------------------------------------------- writing one

    /**
     * The shift somebody is in, at whatever hour they sit down to write it up. Before six in the morning
     * that is the night shift of the day before, which is the one case a date picker gets wrong every time.
     */
    public static ShiftSlot currentSlot(LocalDateTime now) {
        int minute = now.getHour() * 60 + now.getMinute();
        if (minute < 6 * 60) {
            return new ShiftSlot(now.toLocalDate().minusDays(1), ShiftCode.NIGHT);
        }
        if (minute < 14 * 60) {
            return new ShiftSlot(now.toLocalDate(), ShiftCode.MORNING);
        }
        if (minute < 22 * 60) {
            return new ShiftSlot(now.toLocalDate(), ShiftCode.AFTERNOON);
        }
        return new ShiftSlot(now.toLocalDate(), ShiftCode.NIGHT);
    }

    /**
     * The shift before a given one, which is the shift a handover is already owed for.
     *
     * <p>Written as a minute before the given shift began rather than as a case for each code, so that the
     * rule for which day a night shift belongs to is stated in exactly one place.
     */
    public static ShiftSlot previousSlot(ShiftSlot slot) {
        return currentSlot(window(slot)[0].minusMinutes(1));
    }

    /** The window a shift covers, with the night shift running into the next morning. */
    public static LocalDateTime[] window(ShiftSlot slot) {
        LocalDateTime from = slot.workDay().atStartOfDay().plusMinutes(slot.shift().defaultStart());
        int span = slot.shift().defaultEnd() - slot.shift().defaultStart();
        if (span <= 0) {
            span += 24 * 60;
        }
        return new LocalDateTime[]{from, from.plusMinutes(span)};
    }

    /** What would be frozen into the handover if it were written now, so the form can show it first. */
    public ShiftFigures figuresFor(ShiftSlot slot) {
        requireSupervisor();
        LocalDateTime[] window = window(slot);
        return handovers.figuresBetween(window[0], window[1]);
    }

    /**
     * Writes the handover and freezes the shift's figures into it.
     *
     * <p>Every written field is required. "Nothing to report" is an answer somebody has to give: a blank
     * field is exactly what the record exists to prevent, and it costs one word to fill in.
     */
    public long submit(NewHandover input) {
        User actor = requireSupervisor();
        if (input == null || input.workDay() == null || input.shift() == null) {
            throw new IllegalArgumentException("Say which shift this is.");
        }
        if (input.shift() == ShiftCode.OFF) {
            throw new IllegalArgumentException("A day off has nothing to hand over.");
        }
        LocalDateTime now = BusinessClock.now();
        if (input.workDay().isAfter(BusinessClock.today())) {
            throw new IllegalArgumentException("A shift cannot be handed over before it has been worked.");
        }
        String equipment = required(input.equipmentStatus(), "the state of the equipment", FIELD_LIMIT);
        String staffing = required(input.staffingNotes(), "who was in and who was not", FIELD_LIMIT);
        String safety = required(input.safetyItems(), "anything about safety", FIELD_LIMIT);
        String mustKnow = required(input.mustKnow(),
                "the one thing the next shift has to do before the line starts", MUST_KNOW_LIMIT);
        if (handovers.find(input.workDay(), input.shift()).isPresent()) {
            throw new IllegalStateException("That shift has already been handed over.");
        }
        List<NewIssue> issues = input.issues() == null ? List.of() : input.issues();
        for (NewIssue issue : issues) {
            required(issue.summary(), "what the issue is", FIELD_LIMIT);
            User owner = users.findById(issue.ownerId())
                    .orElseThrow(() -> new IllegalArgumentException("Give every issue an owner by name."));
            if (!owner.active()) {
                throw new IllegalArgumentException("An issue cannot be left with somebody who has left.");
            }
            if (issue.dueOn() == null || issue.dueOn().isBefore(input.workDay())) {
                throw new IllegalArgumentException("Give every issue a date it is wanted by.");
            }
        }

        LocalDateTime[] window = window(new ShiftSlot(input.workDay(), input.shift()));
        ShiftFigures figures = handovers.figuresBetween(window[0], window[1]);
        long id = db.executeInTransaction(conn -> {
            long handoverId = handovers.insert(conn, input.workDay(), input.shift(), actor.id(),
                    equipment, staffing, safety, mustKnow, figures, now);
            for (NewIssue issue : issues) {
                handovers.insertIssue(conn, handoverId, issue.summary().strip(), issue.ownerId(),
                        issue.dueOn(), now);
            }
            return handoverId;
        });
        // The next supervisor needs to know there is something to pick up before their shift meeting, not
        // when they happen to open the screen.
        notifications.notifyRoles(EnumSet.of(RoleCode.SHIFT_LEAD, RoleCode.PRODUCTION_LEAD, RoleCode.MANAGER),
                AppNotification.CAT_SYSTEM,
                input.workDay() + " · " + input.shift() + " · " + actor.displayName(), mustKnow, VIEW);
        LOG.info("Shift handover {} written by {} for {} {}", id, actor.username(), input.workDay(),
                input.shift());
        return id;
    }

    /** Picking it up. A handover nobody acknowledged is a handover that did not happen. */
    public void acknowledge(long handoverId) {
        User actor = requireSupervisor();
        LocalDateTime now = BusinessClock.now();
        db.runInTransaction(conn -> {
            if (handovers.acknowledge(conn, handoverId, actor.id(), now) != 1) {
                throw new IllegalStateException("That handover has already been picked up.");
            }
        });
    }

    // ---------------------------------------------------------------- issues

    /**
     * Adds an issue to a handover that is already written, because somebody always remembers one more on
     * the way out. The forty-eight hours run from now rather than from the shift it belongs to.
     */
    public long addIssue(long handoverId, NewIssue issue) {
        requireSupervisor();
        ShiftHandover handover = handovers.findById(handoverId)
                .orElseThrow(() -> new IllegalArgumentException("No such handover."));
        String summary = required(issue == null ? null : issue.summary(), "what the issue is", FIELD_LIMIT);
        User owner = users.findById(issue.ownerId())
                .orElseThrow(() -> new IllegalArgumentException("Give every issue an owner by name."));
        if (!owner.active()) {
            throw new IllegalArgumentException("An issue cannot be left with somebody who has left.");
        }
        if (issue.dueOn() == null || issue.dueOn().isBefore(handover.workDay())) {
            throw new IllegalArgumentException("Give every issue a date it is wanted by.");
        }
        LocalDateTime now = BusinessClock.now();
        return db.executeInTransaction(conn ->
                handovers.insertIssue(conn, handoverId, summary, issue.ownerId(), issue.dueOn(), now));
    }

    public void resolveIssue(long issueId, String resolution) {
        User actor = requireSupervisor();
        String outcome = required(resolution, "what was done about it", FIELD_LIMIT);
        LocalDateTime now = BusinessClock.now();
        db.runInTransaction(conn -> {
            if (handovers.resolveIssue(conn, issueId, actor.id(), outcome, now) != 1) {
                throw new IllegalStateException("That issue is already closed.");
            }
        });
    }

    /**
     * Passes up every issue that has been open for more than forty-eight hours.
     *
     * <p>Automatic on purpose. The point of the rule is that the person who raised an issue does not have to
     * keep pushing it, so this runs when the application starts and whenever the screen is read, rather than
     * waiting for somebody to press a button they would have to remember.
     *
     * <p>It takes no actor: nobody is logged in when the application starts, and an escalation is the
     * record's own doing rather than anybody's decision.
     *
     * @return how many issues went up
     */
    public int escalateStaleIssues() {
        LocalDateTime now = BusinessClock.now();
        List<HandoverIssue> stale = handovers.findOpenIssues().stream()
                .filter(issue -> issue.dueForEscalation(now))
                .toList();
        if (stale.isEmpty()) {
            return 0;
        }
        Long tier = nextTier();
        int escalated = db.executeInTransaction(conn -> {
            int count = 0;
            for (HandoverIssue issue : stale) {
                count += handovers.markEscalated(conn, issue.id(), tier, now);
            }
            return count;
        });
        for (HandoverIssue issue : stale) {
            notifications.notifyRoles(EnumSet.of(RoleCode.PRODUCTION_LEAD, RoleCode.MANAGER),
                    AppNotification.CAT_SYSTEM,
                    issue.hoursOpen(now) + "h · " + issue.ownerName(), issue.summary(), VIEW);
        }
        LOG.info("Escalated {} handover issue(s) open for more than {} hours", escalated,
                HandoverIssue.ESCALATION_AFTER.toHours());
        return escalated;
    }

    /** Who an issue goes to when it has waited too long: the production lead, or management above them. */
    private Long nextTier() {
        return users.findAll().stream()
                .filter(User::active)
                .filter(user -> user.roleCode() == RoleCode.PRODUCTION_LEAD)
                .map(User::id)
                .findFirst()
                .orElseGet(() -> users.findAll().stream()
                        .filter(User::active)
                        .filter(user -> user.roleCode() == RoleCode.MANAGER)
                        .map(User::id)
                        .findFirst()
                        .orElse(null));
    }

    // ---------------------------------------------------------------- reading

    /**
     * The last shift that was handed over.
     *
     * <p>This is deliberately what the screen opens on. Daily management practice is that the board has to be
     * current before the next shift's meeting, which makes the handover a closing task — so the first thing
     * an incoming supervisor should see is the shift they are taking over from, not the one they are in.
     */
    public Optional<ShiftHandover> latest() {
        requireReader();
        return handovers.findLatest();
    }

    public Optional<ShiftHandover> find(LocalDate workDay, ShiftCode shift) {
        requireReader();
        return handovers.find(workDay, shift);
    }

    public List<ShiftHandover> recent(int limit) {
        requireReader();
        return handovers.findRecent(Math.max(1, Math.min(limit, 200)));
    }

    /** Everything still open across every shift, oldest first, escalated ones included. */
    public List<HandoverIssue> openIssues() {
        requireReader();
        return handovers.findOpenIssues();
    }

    // ---------------------------------------------------------------- guards

    private static String required(String value, String what, int limit) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Write down " + what + " — one word is enough, a blank is not.");
        }
        if (text.length() > limit) {
            throw new IllegalArgumentException("Keep it to " + limit + " characters; this is read in ten"
                    + " minutes at a shift change.");
        }
        return text;
    }

    /** Writing and picking up a handover is the job of whoever runs a shift, and the floor above them. */
    private User requireSupervisor() {
        User actor = session.requireCurrentUser();
        if (!actor.roleCode().runsProduction()) {
            throw new IllegalStateException("Only the shift and production leaders may hand a shift over.");
        }
        return actor;
    }

    private User requireReader() {
        return requireSupervisor();
    }
}
