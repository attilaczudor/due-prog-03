package hu.due.rewear.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Multi-currency Partner Statement &amp; 30/60/90-day Aging Summary computed natively per currency
 * (no forced FX conversion). Tracks billed, paid, current (not yet due), and overdue aging buckets.
 */
public record PartnerAgingStatement(
        Customer partner,
        LocalDate asOfDate,
        Map<String, CurrencyAging> byCurrency
) {
    public PartnerAgingStatement {
        byCurrency = byCurrency == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(byCurrency));
    }

    /** Per-currency aging buckets for a single business partner. */
    public record CurrencyAging(
            String currency,
            int decimalPlaces,
            BigDecimal totalBilled,
            BigDecimal totalPaid,
            BigDecimal currentNotDue,
            BigDecimal overdue1to30,
            BigDecimal overdue31to60,
            BigDecimal overdue60Plus,
            BigDecimal totalOpen,
            int openInvoiceCount
    ) {
    }
}
