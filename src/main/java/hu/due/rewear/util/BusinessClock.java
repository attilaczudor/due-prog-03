package hu.due.rewear.util;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The single source of "now" for business records.
 * <p>
 * ReWear WMS runs a Hungarian warehouse, and its documented rules (sorting workdays, packing days,
 * payroll attribution) are all defined in {@code Europe/Budapest}. Reading the machine's own time
 * zone instead would shift a record across midnight whenever the application runs elsewhere, so
 * every timestamp that is stored or used to pick a business day comes from here.
 * <p>
 * Tests can pin time with {@link #useForTesting(Clock)} and must restore it with
 * {@link #resetForTesting()} in a {@code finally} block, since the clock is shared by the JVM.
 */
public final class BusinessClock {

    /** The warehouse's zone, in which every business day is defined. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Budapest");

    private static volatile Clock clock = Clock.system(ZONE);

    private BusinessClock() {
    }

    /** The active clock, for code that needs to hand a {@link Clock} to another component. */
    public static Clock clock() {
        return clock;
    }

    /** Today's date in the warehouse zone. */
    public static LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The current local date and time in the warehouse zone. */
    public static LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Replaces the clock. Intended for tests only. */
    public static void useForTesting(Clock fixed) {
        clock = java.util.Objects.requireNonNull(fixed, "fixed");
    }

    /** Restores the real system clock in the warehouse zone. */
    public static void resetForTesting() {
        clock = Clock.system(ZONE);
    }
}
