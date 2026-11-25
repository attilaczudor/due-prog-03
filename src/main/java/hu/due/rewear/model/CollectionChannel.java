package hu.due.rewear.model;

/**
 * How the material reached the company, which decides what may legally be said about it afterwards.
 *
 * <p>This is not a label for reporting. Household-collected textiles carry the Y46 classification, which
 * requires prior written notification and consent even for a recovery shipment inside the EU, and whose
 * export for recovery outside the OECD is prohibited. Mixing channels destroys the classification of the
 * whole, so the channel has to travel with the goods from intake, through sorting, to whatever leaves the
 * building — which is why it is recorded once, on the purchase lot, and read back by joining rather than
 * copied from place to place where the copies could drift apart.
 */
public enum CollectionChannel {
    /** Collected from drop-off points or households. Y46 applies. */
    HOUSEHOLD_COLLECTION(false),
    /**
     * Handed over directly by the end user. This is the only channel in which the material can avoid being
     * waste from the moment of collection, and only then with an assessment made at the collection point.
     */
    DIRECT_HANDOVER(true),
    /** Bought from a trader or another sorter. */
    COMMERCIAL_PURCHASE(false),
    /** Brought in from another country. */
    IMPORT(false),
    /** Collected by the company's own rounds from its own drop-off boxes. */
    OWN_COLLECTION(false);

    private final boolean reuseAssessmentPossible;

    CollectionChannel(boolean reuseAssessmentPossible) {
        this.reuseAssessmentPossible = reuseAssessmentPossible;
    }

    /**
     * Whether material arriving this way can be assessed as fit for re-use rather than being waste.
     *
     * <p>Separately collected used textiles are waste from the moment of collection unless the end user
     * handed them over directly and they were professionally assessed as fit for re-use at the collection
     * point. Every other channel is waste whatever anybody would prefer to record.
     */
    public boolean allowsReuseAssessment() {
        return reuseAssessmentPossible;
    }
}
