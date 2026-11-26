package hu.due.rewear.model;

import java.time.LocalDateTime;

/**
 * One row that two machines changed since they last spoke, held for a person to settle.
 *
 * <p>It carries both accounts rather than a verdict: which row, which table, and for each side the machine,
 * the person signed in on it, and where their change fell in their own order of events. That is what
 * somebody needs in order to pick up the phone and ask, which is how a disagreement about a package's shelf
 * actually gets settled.
 *
 * @param ourSequence   where our change falls in this database's change log
 * @param theirSequence where their change fell in theirs, which is their count and not ours
 */
public record SyncConflict(
        long id,
        String tableName,
        long rowId,
        State state,
        long ourSequence,
        Long ourDeviceId,
        String ourDeviceName,
        String ourUserName,
        String theirDevice,
        String theirUser,
        long theirSequence,
        LocalDateTime noticedAt,
        LocalDateTime resolvedAt,
        Long resolvedBy,
        String resolvedByName,
        String resolution) {

    public enum State { OPEN, RESOLVED }

    public boolean open() {
        return state == State.OPEN;
    }

    /** One line naming both sides, for a list somebody scans rather than reads. */
    public String between() {
        return (ourDeviceName == null ? "—" : ourDeviceName)
                + (ourUserName == null ? "" : " (" + ourUserName + ")")
                + " / " + (theirDevice == null ? "—" : theirDevice)
                + (theirUser == null ? "" : " (" + theirUser + ")");
    }
}
