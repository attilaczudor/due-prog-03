package hu.due.rewear.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The mix this site actually produces, captured from its own history.
 *
 * <p>The application deliberately ships no yield target. The published cascades are Nordic figures from 2014,
 * and the reusable/recyclable/waste split runs from roughly 53/33/14 in one country to 80/13/6 in another, so
 * a number taken from the literature would be a measure of somebody else's material and somebody else's
 * people. A baseline is therefore captured from a stated period of this line's own output, by a named person,
 * and then left alone — variance against a figure that moves every time it is recomputed is not variance.
 *
 * <p>Shares are in basis points of <em>processed input</em>, not of saleable output, so they include what
 * went in and came out as nothing. A cascade that counts only what could be sold flatters itself.
 */
public record YieldBaseline(
        long id,
        String name,
        LocalDate periodFrom,
        LocalDate periodTo,
        long processedGrams,
        boolean active,
        long capturedBy,
        String capturedByName,
        LocalDateTime capturedAt,
        String notes,
        /** Grade id to share in basis points; the null key is the residue. */
        Map<Long, Integer> shares) {

    public YieldBaseline {
        // Not Map.copyOf: that forbids a null key, and the residue is deliberately held against one. An
        // unmodifiable LinkedHashMap gives the same protection and keeps the capture order.
        shares = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(shares));
    }

    /** The share recorded for a grade, or zero if that grade did not appear in the baseline period. */
    public int shareOf(Long gradeId) {
        return shares.getOrDefault(gradeId, 0);
    }

    /** The grades this baseline knows about, residue excluded. */
    public List<Long> gradeIds() {
        return shares.keySet().stream().filter(java.util.Objects::nonNull).sorted().toList();
    }
}
