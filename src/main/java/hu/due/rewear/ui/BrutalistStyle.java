package hu.due.rewear.ui;

import hu.due.rewear.config.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.border.Border;
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
        button.setBackground(ThemeManager.fg());
        button.setForeground(ThemeManager.bg());
        button.setFont(ThemeManager.monoFont(Font.BOLD, 12.5f));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeManager.fg(), 1),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)
        ));
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
        table.setGridColor(ThemeManager.grid());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
    }

    public static void applyBoxCard(JComponent panel, int padV, int padH) {
        panel.setBackground(ThemeManager.bg());
        panel.setBorder(boxBorder(padV, padH));
    }
}
