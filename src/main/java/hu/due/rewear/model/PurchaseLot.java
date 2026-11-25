package hu.due.rewear.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A quantity of unsorted material as it was bought, with where it came from and what it legally is.
 *
 * <p>This is the record the rest of the chain hangs from. Sorting draws from raw lots, raw lots come from a
 * purchase lot, and so every package, bag and order can be traced back to a supplier, a country and a
 * collection channel. Without it the application can say what it sorted but not what it sorted <em>from</em>,
 * and provenance in this trade is a legal matter rather than a nicety.
 */
public record PurchaseLot(
        long id,
        String reference,
        Long supplierId,
        String supplierName,
        String originCountry,
        CollectionChannel collectionChannel,
        String customsCode,
        String customsDescription,
        BigDecimal purchasePricePerKg,
        String currency,
        BigDecimal grossWeightKg,
        BigDecimal netWeightKg,
        LotLegalStatus legalStatus,
        Long assessedBy,
        String assessedByName,
        LocalDateTime assessedAt,
        String assessmentBasis,
        String assessmentPlace,
        Long invoiceId,
        String invoiceNumber,
        String notes,
        long receivedBy,
        String receivedByName,
        LocalDate receivedOn,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * What the material in this lot cost: the net weight at the agreed price.
     *
     * <p>Net rather than gross, deliberately. The company pays for cloth, not for the pallet and the wrapping,
     * and a cost per kilogram computed on gross weight quietly understates what every sorted kilogram has to
     * earn back.
     */
    public BigDecimal totalCost() {
        BigDecimal weight = netWeightKg == null ? BigDecimal.ZERO : netWeightKg;
        BigDecimal price = purchasePricePerKg == null ? BigDecimal.ZERO : purchasePricePerKg;
        return weight.multiply(price).setScale(2, RoundingMode.HALF_UP);
    }

    /** The weight of packaging and anything else that is not material. */
    public BigDecimal tareWeightKg() {
        if (grossWeightKg == null || netWeightKg == null) {
            return BigDecimal.ZERO;
        }
        return grossWeightKg.subtract(netWeightKg);
    }

    /** True when somebody has made the legal judgement this lot needs. */
    public boolean isAssessed() {
        return legalStatus != LotLegalStatus.UNASSESSED && assessedBy != null;
    }
}
