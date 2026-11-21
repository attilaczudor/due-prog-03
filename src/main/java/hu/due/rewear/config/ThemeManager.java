package hu.due.rewear.config;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Applies a high-contrast Brutalist Monochrome Light or Dark Look & Feel on FlatLaf:
 * pure #FFFFFF / #000000 surfaces, zero border radius (arc = 0), crisp 1px/2px solid borders,
 * inverted active states, and monospace typography throughout.
 */
public final class ThemeManager {

    @FunctionalInterface
    public interface ThemeChangeListener {
        void onThemeChanged(boolean dark);
    }

    // Brutalist Light Palette
    public static final Color LIGHT_BG = Color.WHITE;
    public static final Color LIGHT_SURFACE = new Color(0xF6F6F6);
    public static final Color LIGHT_FG = Color.BLACK;
    public static final Color LIGHT_MUTED = new Color(0x555555);
    public static final Color LIGHT_BORDER = Color.BLACK;
    public static final Color LIGHT_GRID = new Color(0xD4D4D4);

    // Brutalist Dark Palette
    public static final Color DARK_BG = Color.BLACK;
    public static final Color DARK_SURFACE = new Color(0x0F0F0F);
    public static final Color DARK_FG = Color.WHITE;
    public static final Color DARK_MUTED = new Color(0x9A9A9A);
    public static final Color DARK_BORDER = new Color(0xD0D0D0);
    public static final Color DARK_GRID = new Color(0x2A2A2A);

    private static volatile boolean darkMode = false;
    private static final List<ThemeChangeListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final String MONO_FAMILY = resolveMonospaceFamily();

    private ThemeManager() {
    }

    public static boolean isDarkMode() {
        return darkMode;
    }

    public static Color bg() {
        return darkMode ? DARK_BG : LIGHT_BG;
    }

    public static Color surface() {
        return darkMode ? DARK_SURFACE : LIGHT_SURFACE;
    }

    public static Color fg() {
        return darkMode ? DARK_FG : LIGHT_FG;
    }

    public static Color muted() {
        return darkMode ? DARK_MUTED : LIGHT_MUTED;
    }

    public static Color border() {
        return darkMode ? DARK_BORDER : LIGHT_BORDER;
    }

    public static Color grid() {
        return darkMode ? DARK_GRID : LIGHT_GRID;
    }

    public static Font monoFont(int style, float size) {
        return new Font(MONO_FAMILY, style, Math.round(size));
    }

