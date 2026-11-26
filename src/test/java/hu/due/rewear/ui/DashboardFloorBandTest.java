package hu.due.rewear.ui;

import hu.due.rewear.service.GradeYieldService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one line on the dashboard that says where the money came from.
 *
 * <p>The research's central finding is that cream grade is roughly a tenth of what the line puts out and
 * over half of what it earns. A row of the three heaviest grades therefore shows the three that matter least
 * and leaves out the one the business runs on, which is why this row is ordered by value.
 */
class DashboardFloorBandTest {

    private static GradeYieldService.Line line(String code, long grams, int shareBasisPoints, String eur) {
        return new GradeYieldService.Line(1L, code, code, grams, shareBasisPoints,
                new BigDecimal(eur), new BigDecimal(eur).multiply(new BigDecimal("390")), null);
    }

    @Test
    void theMixRowNamesTheGradesThatEarnedTheMostNotTheHeaviest() {
        GradeYieldService.Report report = new GradeYieldService.Report(
                1_000_000, 920_000,
                List.of(line("B", 340_000, 3400, "400"),
                        line("A", 270_000, 2700, "300"),
                        line("C", 230_000, 2300, "90"),
                        line("CR", 90_000, 900, "1200")),
                new BigDecimal("1990"), new BigDecimal("776100"), null);

        String row = DashboardView.topGrades(report);

        assertTrue(row.startsWith("CR 9%"),
                "A tenth of the weight and over half of the revenue belongs first on the row: " + row);
        assertEquals("CR 9%  ·  B 34%  ·  A 27%", row,
                "Three grades, each with the share of input it took, ordered by what it earned");
    }

    @Test
    void aGradeThatEarnedNothingDoesNotPushOutOneThatDid() {
        GradeYieldService.Report report = new GradeYieldService.Report(
                1_000_000, 900_000,
                List.of(line("C", 600_000, 6000, "0"),
                        line("CR", 100_000, 1000, "900"),
                        line("A", 200_000, 2000, "250")),
                new BigDecimal("1150"), new BigDecimal("448500"), null);

        assertEquals("CR 10%  ·  A 20%  ·  C 60%", DashboardView.topGrades(report),
                "Rags are most of the weight and none of the revenue, and the row says so by ordering them last");
    }
}
