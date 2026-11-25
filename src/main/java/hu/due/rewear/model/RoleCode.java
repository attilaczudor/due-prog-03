package hu.due.rewear.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * The company's roles, from the office down to the warehouse floor.
 * <pre>
 *   SUPER_ADMIN            rendszergazda — owns the system and the role matrix
 *   └─ ADMIN               adminisztrátor — all business modules
 *      ├─ MANAGER          raktárvezető — warehouse, partners, orders, finance
 *      ├─ PRODUCTION_LEAD  termelési vezető — the sorting operation as a whole
 *      │  └─ SHIFT_LEAD    műszakvezető — one shift on the floor, and its rota
 *      └─ HR_PAYROLL       bérszámfejtés — payroll, rota and worker records
 *
 *   Floor roles: OPERATOR (állomásvezető), SUPPLY_DRIVER (alapanyag-targoncás),
 *   DELIVERY_DRIVER (kiszállítási targoncás), SORTER (válogató), SORTING_HELPER (válogatósegéd)
 * </pre>
 * The two driver roles split what used to be one {@code FORKLIFT} role. The supply driver serves the sorting
 * floor in both directions: they store arriving raw collections, deliver raw material to the stations, and take
 * finished labelled packages from a station to the shelves. The delivery driver serves customer orders:
 * collecting reserved packages to the dispatch dock and loading the vehicle at handover.
 */
public enum RoleCode {
    SUPER_ADMIN,
    ADMIN,
    MANAGER,
    PRODUCTION_LEAD,
    SHIFT_LEAD,
    HR_PAYROLL,
    OPERATOR,
    SUPPLY_DRIVER,
    DELIVERY_DRIVER,
    SORTER,
    SORTING_HELPER;

    /** Roles that run the company and may see every warehouse and business figure. */
    public static final Set<RoleCode> MANAGEMENT = EnumSet.of(SUPER_ADMIN, ADMIN, MANAGER);

    /** Roles responsible for the sorting operation: management plus the two production leaders. */
    public static final Set<RoleCode> PRODUCTION = EnumSet.of(SUPER_ADMIN, ADMIN, MANAGER, PRODUCTION_LEAD, SHIFT_LEAD);

    /** Roles that may write the shift rota for other people. */
    public static final Set<RoleCode> ROTA_PLANNERS =
            EnumSet.of(SUPER_ADMIN, ADMIN, MANAGER, PRODUCTION_LEAD, SHIFT_LEAD, HR_PAYROLL);

    /** Roles that work on the warehouse floor rather than in the office. */
    public static final Set<RoleCode> FLOOR =
            EnumSet.of(OPERATOR, SUPPLY_DRIVER, DELIVERY_DRIVER, SORTER, SORTING_HELPER);

    public boolean isManagement() {
        return MANAGEMENT.contains(this);
    }

    public boolean runsProduction() {
        return PRODUCTION.contains(this);
    }

    public boolean plansRota() {
        return ROTA_PLANNERS.contains(this);
    }

    public boolean isDriver() {
        return this == SUPPLY_DRIVER || this == DELIVERY_DRIVER;
    }

    /** Floor roles have a company mailbox but send only inside the company. */
    public boolean usesInternalMailOnly() {
        return FLOOR.contains(this);
    }
}
