package hu.due.rewear.model;

/**
 * What the material legally is, which is a judgement somebody made and not a property of the goods.
 *
 * <p>The distinction decides what may be shipped where, so the record has to name the person who made it,
 * when, and on what basis. A status with nobody behind it is worth nothing when it is questioned.
 */
public enum LotLegalStatus {
    /** Nobody has judged it yet. Until somebody does, it cannot be treated as re-usable. */
    UNASSESSED,
    /** Waste, which is what separately collected textiles are unless an assessment says otherwise. */
    WASTE,
    /** Assessed at the collection point as fit for re-use, by a named person. */
    FIT_FOR_REUSE
}
