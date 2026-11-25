package hu.due.rewear.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A stocktake: counting what is really on the shelves, and recording everything that did not match. */
public final class Stocktake {

    private Stocktake() {
    }

    public enum Status { OPEN, CLOSED, CANCELLED }

    /**
     * What a counted package turned out to be.
     * <ul>
     *   <li>{@code FOUND} — where it was expected, at the weight on record.</li>
     *   <li>{@code MISPLACED} — on a different shelf; closing the session moves it to where it really is.</li>
     *   <li>{@code REWEIGHED} — the scales disagree with the record; closing corrects the weight.</li>
     *   <li>{@code DAMAGED} — written off when the session is closed.</li>
     *   <li>{@code UNEXPECTED} — counted although it was not in the scope of the session.</li>
     * </ul>
     * A package in scope that is never counted is missing; it is reported, never written off automatically.
     */
    public enum Outcome { FOUND, MISPLACED, REWEIGHED, DAMAGED, UNEXPECTED }

    public record Session(long id, String scopeZone, Long scopeLocationId, String scopeLocationCode,
                          Status status, String note, String startedBy, LocalDateTime startedAt,
                          String closedBy, LocalDateTime closedAt) {

        public boolean open() {
            return status == Status.OPEN;
        }

        /** What the session covers, in words: a shelf, a zone, or the whole warehouse. */
        public String scopeLabel(String wholeWarehouse) {
            if (scopeLocationCode != null) {
                return scopeLocationCode;
            }
            return scopeZone == null || scopeZone.isBlank() ? wholeWarehouse : scopeZone;
        }
    }

    public record Count(long id, long sessionId, long packageId, String qrCode, Outcome outcome,
                        Long expectedLocationId, String expectedLocationCode,
                        Long foundLocationId, String foundLocationCode,
                        long expectedGrams, Long countedGrams, String note,
                        String countedBy, LocalDateTime countedAt) {

        public BigDecimal expectedKg() {
            return BigDecimal.valueOf(expectedGrams, 3).stripTrailingZeros();
        }

        public BigDecimal countedKg() {
            return countedGrams == null ? null : BigDecimal.valueOf(countedGrams, 3).stripTrailingZeros();
        }

        /** Difference in grams between what was counted and what was on record; 0 when they agree. */
        public long differenceGrams() {
            return countedGrams == null ? 0 : countedGrams - expectedGrams;
        }
    }

    /** A package the session expected to find but nobody counted. */
    public record Missing(long packageId, String qrCode, String locationCode, long grams) { }
}
