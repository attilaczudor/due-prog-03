package hu.due.rewear.config;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.UIManager;

/**
 * Applies FlatLaf Light or Dark Look & Feel across all active Swing windows.
 */
public final class ThemeManager {

    private ThemeManager() {
    }

    public static void applyTheme(boolean dark) {
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ProgressBar.arc", 8);
        UIManager.put("TabbedPane.showTabSeparators", true);

        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        FlatLaf.updateUI();
    }
}
