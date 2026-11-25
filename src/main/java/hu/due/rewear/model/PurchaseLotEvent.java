package hu.due.rewear.model;

import java.time.LocalDateTime;

/**
 * One recorded change to a purchase lot.
 *
 * <p>Kept in full because the interesting field — whether the material is waste or fit for re-use — is a human
 * judgement with legal weight, and a judgement that can be quietly revised is not evidence of anything.
 */
public record PurchaseLotEvent(
        long id,
        long lotId,
        String eventType,
        String detail,
        Long createdBy,
        String createdByName,
        LocalDateTime createdAt) {
}
