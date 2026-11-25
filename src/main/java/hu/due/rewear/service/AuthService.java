package hu.due.rewear.service;

import hu.due.rewear.dao.UserDao;
import hu.due.rewear.model.Role;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;

/**
 * Handles BCrypt authentication, password management, and session lifecycle.
 */
public final class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    public enum AuthStatus {
        SUCCESS,
        INVALID_CREDENTIALS,
        INACTIVE_USER
    }

    public record AuthResult(AuthStatus status, User user) {
        public boolean isSuccess() {
            return status == AuthStatus.SUCCESS && user != null;
        }
    }

    private final UserDao userDao;
    private final SessionContext session;

    public AuthService(UserDao userDao, SessionContext session) {
        this.userDao = userDao;
        this.session = session;
    }

    public AuthResult login(String username, char[] password) {
        if (username == null || username.isBlank() || password == null || password.length == 0) {
            return new AuthResult(AuthStatus.INVALID_CREDENTIALS, null);
        }
        String plain = new String(password);
        try {
            Optional<User> userOpt = userDao.findByUsername(username.trim());
            if (userOpt.isEmpty()) {
                LOG.warn("Login failed: unknown username '{}'", username.trim());
                return new AuthResult(AuthStatus.INVALID_CREDENTIALS, null);
            }
            User user = userOpt.get();
            if (!user.active()) {
                LOG.warn("Login blocked: inactive user '{}'", user.username());
                return new AuthResult(AuthStatus.INACTIVE_USER, null);
            }
            if (!BCrypt.checkpw(plain, user.passwordHash())) {
                LOG.warn("Login failed: invalid password for '{}'", user.username());
                return new AuthResult(AuthStatus.INVALID_CREDENTIALS, null);
            }
            session.login(user);
            LOG.info("User '{}' logged in with role {}", user.username(), user.roleCode());
            return new AuthResult(AuthStatus.SUCCESS, user);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public void logout() {
        session.getCurrentUser().ifPresent(u -> LOG.info("User '{}' logged out", u.username()));
        session.logout();
    }

    public String hashPassword(String plainPassword) {
        if (plainPassword == null || plainPassword.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters long.");
        }
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(10));
    }

    public boolean changePassword(long userId, String currentPassword, String newPassword) {
        User user = userDao.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (currentPassword == null || !BCrypt.checkpw(currentPassword, user.passwordHash())) {
            return false;
        }
        userDao.updatePasswordHash(userId, hashPassword(newPassword));
        LOG.info("Password changed for user '{}'", user.username());
        return true;
    }

    public long createUser(String username, String plainPassword, String fullName, long roleId, boolean active) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required.");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Full name is required.");
        }
        return userDao.insert(username.trim(), hashPassword(plainPassword), fullName.trim(), roleId, active);
    }

    public void updateManagedUser(long userId, String lastName, String firstName, Role role, boolean active,
                                  Long stationId, String email, char[] newPassword) {
        try {
            User actor = session.requireCurrentUser();
            User target = userDao.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
            boolean protectedAccount = target.roleCode() == RoleCode.SUPER_ADMIN;
            if (!session.canManageUsersAndMaintenance()
                    || (!session.isSuperAdmin() && (protectedAccount || email != null))
                    || (!protectedAccount && role.code() == RoleCode.SUPER_ADMIN)) {
                throw new IllegalArgumentException("ADMIN_USER_EDIT_DENIED");
            }
            if (actor.id() == userId && !active) {
                throw new IllegalArgumentException("ADMIN_USER_CANNOT_DISABLE_SELF");
            }
            long roleId = protectedAccount ? target.roleId() : role.id();
            boolean enabled = protectedAccount ? target.active() : active;
            Long assignedStation = role.code() == RoleCode.OPERATOR && !protectedAccount ? stationId : null;
            String passwordHash = newPassword.length == 0 ? null : hashPassword(new String(newPassword));
            userDao.updateManagedAccount(userId, lastName, firstName, roleId, enabled, assignedStation, email, passwordHash);
            if (actor.id() == userId) {
                userDao.findById(userId).ifPresent(session::updateCurrentUser);
            }
            if (passwordHash != null) {
                LOG.info("Administrator reset password for userId={}", userId);
            }
        } finally {
            Arrays.fill(newPassword, '\0');
        }
    }

    public User updateOwnProfile(long userId, String newHandle, String lastName, String firstName,
                                 String phone, byte[] avatarPng, Long newRoleId) {
        if (session.requireCurrentUser().id() != userId) {
            throw new IllegalArgumentException("Only your own profile can be updated here.");
        }
        String ln = lastName == null ? "" : lastName.trim();
        String fn = firstName == null ? "" : firstName.trim();
        if (ln.isEmpty() || fn.isEmpty()) {
            throw new IllegalArgumentException("NAME_REQUIRED");
        }
        User existing = userDao.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        String normalizedHandle = null;
        if (newHandle != null) {
            String cleaned = newHandle.trim();
            if (cleaned.startsWith("@")) {
                cleaned = cleaned.substring(1).trim();
            }
            if (!cleaned.matches("[A-Za-z0-9._-]{3,32}")) {
                throw new IllegalArgumentException("INVALID_HANDLE");
            }
            Optional<User> clash = userDao.findByUsername(cleaned);
            if (clash.isPresent() && clash.get().id() != userId) {
                throw new IllegalArgumentException("HANDLE_TAKEN");
            }
            normalizedHandle = cleaned;
        }

        // Super Administrator role is strictly immutable and cannot be changed in settings
        Long effectiveRoleId = (existing.roleCode() == RoleCode.SUPER_ADMIN)
                ? null
                : (newRoleId != null && session.isAdmin() ? newRoleId : null);
        userDao.updateAccountSettings(userId, normalizedHandle, ln, fn, phone, avatarPng, effectiveRoleId);
        User updated = userDao.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Updated user not found: " + userId));
        session.updateCurrentUser(updated);
        LOG.info("Updated profile settings for user '{}' (role={})", updated.username(), updated.roleCode());
        return updated;
    }
}
