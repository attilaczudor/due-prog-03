package hu.due.rewear.service;

import hu.due.rewear.dao.RoleModulePermissionDao;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.RoleCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service managing Super-Admin-controlled module visibility and access rules per user role.
 * {@link RoleCode#SUPER_ADMIN} always retains full unrestricted access to every module.
 */
public final class RoleModuleService {

    private static final Logger LOG = LoggerFactory.getLogger(RoleModuleService.class);

    private final RoleModulePermissionDao dao;
    private final SessionContext session;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public RoleModuleService(RoleModulePermissionDao dao, SessionContext session) {
        this.dao = dao;
        this.session = session;
        this.dao.seedDefaultsIfMissing();
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    public boolean isModuleEnabled(RoleCode role, AppModule module) {
        if (role == null || module == null) {
            return false;
        }
        if (role == RoleCode.SUPER_ADMIN || module.isAlwaysEnabled()) {
            return true;
        }
        return dao.findEnabledModules(role).contains(module);
    }

    public Set<AppModule> getEnabledModules(RoleCode role) {
        if (role == null) {
            return EnumSet.noneOf(AppModule.class);
        }
        if (role == RoleCode.SUPER_ADMIN) {
            return EnumSet.allOf(AppModule.class);
        }
        return dao.findEnabledModules(role);
    }

    public Map<RoleCode, Set<AppModule>> getPermissionMatrix() {
        return dao.findAllMatrix();
    }

    /** Super-Admin-only toggle for enabling or disabling a module for a given role. */
    public void setModuleEnabled(RoleCode targetRole, AppModule module, boolean enabled) {
        requireSuperAdmin();
        if (targetRole == null || module == null) {
            throw new IllegalArgumentException("Role and module are required.");
        }
        if (targetRole == RoleCode.SUPER_ADMIN) {
            return; // Super Admin always has unrestricted access
        }
        if (module.isAlwaysEnabled() && !enabled) {
            return; // Core home screen remains enabled for all roles
        }
        dao.setEnabled(targetRole, module, enabled);
        LOG.info("Super Admin updated module permission: role={} module={} enabled={}", targetRole, module, enabled);
        notifyListeners();
    }

    /** Super-Admin-only reset of all non-Super-Admin role module permissions back to factory defaults. */
    public void resetAllToDefaults() {
        requireSuperAdmin();
        for (RoleCode role : RoleCode.values()) {
            if (role == RoleCode.SUPER_ADMIN) {
                continue;
            }
            for (AppModule mod : AppModule.values()) {
                dao.setEnabled(role, mod, mod.defaultEnabledFor(role));
            }
        }
        LOG.info("Super Admin reset all role module permissions to defaults.");
        notifyListeners();
    }

    private void requireSuperAdmin() {
        if (!session.isSuperAdmin()) {
            throw new SecurityException("Only SUPER_ADMIN may modify role module permissions.");
        }
    }

    private void notifyListeners() {
        for (Runnable r : changeListeners) {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        }
    }
}
