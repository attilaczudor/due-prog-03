package hu.due.rewear.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One stoppage, from the moment the line stopped to the moment it started again.
 *
 * <p>A null {@code teamId} means the whole line rather than nobody, and a null {@code sorterId} means
 * everybody on the team: a power cut stops the site, and recording that should not require naming twenty
 * people one at a time.
 *
 * @param minutes how long it lasted, or null while it is still running. Held as an integer so that every
 *                total is exact, and always computed from the two timestamps rather than typed.
 */
public record DowntimeEvent(
        long id,
        LocalDate workDay,
        Long teamId,
        String teamName,
        Long sorterId,
        String sorterName,
        Long stationId,
        String stationCode,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        Integer minutes,
        int peopleAffected,
        Long reasonId,
        String reasonCode,
        LossGroup lossGroup,
        String reasonNameEn,
        String reasonNameHu,
        Boolean attributable,
        String note,
        long openedBy,
        String openedByName,
        LocalDateTime openedAt,
        Long closedBy,
        LocalDateTime closedAt) {

    public boolean running() {
        return endedAt == null;
    }

    /** Whose time it was, in one phrase: a person, a team, or the whole line. */
    public String scopeLabel() {
        if (sorterName != null) {
            return sorterName;
        }
        return teamName != null ? teamName : "—";
    }

    public String reasonName(String languageCode) {
        if (reasonCode == null) {
            return null;
        }
        return "hu".equalsIgnoreCase(languageCode) ? reasonNameHu : reasonNameEn;
    }
}
