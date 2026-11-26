package hu.due.rewear.dao;

import hu.due.rewear.util.BusinessClock;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * The one way a moment is written down.
 *
 * <p>The application stores two different things and must not confuse them. A <b>moment</b> is when something
 * happened: a bag was reported, a stoppage started, a handover was picked up. A <b>business date</b> is which
 * day's work something belongs to: a shift, a due date, the day a rate takes effect. They are stored
 * differently on purpose.
 *
 * <ul>
 *   <li>A moment is a UTC instant in ISO-8601, truncated to whole seconds, always exactly twenty characters:
 *       {@code 2026-11-26T07:12:00Z}. Its column name ends in {@code _at}.</li>
 *   <li>A business date is a local date, {@code 2026-11-26}, and its column name ends in {@code _day},
 *       {@code _date}, {@code _on}, {@code _from} or {@code _until}. A business day belongs to the site, not
 *       to a time zone, and turning it into an instant would make the night shift change date.</li>
 * </ul>
 *
 * <p>Both conventions were in use until this was written: the sorting module stored instants and everything
 * else stored local date-times, and the two had already cost two defects — a seeder that wrote local times
 * into instant columns so every sorting screen threw, and a handover query that has to convert one half of
 * its window and not the other. Two readings of the same wall-clock hour exist every autumn, so a local
 * date-time is not even a reliable way to order two records, which is the first thing a handheld that works
 * offline has to be able to do.
 *
 * <p>Whole seconds, always: {@code Instant.toString()} leaves out a zero fraction and prints one when it has
 * it, so the same moment could be written two ways and a text comparison would put the longer one first.
 */
public final class DaoDateUtil {

    /** How a moment is written: a UTC instant, whole seconds, twenty characters. */
    public static final DateTimeFormatter MOMENT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** What a stored moment must look like. Used by the guard test and by the migration. */
    public static final Pattern MOMENT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");

    /**
     * The format moments were stored in before this: a local date-time, with no zone on it anywhere.
     * Still read, because databases full of it exist; never written.
     */
    public static final DateTimeFormatter LEGACY_LOCAL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DaoDateUtil() {
    }

    /** Now, as a stored moment, from the business clock. */
    public static String now() {
        return format(BusinessClock.clock().instant());
    }

    public static String format(Instant moment) {
        return moment == null ? null : MOMENT_FORMAT.format(moment);
    }

    /**
     * Writes a local Budapest time as the moment it names.
     *
     * <p>During the hour that happens twice every autumn a local time names two moments; the earlier one is
     * taken, which is what {@link java.time.ZonedDateTime} does by default and the only choice that can be
     * made without asking.
     */
    public static String format(LocalDateTime localMoment) {
        return localMoment == null ? null : format(localMoment.atZone(BusinessClock.ZONE).toInstant());
    }

    /** Reads a stored moment as a local Budapest time, which is how every screen shows one. */
    public static LocalDateTime parse(String raw) {
        Instant moment = parseInstant(raw);
        return moment == null ? null : LocalDateTime.ofInstant(moment, BusinessClock.ZONE);
    }

    /**
     * Reads a stored moment, in either convention.
     *
     * <p>Anything ending in {@code Z} is an instant, with or without a fraction of a second. Anything else
     * is a local date-time written before this convention existed, and is read as Budapest time — which is
     * what it meant, since that is the only clock the application has ever had.
     */
    public static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.endsWith("Z")) {
            return Instant.parse(text).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        }
        String normalized = text.replace('T', ' ');
        if (normalized.length() > 19) {
            normalized = normalized.substring(0, 19);
        }
        return LocalDateTime.parse(normalized, LEGACY_LOCAL_FORMAT).atZone(BusinessClock.ZONE).toInstant();
    }

    /** True if a stored value is already written the one way moments are written. */
    public static boolean isCanonicalMoment(String raw) {
        return raw != null && MOMENT.matcher(raw).matches();
    }

    /**
     * The half-open range of moments that fall on one local day, as stored text.
     *
     * <p>A local day is not a prefix of a stored moment, because the moment is in UTC and the day is in
     * Budapest: between eleven at night and midnight, the two disagree about the date. Anything asking "what
     * happened on this day" asks for this range instead.
     *
     * @return the first moment of the day and the first moment of the next, both inclusive-exclusive
     */
    public static String[] dayRange(java.time.LocalDate day) {
        return new String[]{
                format(day.atStartOfDay()),
                format(day.plusDays(1).atStartOfDay())};
    }
}
