package hu.due.rewear.model;

import java.math.BigDecimal;

/**
 * Multi-currency consolidated P&L financial summary expressed in a chosen {@code reportingCurrency}
 * (EUR or HUF), alongside exact native-currency subtotals so EUR and HUF are never naively summed.
 */
public record FinancialSummary(
        CurrencyCode reportingCurrency,
        BigDecimal totalIncome,
        BigDecimal paidIncome,
        BigDecimal totalExpenses,
        BigDecimal paidExpenses,
        BigDecimal netBalance,
        int unpaidCount,
        BigDecimal unpaidAmount,
        BigDecimal incomeEur,
        BigDecimal incomeHuf,
        BigDecimal expenseEur,
        BigDecimal expenseHuf,
        BigDecimal unpaidEur,
        BigDecimal unpaidHuf
) {
}
