package hu.due.rewear.config;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.List;
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
    public static final Color LIGHT_SURFACE = new Color(0xF8F8F8);
    public static final Color LIGHT_FG = Color.BLACK;
    public static final Color LIGHT_MUTED = new Color(0x555555);
    public static final Color LIGHT_BORDER = Color.BLACK;
    public static final Color LIGHT_GRID = new Color(0xD4D4D4);

    // Brutalist Dark Palette
    public static final Color DARK_BG = Color.BLACK;
    public static final Color DARK_SURFACE = new Color(0x0D0D0D);
    public static final Color DARK_FG = Color.WHITE;
    public static final Color DARK_MUTED = new Color(0x8E8E8E);
    public static final Color DARK_BORDER = new Color(0xD4D4D4);
    public static final Color DARK_GRID = new Color(0x2B2B2B);

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

        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }

        Color bg = bg();
        Color surface = surface();
        Color fg = fg();
        Color muted = muted();
        Color border = border();
        Color grid = grid();

        // Zero border radius everywhere (sharp brutalist geometry)
        UIManager.put("Button.arc", 0);
        UIManager.put("Component.arc", 0);
        UIManager.put("TextComponent.arc", 0);
        UIManager.put("CheckBox.arc", 0);
        UIManager.put("ProgressBar.arc", 0);
        UIManager.put("ScrollBar.thumbArc", 0);
        UIManager.put("TabbedPane.tabArc", 0);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.borderColor", border);
        UIManager.put("Component.focusedBorderColor", fg);
        UIManager.put("Component.disabledBorderColor", grid);

        // Global monospace typography
        Font baseFont = monoFont(Font.PLAIN, 12.5f);
        UIManager.put("defaultFont", baseFont);

        // Pure monochrome surfaces & inverted focus/selection
        UIManager.put("Panel.background", bg);
        UIManager.put("Panel.foreground", fg);
        UIManager.put("Viewport.background", bg);
        UIManager.put("ScrollPane.background", bg);
        UIManager.put("ScrollPane.borderColor", border);
        UIManager.put("SplitPane.background", bg);
        UIManager.put("SplitPaneDivider.draggingColor", fg);
        UIManager.put("Separator.foreground", border);
        UIManager.put("Separator.stripeWidth", 1);
        UIManager.put("Label.foreground", fg);
        UIManager.put("Label.disabledForeground", muted);

        // Boxed brutalist buttons & inverted toggle buttons
        UIManager.put("Button.background", bg);
        UIManager.put("Button.foreground", fg);
        UIManager.put("Button.borderColor", border);
        UIManager.put("Button.hoverBackground", surface);
        UIManager.put("Button.hoverBorderColor", fg);
        UIManager.put("Button.pressedBackground", fg);
        UIManager.put("Button.pressedForeground", bg);
        UIManager.put("Button.default.background", fg);
        UIManager.put("Button.default.foreground", bg);
        UIManager.put("Button.default.borderColor", fg);
        UIManager.put("Button.default.hoverBackground", dark ? new Color(0xE2E2E2) : new Color(0x222222));

        UIManager.put("ToggleButton.background", bg);
        UIManager.put("ToggleButton.foreground", fg);
        UIManager.put("ToggleButton.borderColor", border);
        UIManager.put("ToggleButton.hoverBackground", surface);
        UIManager.put("ToggleButton.selectedBackground", fg);
        UIManager.put("ToggleButton.selectedForeground", bg);

        // Text fields, combo boxes, spinners
        UIManager.put("TextField.background", bg);
        UIManager.put("TextField.foreground", fg);
        UIManager.put("TextField.caretForeground", fg);
        UIManager.put("TextField.selectionBackground", fg);
        UIManager.put("TextField.selectionForeground", bg);
        UIManager.put("PasswordField.background", bg);
        UIManager.put("PasswordField.foreground", fg);
        UIManager.put("PasswordField.caretForeground", fg);
        UIManager.put("TextArea.background", bg);
        UIManager.put("TextArea.foreground", fg);
        UIManager.put("TextArea.caretForeground", fg);
        UIManager.put("ComboBox.background", bg);
        UIManager.put("ComboBox.foreground", fg);
        UIManager.put("ComboBox.buttonBackground", bg);
        UIManager.put("ComboBox.buttonArrowColor", fg);
        UIManager.put("ComboBox.selectionBackground", fg);
        UIManager.put("ComboBox.selectionForeground", bg);
        UIManager.put("Spinner.background", bg);
        UIManager.put("Spinner.foreground", fg);

        // Brutalist Tabs (inverted active tab)
        UIManager.put("TabbedPane.background", bg);
        UIManager.put("TabbedPane.foreground", fg);
        UIManager.put("TabbedPane.selectedBackground", fg);
        UIManager.put("TabbedPane.selectedForeground", bg);
        UIManager.put("TabbedPane.underlineColor", fg);
        UIManager.put("TabbedPane.contentAreaColor", border);
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", true);

        // Crisp high-contrast Tables
        UIManager.put("Table.background", bg);
        UIManager.put("Table.foreground", fg);
        UIManager.put("Table.alternateRowColor", surface);
        UIManager.put("Table.gridColor", grid);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.selectionBackground", fg);
        UIManager.put("Table.selectionForeground", bg);
        UIManager.put("TableHeader.background", bg);
        UIManager.put("TableHeader.foreground", fg);
        UIManager.put("TableHeader.bottomSeparatorColor", border);
        UIManager.put("TableHeader.separatorColor", grid);
        UIManager.put("TableHeader.font", monoFont(Font.BOLD, 11.5f));

        // OptionPane & Dialogs
        UIManager.put("OptionPane.background", bg);
        UIManager.put("OptionPane.messageForeground", fg);
        UIManager.put("ProgressBar.background", bg);
        UIManager.put("ProgressBar.foreground", fg);
        UIManager.put("ProgressBar.selectionBackground", fg);
        UIManager.put("ProgressBar.selectionForeground", bg);

        FlatLaf.updateUI();
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
