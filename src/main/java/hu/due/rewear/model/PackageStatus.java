package hu.due.rewear.model;

/**
 * Physical inventory lifecycle states for a sorted clothing bale, bag, box, or pallet.
 */
public enum PackageStatus {
    IN_STOCK,
    RESERVED,
    /** Held aside after damage was found, until somebody decides to write it off or return it to stock. */
    QUARANTINED,
    SHIPPED,
    DISCARDED
}
