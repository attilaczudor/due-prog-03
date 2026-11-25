package hu.due.rewear.service;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PayrollWorkflowTest {
    @TempDir Path tmp;

    @Test
    void tracksFlexibleRostersActualPackerAndDepartmentPayOnTheSameWorkDay() {
        var ctx = new AppContext(new DatabaseManager(tmp.resolve("payroll.db")),
                new AppPreferences(tmp.resolve("app.properties")), new I18nManager());
        var sorting = ctx.getSortingService();
        var payroll = ctx.getPayrollService();
        var day = payroll.today();
        var sorter = user(ctx, "sorter01");
        var movingSorter = user(ctx, "sorter05");
        var firstHelper = user(ctx, "helper01");
        var packingHelper = user(ctx, "helper02");
        var manager = user(ctx, "manager");

        ctx.getSession().login(manager);
        assertFalse(ctx.getSession().canAccessModule(AppModule.PAYROLL));
        assertThrows(SecurityException.class, () -> payroll.workspace(day));
        var teams = sorting.workspace(day).teams();
        var firstTeam = teams.stream().filter(t -> t.sorterIds().contains(sorter.id())).findFirst().orElseThrow();
        var secondTeam = teams.stream().filter(t -> t.sorterIds().contains(movingSorter.id())).findFirst().orElseThrow();
        sorting.saveTeam(secondTeam.id(), secondTeam.name(), secondTeam.helperIds(), secondTeam.driverId(),
                secondTeam.stationId(), secondTeam.sorterIds().stream().filter(id -> id != movingSorter.id()).toList(),
                bd("1000"), null);
        var fiveSorters = new ArrayList<>(firstTeam.sorterIds());
        fiveSorters.add(movingSorter.id());
        sorting.saveTeam(firstTeam.id(), firstTeam.name(), List.of(firstHelper.id(), packingHelper.id()),
                firstTeam.driverId(), firstTeam.stationId(), fiveSorters, bd("1000"), null);
        var configured = sorting.workspace(day).teams().stream().filter(t -> t.id() == firstTeam.id()).findFirst().orElseThrow();
        assertEquals(5, configured.sorterIds().size());
        assertEquals(2, configured.helperIds().size());

        long lot = sorting.receive("WORK-DAY", bd("800"));
        ctx.getSession().login(sorter);
        long request = sorting.requestSupply(bd("400"));
        ctx.getSession().login(user(ctx, "forklift"));
        sorting.deliver(request, lot, bd("400"));
        var dict = ctx.getDictionaryDao();
        var target = dict.findTargetGroups(true).get(0);
        var garment = dict.findGarmentTypes(true).get(0);
        var grade = dict.findQualityGrades(true).get(0);
        var packaging = dict.findPackageTypes(true).get(0);
        ctx.getSession().login(sorter);
        long firstBag = sorting.reportBag(request, target.id(), garment.id(), grade.id(), bd("15"));
        sorting.finish(request, bd("400"));
        ctx.getSession().login(packingHelper);
        sorting.pack(firstBag, packaging.id());
        assertEquals(packingHelper.displayName(), sorting.workspace(day).bags().get(0).packer());
        ctx.getSession().login(firstHelper);
        assertThrows(IllegalStateException.class, () -> sorting.pack(firstBag, packaging.id()));

        var secondSorter = user(ctx, "sorter06");
        ctx.getSession().login(secondSorter);
        long secondRequest = sorting.requestSupply(bd("400"));
        ctx.getSession().login(user(ctx, "forklift"));
        sorting.deliver(secondRequest, lot, bd("400"));
        ctx.getSession().login(secondSorter);
        long secondBag = sorting.reportBag(secondRequest, target.id(), garment.id(), grade.id(), bd("20"));
        sorting.finish(secondRequest, bd("400"));
        ctx.getSession().login(packingHelper);
        sorting.pack(secondBag, packaging.id());

        ctx.getSession().login(user(ctx, "admin"));
        var initial = payroll.workspace(day);
        assertTrue(initial.workers().stream().anyMatch(w -> w.id() == packingHelper.id() && w.monthlyGross() == null));
        var firstDepartment = initial.departments().stream().filter(d -> firstTeam.name().equals(d.sortingTeam())).findFirst().orElseThrow();
        var secondDepartment = initial.departments().stream().filter(d -> secondTeam.name().equals(d.sortingTeam())).findFirst().orElseThrow();
        payroll.saveMonthlyGross(packingHelper.id(), day, 360_000);
        payroll.saveRate(firstDepartment.id(), RoleCode.SORTING_HELPER, day.minusDays(1), 16_000, bd("10"));
        payroll.saveRate(firstDepartment.id(), RoleCode.SORTING_HELPER, day.plusDays(1), 21_000, bd("15"));
        payroll.saveRate(secondDepartment.id(), RoleCode.SORTING_HELPER, day, 18_000, bd("20"));
        payroll.saveShift(0, packingHelper.id(), day, firstDepartment.id(), RoleCode.SORTING_HELPER, bd("50"), true);
        payroll.saveShift(0, packingHelper.id(), day, secondDepartment.id(), RoleCode.SORTING_HELPER, bd("50"), false);
        assertThrows(IllegalArgumentException.class, () ->
                payroll.saveShift(0, packingHelper.id(), day, firstDepartment.id(), RoleCode.SORTER, bd("5"), true));

        var state = payroll.workspace(day);
        assertEquals(2, state.shifts().size());
        assertEquals(17_800L, state.shifts().stream().mapToLong(PayrollService.Shift::draftGross).sum());
        assertEquals(800L, state.shifts().stream().mapToLong(PayrollService.Shift::bonusGross).sum());
        assertEquals(360_000L, state.workers().stream().filter(w -> w.id() == packingHelper.id()).findFirst().orElseThrow().monthlyGross());
        assertEquals(2, state.performance().stream().filter(p -> p.workerId() == packingHelper.id()).count());
        assertEquals(35_000L, state.performance().stream().filter(p -> p.workerId() == packingHelper.id())
                .mapToLong(PayrollService.Performance::packedGrams).sum());
        assertEquals(400_000L, state.performance().stream().filter(p -> p.workerId() == sorter.id())
                .mapToLong(PayrollService.Performance::sortedGrams).sum());
        assertTrue(state.performance().stream().noneMatch(p -> p.workerId() == firstHelper.id()));
        assertTrue(payroll.workspace(day.minusDays(1)).performance().isEmpty());
    }

    private static User user(AppContext ctx, String username) {
        return ctx.getUserDao().findByUsername(username).orElseThrow();
    }

    private static BigDecimal bd(String amount) { return new BigDecimal(amount); }
}
