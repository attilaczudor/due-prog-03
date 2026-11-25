package hu.due.rewear.service;

import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;

import java.util.Optional;

/**
 * Holds the currently authenticated user and centralizes role-based access control (RBAC)
 * and Super-Admin-managed module permission checks.
 */
public final class SessionContext {

    private User currentUser;
    private RoleModuleService roleModuleService;

    public synchronized void setRoleModuleService(RoleModuleService roleModuleService) {
        this.roleModuleService = roleModuleService;
    }

    public synchronized boolean canAccessModule(AppModule module) {
        if (currentUser == null || module == null || !module.allowsRole(currentUser.roleCode())) {
            return false;
        }
        if (currentUser.roleCode() == RoleCode.SUPER_ADMIN || module.isAlwaysEnabled()) {
            return true;
        }
        if (roleModuleService != null) {
            return roleModuleService.isModuleEnabled(currentUser.roleCode(), module);
        }
        return module.defaultEnabledFor(currentUser.roleCode());
    }

    public synchronized void login(User user) {
        this.currentUser = user;
    }

    public synchronized void updateCurrentUser(User user) {
        if (user != null && this.currentUser != null && this.currentUser.id() == user.id()) {
            this.currentUser = user;
        }
    }

    public synchronized void logout() {
        this.currentUser = null;
    }

    public synchronized Optional<User> getCurrentUser() {
        return Optional.ofNullable(currentUser);
    }

    public synchronized User requireCurrentUser() {
        if (currentUser == null) {
            throw new IllegalStateException("No authenticated user in session.");
        }
        return currentUser;
    }

    public synchronized boolean isAuthenticated() {
        return currentUser != null;
    }

    public synchronized boolean hasRole(RoleCode roleCode) {
        return currentUser != null && currentUser.roleCode() == roleCode;
    }

    public synchronized boolean isSuperAdmin() {
        return hasRole(RoleCode.SUPER_ADMIN);
    }

    public synchronized boolean isAdmin() {
        return hasRole(RoleCode.SUPER_ADMIN) || hasRole(RoleCode.ADMIN);
    }

    public synchronized boolean isManagerOrAdmin() {
        return isAdmin() || hasRole(RoleCode.MANAGER);
    }

    public synchronized boolean isStationManager() {
        return hasRole(RoleCode.OPERATOR);
    }

    public synchronized boolean isForklift() {
        return hasRole(RoleCode.FORKLIFT);
    }

    public boolean canUseIntake() {
        return isManagerOrAdmin() || isStationManager();
    }

    public boolean canUseTransportQueue() {
        return isManagerOrAdmin() || isForklift();
    }

    /** Forklift Drivers use the dedicated scan-to-collect workflow inside Order Staging instead of the standalone QR Scanner Station. */
    public boolean canUseQrScannerStation() {
        return !isForklift();
    }

    /** Only Management/Admins may send or receive external emails; Operators &amp; Forklift Drivers are internal-only. */
    public boolean canUseExternalEmail() {
        return isManagerOrAdmin();
    }

    /** Only Super Admin, Admin, and Manager may create group chat rooms or manage room memberships. */
    public boolean canManageChatRooms() {
        return isManagerOrAdmin();
    }

    public boolean canManageOrders() {
        return isManagerOrAdmin();
    }

    public boolean canManageLocationsAndDictionaries() {
        return isManagerOrAdmin();
    }

    public boolean canExportReports() {
        return isManagerOrAdmin();
    }

    public boolean canManageUsersAndMaintenance() {
        return isAdmin();
    }
}
