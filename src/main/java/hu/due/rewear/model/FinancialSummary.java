package hu.due.rewear.model;

import java.math.BigDecimal;

public record FinancialSummary(
        BigDecimal totalIncome,
        BigDecimal paidIncome,
        BigDecimal totalExpenses,
        BigDecimal paidExpenses,
        BigDecimal netBalance,
        int unpaidCount,
        BigDecimal unpaidAmount
) {
}
