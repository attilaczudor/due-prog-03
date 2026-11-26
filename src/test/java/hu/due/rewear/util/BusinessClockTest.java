package hu.due.rewear.util;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.RoleModulePermissionDao;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.RoleCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Proves that business days follow the warehouse zone (Europe/Budapest), not whatever zone the
 * machine happens to run in. Each test moves the JVM to Los Angeles, where the date at the chosen
 * instant differs from Budapest's, so a stray system-clock call would produce a visibly wrong day.
 */
class BusinessClockTest {

    /** 23:30 UTC on 24 Nov: already 00:30 on 25 Nov in Budapest (CET), still 24 Nov in Los Angeles. */
    private static final Instant JUST_PAST_BUDAPEST_MIDNIGHT = Instant.parse("2026-11-24T23:30:00Z");

    private final TimeZone originalZone = TimeZone.getDefault();

    @TempDir
    Path tempDir;

    @AfterEach
    void restore() {
        BusinessClock.resetForTesting();
        TimeZone.setDefault(originalZone);
    }

    private void machineInLosAngelesAndClockPinned() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        BusinessClock.useForTesting(Clock.fixed(JUST_PAST_BUDAPEST_MIDNIGHT, BusinessClock.ZONE));
    }

    private AppContext newContext() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("clock-test.db"));
        AppPreferences prefs = new AppPreferences(tempDir.resolve("app.properties"));
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        return new AppContext(db, prefs, i18n);
    }

    @Test
    void businessDayFollowsBudapestNotTheMachineZone() {
        machineInLosAngelesAndClockPinned();

        assertEquals(LocalDate.of(2026, 11, 25), BusinessClock.today());
        assertEquals(LocalDateTime.of(2026, 11, 25, 0, 30), BusinessClock.now());
        assertEquals(LocalDate.of(2026, 11, 24), LocalDate.now(Clock.fixed(JUST_PAST_BUDAPEST_MIDNIGHT, ZoneId.systemDefault())),
                "Precondition: the machine zone would have produced the previous day");
    }

    @Test
    void realClockIsAnchoredToTheWarehouseZoneAndResetRestoresIt() {
        BusinessClock.useForTesting(Clock.fixed(JUST_PAST_BUDAPEST_MIDNIGHT, BusinessClock.ZONE));
        assertEquals(JUST_PAST_BUDAPEST_MIDNIGHT, BusinessClock.clock().instant());

        BusinessClock.resetForTesting();

        assertEquals(BusinessClock.ZONE, BusinessClock.clock().getZone());
        assertNotEquals(JUST_PAST_BUDAPEST_MIDNIGHT, BusinessClock.clock().instant(), "The pinned instant must be gone");
    }

    @Test
    void sortingAndPayrollShareTheBusinessDayInsteadOfKeepingTheirOwnClocks() {
        machineInLosAngelesAndClockPinned();
        AppContext ctx = newContext();

        assertEquals(LocalDate.of(2026, 11, 25), ctx.getSortingService().today());
        assertEquals(LocalDate.of(2026, 11, 25), ctx.getPayrollService().today());
    }

    /**
     * The permission matrix used SQLite's {@code datetime('now')}, which is UTC, while every other
     * stored timestamp is local. It must now carry the business clock's time like everything else.
     */
    @Test
    void permissionMatrixTimestampUsesTheBusinessClockNotSqliteUtc() throws SQLException {
        machineInLosAngelesAndClockPinned();
        AppContext ctx = newContext();

        new RoleModulePermissionDao(ctx.getDatabaseManager()).setEnabled(RoleCode.MANAGER, AppModule.INVENTORY, true);

        String sql = "SELECT updated_at FROM role_module_permissions WHERE module_code = ? "
                + "AND role_id = (SELECT id FROM roles WHERE code = ?)";
        try (Connection conn = ctx.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, AppModule.INVENTORY.name());
            ps.setString(2, RoleCode.MANAGER.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                // The moment is stored as a UTC instant, which is the same moment the pinned business
                // clock names. SQLite's own clock would have ignored the pin and written the real time,
                // and would have written it without the Z that says which clock it is on.
                assertEquals("2026-11-24T23:30:00Z", rs.getString(1),
                        "The business clock's moment, in the one format a moment is written in");
                assertTrue(hu.due.rewear.dao.DaoDateUtil.isCanonicalMoment(rs.getString(1)));
                assertEquals(LocalDateTime.of(2026, 11, 25, 0, 30),
                        hu.due.rewear.dao.DaoDateUtil.parse(rs.getString(1)),
                        "Read back in Budapest time it is half past midnight, which is the day it belongs to");
            }
        }
    }
}
