package hu.due.rewear.model;

import java.time.LocalDateTime;

/**
 * One reason the line stopped, as the supervisor words it.
 *
 * @param attributable whether the stopped time is the crew's own. A break that ran over is; waiting for a
 *                     forklift is not. This single flag is what lets a bonus figure survive being challenged,
 *                     because it separates the minutes somebody could have done something about from the
 *                     minutes they could not.
 * @param retiredAt    when it stopped being offered. A retired reason is never deleted: the stoppages logged
 *                     under it happened under that wording, and rewriting last quarter's history to match
 *                     this quarter's vocabulary would make the report a work of fiction.
 */
public record DowntimeReason(
        long id,
        String code,
        LossGroup lossGroup,
        String nameEn,
        String nameHu,
        boolean attributable,
        boolean active,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime retiredAt) {

    public String name(String languageCode) {
        return "hu".equalsIgnoreCase(languageCode) ? nameHu : nameEn;
    }

    public String label(String languageCode) {
        return code + " · " + name(languageCode);
    }
}
