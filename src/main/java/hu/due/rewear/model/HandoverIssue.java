package hu.due.rewear.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Something left unfinished at the end of a shift, with a name and a date against it.
 *
 * <p>Both of those are required. An issue with no owner is a note, and a note is what a handover sheet exists
 * to replace: the whole value of the record is that somebody can be asked about it tomorrow.
 *
 * @param escalatedAt when it was passed up a tier for having sat open too long. Escalation is automatic
 *                    rather than a button, so that the person who raised it does not have to keep pushing it.
 */
public record HandoverIssue(
        long id,
        long handoverId,
        LocalDate workDay,
        String summary,
        long ownerId,
        String ownerName,
        LocalDate dueOn,
        State state,
        LocalDateTime createdAt,
        LocalDateTime escalatedAt,
        Long escalatedTo,
        String escalatedToName,
        LocalDateTime resolvedAt,
        Long resolvedBy,
        String resolvedByName,
        String resolution) {

    public enum State { OPEN, RESOLVED }

    /** How long an issue may sit before it goes up a tier on its own. */
    public static final Duration ESCALATION_AFTER = Duration.ofHours(48);

    public boolean open() {
        return state == State.OPEN;
    }

    public boolean escalated() {
        return escalatedAt != null;
    }

    /** True once it has been open longer than the escalation window and nobody has closed it. */
    public boolean dueForEscalation(LocalDateTime now) {
        return open() && escalatedAt == null
                && Duration.between(createdAt, now).compareTo(ESCALATION_AFTER) >= 0;
    }

    /** Past its own due date, which is a different question from having sat too long unlooked-at. */
    public boolean overdue(LocalDate today) {
        return open() && dueOn.isBefore(today);
    }

    public long hoursOpen(LocalDateTime now) {
        return Duration.between(createdAt, resolvedAt == null ? now : resolvedAt).toHours();
    }
}
