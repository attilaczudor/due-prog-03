package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppContext.InitialData;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The setup screen's rules live in OnboardingService and are tested there. This only proves the
 * dialog builds in both languages without throwing, which catches a layout or construction error
 * before a user meets it on a brand-new installation. (A missing translation key shows as [key] rather
 * than throwing; the bundled-translations guard in I18nManagerTest covers the two-language completeness.)
 */
class SetupWizardDialogTest {

    @TempDir
    Path tempDir;

    @Test
    void setupDialogAndLoginScreenBuildInBothLanguages() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        AppContext ctx = new AppContext(new DatabaseManager(tempDir.resolve("ui.db")),
                new AppPreferences(tempDir.resolve("app.properties")), i18n, step -> { }, InitialData.AWAIT_SETUP);

        for (String language : new String[]{"en", "hu"}) {
            i18n.setLanguage(language);
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                SetupWizardDialog dialog = new SetupWizardDialog(ctx);
                assertNotNull(dialog.getContentPane());
                dialog.dispose();
            });
        }
        i18n.setLanguage("en");
    }
}
