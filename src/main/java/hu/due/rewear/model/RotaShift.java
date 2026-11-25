package hu.due.rewear.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One person's working day in the rota. A day a worker is off is also a shift, so being off is planned
 * rather than inferred from an absent row.
 */
public record RotaShift(
        long id,
        long userId,
        String workerName,
        RoleCode roleCode,
        LocalDate day,
        ShiftCode shift,
        int startMinute,
        int endMinute,
        String note
) {
    /** The company's standard shifts; a shift still stores its own times so one can be adjusted. */
    public enum ShiftCode {
        MORNING(6 * 60, 14 * 60),
        AFTERNOON(14 * 60, 22 * 60),
        NIGHT(22 * 60, 6 * 60),
        OFF(0, 0);

        private final int defaultStart;
        private final int defaultEnd;

        ShiftCode(int defaultStart, int defaultEnd) {
            this.defaultStart = defaultStart;
            this.defaultEnd = defaultEnd;
        }

        public int defaultStart() {
            return defaultStart;
        }

        public int defaultEnd() {
            return defaultEnd;
        }

        public boolean working() {
            return this != OFF;
        }
    }

    public boolean working() {
        return shift.working();
    }

    public LocalTime start() {
        return LocalTime.ofSecondOfDay(startMinute * 60L);
    }

    public LocalTime end() {
        return LocalTime.ofSecondOfDay(endMinute * 60L);
    }

    /** The night shift ends the next morning, so its length wraps past midnight. */
    public int lengthMinutes() {
        if (!working()) {
            return 0;
        }
        int span = endMinute - startMinute;
        return span > 0 ? span : span + 24 * 60;
    }

    public String timeRange() {
        return working() ? start() + "–" + end() : "";
    }
}
