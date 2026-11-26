package hu.due.rewear.model;

import hu.due.rewear.model.RotaShift.ShiftCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What one shift hands to the next.
 *
 * <p>The shape follows a published template: six fields, ten minutes, written before the outgoing supervisor
 * leaves. Four of the six are not figures — equipment, staffing, safety and the one thing that must be done
 * before the line starts — and that is the part an application usually misses, because a shift summary report
 * is easy and a structured note with a name against it is not.
 *
 * <p>The figures are frozen as they stood at close, and they cover the shift's own hours rather than the
 * whole work day.
 *
 * @param mustKnow one sentence on the single action the incoming supervisor has to take before the line
 *                 starts. It is the field that most often turns out to matter and the one that is hardest to
 *                 reconstruct afterwards from anything else in the database.
 */
public record ShiftHandover(
        long id,
        LocalDate workDay,
        ShiftCode shift,
        long outgoingBy,
        String outgoingByName,
        String equipmentStatus,
        String staffingNotes,
        String safetyItems,
        String mustKnow,
        long processedGrams,
        long targetGrams,
        long outputGrams,
        int downtimeMinutes,
        Long primaryCauseReasonId,
        String primaryCauseCode,
        String primaryCauseEn,
        String primaryCauseHu,
        LocalDateTime submittedAt,
        Long acknowledgedBy,
        String acknowledgedByName,
        LocalDateTime acknowledgedAt,
        List<HandoverIssue> issues) {

    public ShiftHandover {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** A handover nobody picked up is a handover that did not happen. */
    public boolean acknowledged() {
        return acknowledgedAt != null;
    }

    public BigDecimal processedKg() {
        return BigDecimal.valueOf(processedGrams, 3);
    }

    public BigDecimal targetKg() {
        return BigDecimal.valueOf(targetGrams, 3);
    }

    public BigDecimal outputKg() {
        return BigDecimal.valueOf(outputGrams, 3);
    }

    public boolean reachedTarget() {
        return targetGrams > 0 && processedGrams >= targetGrams;
    }

    public String primaryCause(String languageCode) {
        if (primaryCauseCode == null) {
            return null;
        }
        return primaryCauseCode + " · "
                + ("hu".equalsIgnoreCase(languageCode) ? primaryCauseHu : primaryCauseEn);
    }

    public List<HandoverIssue> openIssues() {
        return issues.stream().filter(HandoverIssue::open).toList();
    }
}
