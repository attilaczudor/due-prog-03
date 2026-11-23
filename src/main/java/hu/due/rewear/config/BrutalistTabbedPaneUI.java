package hu.due.rewear.config;

import com.formdev.flatlaf.ui.FlatTabbedPaneUI;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import java.awt.Color;
import java.awt.Graphics;

/**
 * Custom FlatLaf {@link FlatTabbedPaneUI} ensuring that the currently active/selected tab
 * never changes background or foreground color on hover or press, while inactive tabs retain
 * crisp monochrome hover feedback.
 */
public final class BrutalistTabbedPaneUI extends FlatTabbedPaneUI {

    public static ComponentUI createUI(JComponent c) {
        return new BrutalistTabbedPaneUI();
    }

    @Override
    protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex,
                                      int x, int y, int w, int h, boolean isSelected) {
        if (isSelected) {
            Color selBg = selectedBackground != null ? selectedBackground : ThemeManager.fg();
            g.setColor(selBg);
            g.fillRect(x, y, w, h);
            return;
        }
        super.paintTabBackground(g, tabPlacement, tabIndex, x, y, w, h, false);
    }

    @Override
    protected Color getTabForeground(int tabPlacement, int tabIndex, boolean isSelected) {
        if (isSelected) {
            return selectedForeground != null ? selectedForeground : ThemeManager.bg();
        }
        return super.getTabForeground(tabPlacement, tabIndex, false);
    }
}
