package hu.due.rewear;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPaths;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.service.MailExportJanitor;
import hu.due.rewear.ui.LoginFrame;
import hu.due.rewear.ui.LogoAssets;
import hu.due.rewear.ui.MainFrame;
import hu.due.rewear.ui.StartupWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Application entry point for ReWear WMS (due-prog-03).
 */
public final class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    /** Days an {@code exports/mail-*} attachment staging folder is kept before startup prunes it. */
    private static final int MAIL_EXPORT_RETENTION_DAYS = 7;

    private App() {
    }

    public static void main(String[] args) {
        LOG.info("Starting ReWear WMS (due-prog-03)...");
        var preferences = new AppPreferences();
        var i18n = I18nManager.getInstance();
        if (i18n.getAvailableLanguages().contains(preferences.getLanguage())) i18n.setLanguage(preferences.getLanguage());
        SwingUtilities.invokeLater(() -> initialize(preferences, i18n));
    }

    private static void initialize(AppPreferences preferences, I18nManager i18n) {
        LogoAssets.applyDockIcon();
        ThemeManager.applyTheme(preferences.isDarkTheme());
        var startup = new StartupWindow(i18n);
        startup.setVisible(true);
        new SwingWorker<AppContext, AppContext.StartupStep>() {
            @Override protected AppContext doInBackground() throws Exception {
                Files.createDirectories(AppPaths.exportsDir());
                AppContext context = new AppContext(new DatabaseManager(), preferences, i18n, this::publish);
                pruneAbandonedMailExports(context);
                return context;
            }

            @Override protected void process(List<AppContext.StartupStep> steps) {
                startup.updateStep(steps.get(steps.size() - 1));
            }

            @Override protected void done() {
                startup.dispose();
                try {
                    showLogin(get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showStartupError(i18n, ex);
                } catch (ExecutionException ex) {
                    showStartupError(i18n, ex.getCause());
                }
            }
        }.execute();
    }

    /**
     * Housekeeping for e-mail attachment storage. Runs after the context exists so the mail table is
     * guaranteed to be there, and never blocks startup if it fails.
     * <ul>
     *   <li>{@code exports/mail-*} staging folders are removed once old and unreferenced. Messages sent
     *       before the attachment vault existed still point into them, so those stay protected.</li>
     *   <li>Vault files no mail row references are removed after a day, which covers a copy made just
     *       before a crash or database error.</li>
     * </ul>
     */
    private static void pruneAbandonedMailExports(AppContext context) {
        try {
            var referenced = context.getEmailDao().findAttachmentPaths();
            MailExportJanitor.pruneUnreferenced(AppPaths.exportsDir(), referenced,
                    Duration.ofDays(MAIL_EXPORT_RETENTION_DAYS), Instant.now());
            context.getMailAttachmentVault().pruneUnreferenced(referenced, Duration.ofDays(1), Instant.now());
        } catch (RuntimeException ex) {
            LOG.debug("Skipped mail attachment housekeeping", ex);
        }
    }

    private static void showStartupError(I18nManager i18n, Throwable ex) {
        LOG.error("Fatal error during application startup", ex);
        JOptionPane.showMessageDialog(null, i18n.t("startup.failed", ex.getMessage()),
                "ReWear WMS", JOptionPane.ERROR_MESSAGE);
    }

    private static void showLogin(AppContext context) {
        LoginFrame loginFrame = new LoginFrame(context, () -> showMain(context));
        loginFrame.setVisible(true);
    }

    private static void showMain(AppContext context) {
        MainFrame mainFrame = new MainFrame(context, () -> showLogin(context));
        mainFrame.setVisible(true);
    }
}