    public static void addListener(ThemeChangeListener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(ThemeChangeListener listener) {
        LISTENERS.remove(listener);
    }

    public static void applyTheme(boolean dark) {
        darkMode = dark;

        String bgHex = dark ? "#000000" : "#FFFFFF";
        String surfaceHex = dark ? "#0F0F0F" : "#F6F6F6";
        String fgHex = dark ? "#FFFFFF" : "#000000";
        String mutedHex = dark ? "#9A9A9A" : "#555555";
        String borderHex = dark ? "#D0D0D0" : "#000000";
        String gridHex = dark ? "#2A2A2A" : "#D4D4D4";
        String hoverHex = dark ? "#1C1C1C" : "#EAEAEA";
        String pressHex = dark ? "#333333" : "#CFCFCF";
        String defaultHoverHex = dark ? "#DCDCDC" : "#2A2A2A";
        String defaultPressHex = dark ? "#BDBDBD" : "#424242";

        // Register FlatLaf global extra defaults BEFORE setup() so FlatLaf builds all derived colors cleanly
        Map<String, String> extra = new HashMap<>();
        extra.put("@background", bgHex);
        extra.put("@foreground", fgHex);
        extra.put("@componentBackground", bgHex);
        extra.put("@accentColor", fgHex);
        extra.put("@accentBaseColor", fgHex);
        extra.put("@accentBase2Color", fgHex);
        extra.put("@selectionBackground", fgHex);
        extra.put("@selectionForeground", bgHex);
        extra.put("@selectionInactiveBackground", hoverHex);
        extra.put("@selectionInactiveForeground", fgHex);
        extra.put("@disabledForeground", mutedHex);
        extra.put("@disabledBackground", surfaceHex);
        extra.put("Component.arc", "0");
        extra.put("Button.arc", "0");
        extra.put("TextComponent.arc", "0");
        extra.put("CheckBox.arc", "0");
        extra.put("ProgressBar.arc", "0");
        extra.put("ScrollBar.thumbArc", "0");
        extra.put("TabbedPane.tabArc", "0");
        extra.put("Component.borderColor", borderHex);
        extra.put("Component.focusedBorderColor", fgHex);
        extra.put("Component.focusColor", fgHex);
        extra.put("Component.focusWidth", "1");
        extra.put("Component.innerFocusWidth", "0");
        extra.put("Panel.background", bgHex);
        extra.put("Panel.foreground", fgHex);
        extra.put("Label.foreground", fgHex);
        extra.put("Viewport.background", bgHex);
        extra.put("ScrollPane.background", bgHex);
        extra.put("ScrollPane.borderColor", borderHex);
        extra.put("SplitPane.background", bgHex);
        extra.put("Button.background", bgHex);
        extra.put("Button.foreground", fgHex);
        extra.put("Button.borderColor", borderHex);
        extra.put("Button.focusedBorderColor", fgHex);
        extra.put("Button.hoverBackground", hoverHex);
        extra.put("Button.hoverForeground", fgHex);
        extra.put("Button.hoverBorderColor", fgHex);
        extra.put("Button.pressedBackground", pressHex);
        extra.put("Button.pressedForeground", fgHex);
        extra.put("Button.focusedBackground", bgHex);
        extra.put("Button.focusedForeground", fgHex);
        extra.put("Button.margin", "5,14,5,14");
        extra.put("Button.minimumWidth", "0");
        extra.put("Button.default.background", fgHex);
        extra.put("Button.default.foreground", bgHex);
        extra.put("Button.default.borderColor", fgHex);
        extra.put("Button.default.focusedBorderColor", fgHex);
        extra.put("Button.default.focusedBackground", fgHex);
        extra.put("Button.default.focusedForeground", bgHex);
        extra.put("Button.default.hoverBackground", defaultHoverHex);
        extra.put("Button.default.hoverForeground", bgHex);
        extra.put("Button.default.pressedBackground", defaultPressHex);
        extra.put("Button.default.pressedForeground", bgHex);
        extra.put("ToggleButton.background", bgHex);
        extra.put("ToggleButton.foreground", fgHex);
        extra.put("ToggleButton.borderColor", borderHex);
        extra.put("ToggleButton.hoverBackground", hoverHex);
        extra.put("ToggleButton.pressedBackground", pressHex);
        extra.put("ToggleButton.selectedBackground", fgHex);
        extra.put("ToggleButton.selectedForeground", bgHex);
        extra.put("ToggleButton.selectedHoverBackground", defaultHoverHex);
        extra.put("ToggleButton.selectedPressedBackground", defaultPressHex);
        extra.put("TextField.background", bgHex);
        extra.put("TextField.foreground", fgHex);
        extra.put("TextField.caretForeground", fgHex);
        extra.put("PasswordField.background", bgHex);
        extra.put("PasswordField.foreground", fgHex);
        extra.put("PasswordField.caretForeground", fgHex);
        extra.put("TextArea.background", bgHex);
        extra.put("TextArea.foreground", fgHex);
        extra.put("TextArea.caretForeground", fgHex);
        extra.put("ComboBox.background", bgHex);
        extra.put("ComboBox.foreground", fgHex);
        extra.put("ComboBox.buttonBackground", bgHex);
        extra.put("ComboBox.buttonArrowColor", fgHex);
        extra.put("ComboBox.popupBackground", bgHex);
        extra.put("Spinner.background", bgHex);
        extra.put("Spinner.foreground", fgHex);
        extra.put("List.background", bgHex);
        extra.put("List.foreground", fgHex);
        extra.put("Table.background", bgHex);
        extra.put("Table.foreground", fgHex);
        extra.put("Table.alternateRowColor", surfaceHex);
        extra.put("Table.gridColor", gridHex);
        extra.put("Table.selectionBackground", fgHex);
        extra.put("Table.selectionForeground", bgHex);
        extra.put("TableHeader.background", bgHex);
        extra.put("TableHeader.foreground", fgHex);
        extra.put("TableHeader.bottomSeparatorColor", borderHex);
        extra.put("TableHeader.separatorColor", gridHex);
        extra.put("TabbedPane.background", bgHex);
        extra.put("TabbedPane.foreground", fgHex);
        extra.put("TabbedPane.selectedBackground", fgHex);
        extra.put("TabbedPane.selectedForeground", bgHex);
        extra.put("TabbedPane.hoverColor", hoverHex);
        extra.put("TabbedPane.hoverForeground", fgHex);
        extra.put("TabbedPane.focusColor", fgHex);
        extra.put("TabbedPane.focusForeground", bgHex);
        extra.put("TabbedPane.underlineColor", fgHex);
        extra.put("TabbedPane.inactiveUnderlineColor", borderHex);
        extra.put("TabbedPane.tabSeparatorColor", borderHex);
        extra.put("TabbedPane.contentAreaColor", borderHex);
        extra.put("Separator.foreground", borderHex);
        extra.put("OptionPane.background", bgHex);
        extra.put("OptionPane.messageForeground", fgHex);
        extra.put("ProgressBar.background", bgHex);
        extra.put("ProgressBar.foreground", fgHex);
        extra.put("ProgressBar.selectionBackground", fgHex);
        extra.put("ProgressBar.selectionForeground", bgHex);
        extra.put("ScrollBar.track", bgHex);
        extra.put("ScrollBar.thumb", dark ? "#444444" : "#BDBDBD");

        FlatLaf.setGlobalExtraDefaults(extra);

        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }

        // Apply monospace font & crisp table flags on LookAndFeel defaults
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        Font baseFont = monoFont(Font.PLAIN, 12.5f);
        defaults.put("defaultFont", baseFont);
        defaults.put("Table.showHorizontalLines", true);
        defaults.put("Table.showVerticalLines", false);
        defaults.put("TableHeader.font", monoFont(Font.BOLD, 11.5f));
        defaults.put("TabbedPane.showTabSeparators", true);
        defaults.put("TabbedPane.tabSeparatorsFullHeight", true);
        defaults.put("OptionPane.informationIcon", BrutalistOptionPaneIcons.information());
        defaults.put("OptionPane.warningIcon", BrutalistOptionPaneIcons.warning());
        defaults.put("OptionPane.errorIcon", BrutalistOptionPaneIcons.error());
        defaults.put("OptionPane.questionIcon", BrutalistOptionPaneIcons.question());

        // Update all open Swing windows cleanly
        FlatLaf.updateUI();
        for (Window w : Window.getWindows()) {
            if (w.isDisplayable()) {
                SwingUtilities.updateComponentTreeUI(w);
            }
        }

        for (ThemeChangeListener listener : LISTENERS) {
            listener.onThemeChanged(dark);
        }
    }

    private static String resolveMonospaceFamily() {
        try {
            Set<String> available = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            for (String candidate : List.of("JetBrains Mono", "SF Mono", "Menlo", "Consolas", "DejaVu Sans Mono", "Courier New")) {
                if (available.contains(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // Headless environment fallback
        }
        return Font.MONOSPACED;
    }
}
