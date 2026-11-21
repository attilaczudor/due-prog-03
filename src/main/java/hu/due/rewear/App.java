package hu.due.rewear;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.ui.LoginFrame;
import hu.due.rewear.ui.LogoAssets;
import hu.due.rewear.ui.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Application entry point for ReWear WMS (due-prog-03).
 */
public final class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    private App() {
    }

    public static void main(String[] args) {
        LOG.info("Starting ReWear WMS (due-prog-03)...");
        try {
            AppContext context = new AppContext();
            SwingUtilities.invokeLater(() -> {
                LogoAssets.applyDockIcon();
                ThemeManager.applyTheme(context.getPreferences().isDarkTheme());
                showLogin(context);
            });
        } catch (Exception ex) {
            LOG.error("Fatal error during application startup", ex);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    null,
                    "Startup error: " + ex.getMessage(),
                    "ReWear WMS",
                    JOptionPane.ERROR_MESSAGE
            ));
        }
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
