package hu.due.rewear.model;

/**
 * The eight groups a stoppage can belong to.
 *
 * <p>Eight is not an arbitrary number. Lean practice on downtime coding is consistent that the list has to be
 * short — on the order of eight groups with fewer than ten reasons each — and that it must be written in the
 * operators' own words, because a plant running a two-hundred-item list on a touchscreen gets people picking
 * the first plausible option while the line is still stopped. The groups are fixed in code and the reasons
 * inside them are not: the groups are what reporting is built on, the reasons are what the supervisor
 * rewrites every couple of weeks as the operation teaches them better wording.
 */
public enum LossGroup {
    /** Nothing to sort: material not delivered, not back from the shelf, or the wrong line arrived. */
    SUPPLY,
    /** A scale, scanner, label printer or baler that stopped working. */
    EQUIPMENT,
    /** Re-grading, contamination, anything that makes work be done twice. */
    QUALITY,
    /** Switching material, target group or station, and clearing the bench between lots. */
    CHANGEOVER,
    /** The building: power, lighting, heating, water. */
    FACILITY,
    /** People: absence, a break that ran over, training somebody new. */
    STAFFING,
    /** Meetings, instruction, stocktake — scheduled work that is not sorting. */
    ADMIN,
    /** Somebody else's doing: a blocked dock, an inspection, a visitor. */
    EXTERNAL
}
