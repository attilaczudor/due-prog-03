package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.SyncPolicy;
import hu.due.rewear.dao.ChangeLogDao;
import hu.due.rewear.dao.SyncConflictDao;
import hu.due.rewear.model.SyncConflict;
import hu.due.rewear.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * What happens when a second machine brings back work done out of contact.
 *
 * <p>This decides; it does not apply. There is no transport yet and no second machine, and writing the
 * applying half against an imagined one would be guesswork. What can be built honestly now is the part that
 * has a right answer: given a set of changes another device says it made, which of them this database can
 * take without losing anything, and which of them are disagreements a person has to settle.
 *
 * <p>The rule is in {@link SyncPolicy} and comes in two parts. A row in an insert-only table cannot be
 * disagreed about, because neither side changes one — they each add their own. Anything else, where both
 * sides have touched the same row since they last spoke, is held: no automatic rule would be right every
 * time, and the two cases it would get wrong look identical from here.
 */
public final class SyncService {

    private static final Logger LOG = LoggerFactory.getLogger(SyncService.class);

    /** A change another device says it made, as it would arrive over a wire that does not exist yet. */
    public record IncomingChange(String tableName, long rowId, ChangeLogDao.Operation operation,
                                 long theirSequence, String theirDevice, String theirUser) { }

    public enum Outcome {
        /** Nothing of ours stands in the way of this change. */
        TAKE,
        /** We changed the same row since we last spoke, and no rule may decide which of us is right. */
        HELD
    }

    /**
     * What was decided about one incoming change.
     *
     * @param conflictId the conflict that was recorded, when it was held
     * @param reason     why, in the words of the rule that said so
     */
    public record Decision(IncomingChange change, Outcome outcome, Long conflictId, String reason) { }

    private final DatabaseManager db;
    private final SessionContext session;
    private final ChangeLogDao changes;
    private final SyncConflictDao conflicts;

    public SyncService(DatabaseManager db, SessionContext session, ChangeLogDao changes,
                       SyncConflictDao conflicts) {
        this.db = db;
        this.session = session;
        this.changes = changes;
        this.conflicts = conflicts;
    }

    /**
     * Decides what to do with each change a device offers.
     *
     * <p>"Since we last spoke" is the sequence number this database last sent that device. Our own changes
     * after it are ones the device has not seen, so a change of theirs to the same row was made without
     * knowing about ours — which is the definition of the disagreement, and the only one available without
     * either side keeping a copy of the other's data.
     *
     * @param deviceId the device offering the changes, as this database knows it
     */
    public List<Decision> classify(long deviceId, List<IncomingChange> incoming) {
        requireSupervisor();
        SyncConflictDao.SyncState state = conflicts.stateFor(deviceId);
        Long ourDevice = currentDeviceId();
        List<Decision> decisions = new ArrayList<>();
        for (IncomingChange change : incoming) {
            SyncPolicy.Rule rule = SyncPolicy.rule(change.tableName());
            if (rule.merge() == SyncPolicy.Merge.APPEND) {
                decisions.add(new Decision(change, Outcome.TAKE, null, rule.reason()));
                continue;
            }
            List<ChangeLogDao.Change> ours = changes.forRow(change.tableName(), change.rowId()).stream()
                    .filter(c -> c.sequence() > state.sentThrough())
                    .toList();
            if (ours.isEmpty()) {
                decisions.add(new Decision(change, Outcome.TAKE, null,
                        "We have not touched this row since they last heard from us."));
                continue;
            }
            // Our side of the story is the last change we made to that row, and the person who made it —
            // not whoever happens to be looking at the conflict afterwards.
            ChangeLogDao.Change mine = ours.get(ours.size() - 1);
            long conflict = conflicts.open(change.tableName(), change.rowId(),
                    mine.sequence(), ourDevice, mine.userId(),
                    change.theirDevice(), change.theirUser(), change.theirSequence());
            decisions.add(new Decision(change, Outcome.HELD, conflict, rule.reason()));
        }
        long held = decisions.stream().filter(d -> d.outcome() == Outcome.HELD).count();
        if (held > 0) {
            LOG.info("Held {} of {} incoming changes from device {} for somebody to settle",
                    held, incoming.size(), deviceId);
        }
        return List.copyOf(decisions);
    }

    /** Everything waiting for a person, oldest first. */
    public List<SyncConflict> openConflicts() {
        requireSupervisor();
        return conflicts.findOpen();
    }

    public long countOpenConflicts() {
        return conflicts.countOpen();
    }

    /**
     * Settles one, with what was decided written down.
     *
     * <p>The sentence is required for the same reason a stoppage may not be closed without a reason: a
     * disagreement settled without a record is one that comes back as "nobody knows why that package moved".
     */
    public void resolve(long conflictId, String resolution) {
        User actor = requireSupervisor();
        String text = resolution == null ? "" : resolution.strip();
        if (text.isEmpty() || text.length() > 500) {
            throw new IllegalArgumentException("Say in a sentence what was decided, and who you asked.");
        }
        if (conflicts.resolve(conflictId, actor.id(), text) != 1) {
            throw new IllegalStateException("That disagreement has already been settled.");
        }
    }

    /** Notes how far this database and a device have got with each other. */
    public void recordSync(long deviceId, long sentThrough, long receivedThrough) {
        requireSupervisor();
        conflicts.recordSync(deviceId, sentThrough, receivedThrough);
    }

    public SyncConflictDao.SyncState stateFor(long deviceId) {
        return conflicts.stateFor(deviceId);
    }

    private Long currentDeviceId() {
        return db.executeInTransaction(conn ->
                hu.due.rewear.config.DeviceRegistry.currentDeviceId(conn).orElse(null));
    }

    /**
     * Settling a disagreement between two machines is the floor's leadership and the office, nobody else.
     *
     * <p>The same set that may read yield figures and write a handover: management and the two production
     * leaders. A sorter whose own run is in dispute is the person to ask about it, not the person to decide
     * it.
     */
    private User requireSupervisor() {
        User actor = session.requireCurrentUser();
        if (!actor.roleCode().runsProduction()) {
            throw new IllegalStateException("Only the production leadership may settle a sync disagreement.");
        }
        return actor;
    }
}
