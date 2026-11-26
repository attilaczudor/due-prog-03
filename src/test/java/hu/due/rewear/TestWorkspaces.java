package hu.due.rewear;

import hu.due.rewear.config.AppContext;

/** Shared fixture surgery for tests that need a known starting state rather than the demo's. */
public final class TestWorkspaces {

    private TestWorkspaces() {
    }

    /**
     * Empties the worked week the demo workspace opens with.
     *
     * <p>That week is right for a demo and wrong for a test that measures what it put through the line
     * itself: a figure for the whole line would be a figure for somebody else's work as well, and which
     * sorter the seeded rotation happened to put on today is not something a test should depend on. The
     * collections are credited back what was taken off them, so the workspace is left consistent, and the
     * week's stoppages go with the week they belong to. The reason list itself stays: it is reference data
     * every installation has, not sample data.
     */
    public static void clearSeededSortingHistory(AppContext ctx) {
        ctx.getDatabaseManager().runInTransaction(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM downtime_events");
                stmt.executeUpdate("DELETE FROM sorting_bags");
                stmt.executeUpdate("DELETE FROM sorting_request_helpers");
                stmt.executeUpdate("DELETE FROM sorting_requests");
                stmt.executeUpdate("UPDATE sorting_raw_lots SET available_grams = received_grams");
            }
        });
    }
}
