package hu.due.rewear.model;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * First-class registry of every menu module in ReWear WMS.
 * {@link RoleCode#SUPER_ADMIN} always has full access to every module and can toggle
 * configurable modules for each eligible role.
 */
public enum AppModule {
    DASHBOARD("dashboard", "nav.dashboard", Category.CORE, true),
    TODO("todo", "nav.todo", Category.CORE, false),
    SORTING("sorting", "nav.sorting", Category.OPERATIONS, false),
    INTAKE("intake", "nav.intake", Category.OPERATIONS, false),
    PUTAWAY("putaway", "nav.putaway", Category.OPERATIONS, false),
    STAGING("staging", "nav.staging", Category.OPERATIONS, false),
    SCANNER("scanner", "nav.scanner", Category.OPERATIONS, false),
    INVENTORY("inventory", "nav.inventory", Category.OPERATIONS, false),
    LOCATIONS("locations", "nav.locations", Category.MANAGEMENT, false),
    DICTIONARIES("dictionaries", "nav.dictionaries", Category.MANAGEMENT, false),
    CUSTOMERS("customers", "nav.customers", Category.MANAGEMENT, false),
    ORDERS("orders", "nav.orders", Category.MANAGEMENT, false),
    INVOICES("invoices", "nav.invoices", Category.MANAGEMENT, false),
    PAYROLL("payroll", "nav.payroll", Category.MANAGEMENT, false),
    ROTA("rota", "nav.rota", Category.MANAGEMENT, false),
    STOCKTAKE("stocktake", "nav.stocktake", Category.OPERATIONS, false),
    NOTIFICATIONS("notifications", "nav.notifications", Category.COMMUNICATIONS, false),
    MESSAGES("messages", "nav.messages", Category.COMMUNICATIONS, false),
    EMAIL("email", "nav.email", Category.COMMUNICATIONS, false),
    ADMIN("admin", "nav.admin", Category.ADMINISTRATION, false);

    public enum Category {
        CORE("module.cat.core"),
        OPERATIONS("module.cat.operations"),
        MANAGEMENT("module.cat.management"),
        COMMUNICATIONS("module.cat.communications"),
        ADMINISTRATION("module.cat.administration");

        private final String i18nKey;

        Category(String i18nKey) {
            this.i18nKey = i18nKey;
        }

        public String i18nKey() {
            return i18nKey;
        }
    }

    private final String viewKey;
    private final String navI18nKey;
    private final Category category;
    private final boolean alwaysEnabled;

    AppModule(String viewKey, String navI18nKey, Category category, boolean alwaysEnabled) {
        this.viewKey = viewKey;
        this.navI18nKey = navI18nKey;
        this.category = category;
        this.alwaysEnabled = alwaysEnabled;
    }

    public String viewKey() {
        return viewKey;
    }

    public String navI18nKey() {
        return navI18nKey;
    }

    public Category category() {
        return category;
    }

    /** True if this module cannot be turned off for any role (e.g. Dashboard home screen). */
    public boolean isAlwaysEnabled() {
        return alwaysEnabled;
    }

    /** Payroll figures stay with the office roles that are responsible for them. */
    public boolean allowsRole(RoleCode role) {
        return this != PAYROLL || role == RoleCode.ADMIN || role == RoleCode.SUPER_ADMIN
                || role == RoleCode.HR_PAYROLL;
    }

    public boolean defaultEnabledFor(RoleCode role) {
        if (role == null || !allowsRole(role)) {
            return false;
        }
        if (alwaysEnabled || role == RoleCode.SUPER_ADMIN) {
            return true;
        }
        return switch (role) {
            case ADMIN -> true;
            case MANAGER -> this != ADMIN;
            // Runs the sorting operation: the floor, its reference data and its rota, but no money.
            case PRODUCTION_LEAD -> this == TODO || this == SORTING || this == INTAKE || this == PUTAWAY
                    || this == STAGING || this == SCANNER || this == INVENTORY || this == LOCATIONS
                    || this == DICTIONARIES || this == ROTA || this == STOCKTAKE
                    || this == NOTIFICATIONS || this == MESSAGES || this == EMAIL;
            // Runs one shift: the same floor work and the rota, without the reference data.
            case SHIFT_LEAD -> this == TODO || this == SORTING || this == INTAKE || this == PUTAWAY
                    || this == STAGING || this == SCANNER || this == INVENTORY || this == ROTA || this == STOCKTAKE
                    || this == NOTIFICATIONS || this == MESSAGES || this == EMAIL;
            // Payroll, the rota it is based on, and the worker records inside Payroll.
            case HR_PAYROLL -> this == TODO || this == PAYROLL || this == ROTA
                    || this == NOTIFICATIONS || this == MESSAGES || this == EMAIL;
            case OPERATOR -> this == TODO || this == SORTING || this == INTAKE || this == SCANNER || this == INVENTORY
                    || this == STOCKTAKE || this == ROTA || this == NOTIFICATIONS || this == MESSAGES || this == EMAIL;
            case SORTER, SORTING_HELPER -> this == TODO || this == SORTING || this == ROTA
                    || this == NOTIFICATIONS || this == MESSAGES || this == EMAIL;
            // Serves the sorting floor: raw material in, finished packages out to the shelves.
            case SUPPLY_DRIVER -> this == TODO || this == SORTING || this == PUTAWAY || this == INVENTORY
                    || this == ROTA || this == NOTIFICATIONS || this == MESSAGES || this == EMAIL;
            // Serves customer orders: collecting to the dock and loading the vehicle.
            case DELIVERY_DRIVER -> this == TODO || this == STAGING || this == INVENTORY
                    || this == ROTA || this == NOTIFICATIONS || this == MESSAGES || this == EMAIL;
            case SUPER_ADMIN -> true;
        };
    }

    public static Set<AppModule> defaultsFor(RoleCode role) {
        EnumSet<AppModule> set = EnumSet.noneOf(AppModule.class);
        for (AppModule m : values()) {
            if (m.defaultEnabledFor(role)) {
                set.add(m);
            }
        }
        return set;
    }

    public static Optional<AppModule> fromViewKey(String viewKey) {
        if (viewKey == null || viewKey.isBlank()) {
            return Optional.empty();
        }
        if ("transport".equalsIgnoreCase(viewKey)) {
            return Optional.of(PUTAWAY);
        }
        for (AppModule m : values()) {
            if (m.viewKey.equalsIgnoreCase(viewKey)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    public static Optional<AppModule> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(AppModule.valueOf(code.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
