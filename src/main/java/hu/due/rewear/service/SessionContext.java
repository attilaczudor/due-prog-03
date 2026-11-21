package hu.due.rewear.service;

import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;

import java.util.Optional;

/**
 * Holds the currently authenticated user and centralizes role-based access control (RBAC) checks.
 */
public final class SessionContext {

    private User currentUser;

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
