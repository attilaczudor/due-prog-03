package hu.due.rewear.ui;

import com.formdev.flatlaf.FlatClientProperties;
import hu.due.rewear.config.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.border.Border;
import java.awt.Dimension;
import java.awt.Font;

/**
 * Shared styling helpers implementing the stark Brutalist Monochrome visual language:
 * 1px/2px solid borders, zero border radius, inverted primary buttons, and boxed uppercase badges.
 */
public final class BrutalistStyle {

    private BrutalistStyle() {
    }

    public static Border boxBorder(int padV, int padH) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeManager.border(), 1),
                BorderFactory.createEmptyBorder(padV, padH, padV, padH)
        );
    }

    public static Border thickBottomRule(int padTop, int padBottom) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, ThemeManager.border()),
                BorderFactory.createEmptyBorder(padTop, 0, padBottom, 0)
        );
    }

    public static Border hairlineBottomRule(int padV, int padH) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeManager.border()),
                BorderFactory.createEmptyBorder(padV, padH, padV, padH)
        );
    }

    public static Border hairlineRightRule(int padV, int padH) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, ThemeManager.border()),
                BorderFactory.createEmptyBorder(padV, padH, padV, padH)
        );
    }

    public static Border hairlineTopRule(int padV, int padH) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeManager.border()),
                BorderFactory.createEmptyBorder(padV, padH, padV, padH)
        );
    }

    /** Styles a button as an inverted primary CTA (black bg/white text in Light, white bg/black text in Dark). */
    public static void stylePrimaryButton(JButton button) {
        boolean dark = ThemeManager.isDarkMode();
        String bgHex = dark ? "#FFFFFF" : "#000000";
        String fgHex = dark ? "#000000" : "#FFFFFF";
        String hoverHex = dark ? "#DCDCDC" : "#2A2A2A";
        String pressHex = dark ? "#BDBDBD" : "#424242";

        button.setBackground(ThemeManager.fg());
        button.setForeground(ThemeManager.bg());
        button.setFont(ThemeManager.monoFont(Font.BOLD, 12.5f));
        button.putClientProperty(FlatClientProperties.STYLE,
                "background:" + bgHex
                        + "; foreground:" + fgHex
                        + "; hoverBackground:" + hoverHex
                        + "; pressedBackground:" + pressHex
                        + "; focusedBackground:" + bgHex);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeManager.fg(), 1),
                BorderFactory.createEmptyBorder(5, 14, 5, 14)
        ));
    }

    /** Styles a secondary button with the exact same border & padding metrics as primary buttons. */
    public static void styleSecondaryButton(JButton button) {
        boolean dark = ThemeManager.isDarkMode();
        String bgHex = dark ? "#000000" : "#FFFFFF";
        String fgHex = dark ? "#FFFFFF" : "#000000";
        String hoverHex = dark ? "#1C1C1C" : "#EAEAEA";
        String pressHex = dark ? "#333333" : "#CFCFCF";

        button.setBackground(ThemeManager.bg());
        button.setForeground(ThemeManager.fg());
        button.setFont(ThemeManager.monoFont(Font.PLAIN, 12.5f));
        button.putClientProperty(FlatClientProperties.STYLE,
                "background:" + bgHex
                        + "; foreground:" + fgHex
                        + "; hoverBackground:" + hoverHex
                        + "; pressedBackground:" + pressHex
                        + "; focusedBackground:" + bgHex);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeManager.border(), 1),
                BorderFactory.createEmptyBorder(5, 14, 5, 14)
        ));
    }

    /** Equalizes preferred and minimum sizes across a group of buttons so they align identically. */
    public static void matchButtonSizes(JButton... buttons) {
        if (buttons == null || buttons.length == 0) {
            return;
        }
        int maxW = 0;
        int maxH = 0;
        for (JButton b : buttons) {
            b.setPreferredSize(null);
            Dimension d = b.getPreferredSize();
            maxW = Math.max(maxW, d.width);
            maxH = Math.max(maxH, d.height);
        }
        Dimension uniform = new Dimension(maxW, maxH);
        for (JButton b : buttons) {
            b.setPreferredSize(uniform);
            b.setMinimumSize(uniform);
        }
    }

    /** Styles a label as a crisp boxed brutalist tag (like [ LIVE CDC ] or [ LLM-CPU ]). */
    public static void styleBoxedTag(JLabel label, boolean inverted) {
        label.setOpaque(true);
        label.setFont(ThemeManager.monoFont(Font.BOLD, 11f));
        if (inverted) {
            label.setBackground(ThemeManager.fg());
            label.setForeground(ThemeManager.bg());
        } else {
            label.setBackground(ThemeManager.bg());
            label.setForeground(ThemeManager.fg());
        }
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeManager.border(), 1),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)
        ));
    }

    public static void styleTable(JTable table) {
        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setBackground(ThemeManager.bg());
        table.setForeground(ThemeManager.fg());
        table.setSelectionBackground(ThemeManager.fg());
        table.setSelectionForeground(ThemeManager.bg());
        table.setGridColor(ThemeManager.grid());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setBackground(ThemeManager.bg());
        table.getTableHeader().setForeground(ThemeManager.fg());
        table.getTableHeader().setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
    }

    public static void applyBoxCard(JComponent panel, int padV, int padH) {
        panel.setBackground(ThemeManager.bg());
        panel.setBorder(boxBorder(padV, padH));
    }
}
