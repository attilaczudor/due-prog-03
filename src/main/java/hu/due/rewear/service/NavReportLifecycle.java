package hu.due.rewear.service;

import hu.due.rewear.model.NavReportState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The moves a report may make, in one place, for the same reason the invoice lifecycle is in one place: a
 * state machine spread across the code that drives it is a state machine nobody can check.
 *
 * <p>Two rules are worth reading twice. A report that reached {@code DONE} or {@code WARN} never moves again —
 * the authority has it, and quietly re-reporting the same invoice would create a duplicate the company would
 * then have to annul. And {@code ERROR} may only go back to {@code PENDING}, never straight to a finished
 * state: an invoice NAV rejected becomes reported by being sent again and accepted, not by being relabelled.
 */
public final class NavReportLifecycle {

    private static final Map<NavReportState, Set<NavReportState>> ALLOWED = new EnumMap<>(NavReportState.class);

    static {
        ALLOWED.put(NavReportState.PENDING, EnumSet.of(
                NavReportState.SUBMITTED, NavReportState.PENDING, NavReportState.ERROR));
        ALLOWED.put(NavReportState.SUBMITTED, EnumSet.of(
                NavReportState.SUBMITTED, NavReportState.DONE, NavReportState.WARN,
                NavReportState.ERROR, NavReportState.ABORTED, NavReportState.ANNUL_REQUESTED));
        ALLOWED.put(NavReportState.DONE, EnumSet.of(NavReportState.ANNUL_REQUESTED));
        ALLOWED.put(NavReportState.WARN, EnumSet.of(NavReportState.ANNUL_REQUESTED));
        ALLOWED.put(NavReportState.ERROR, EnumSet.of(NavReportState.PENDING));
        ALLOWED.put(NavReportState.ABORTED, EnumSet.of(NavReportState.PENDING));
        ALLOWED.put(NavReportState.ANNUL_REQUESTED, EnumSet.noneOf(NavReportState.class));
    }

    private NavReportLifecycle() { }

    public static boolean allows(NavReportState from, NavReportState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<NavReportState> allowedFrom(NavReportState from) {
        return Set.copyOf(ALLOWED.getOrDefault(from, Set.of()));
    }

    public static void require(NavReportState from, NavReportState to) {
        if (!allows(from, to)) {
            throw new IllegalStateException("A NAV report cannot move from " + from + " to " + to);
        }
    }
}
