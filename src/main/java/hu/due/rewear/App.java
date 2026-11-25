package hu.due.rewear;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPaths;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
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
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Application entry point for ReWear WMS (due-prog-03).
 */
public final class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

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
                return new AppContext(new DatabaseManager(), preferences, i18n, this::publish);
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
