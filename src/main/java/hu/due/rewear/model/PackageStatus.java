package hu.due.rewear.model;

/**
 * Physical inventory lifecycle states for a sorted clothing bale, bag, box, or pallet.
 */
public enum PackageStatus {
    IN_STOCK,
    RESERVED,
    SHIPPED,
    DISCARDED
}
