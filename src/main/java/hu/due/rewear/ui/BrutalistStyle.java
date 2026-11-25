package hu.due.rewear.ui;

import com.formdev.flatlaf.FlatClientProperties;
import hu.due.rewear.config.ThemeManager;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

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
                        + "; hoverForeground:" + fgHex
                        + "; pressedBackground:" + pressHex
                        + "; pressedForeground:" + fgHex
                        + "; focusedBackground:" + bgHex
                        + "; focusedForeground:" + fgHex);
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
                        + "; hoverForeground:" + fgHex
                        + "; pressedBackground:" + pressHex
                        + "; pressedForeground:" + fgHex
                        + "; focusedBackground:" + bgHex
                        + "; focusedForeground:" + fgHex);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeManager.border(), 1),
                BorderFactory.createEmptyBorder(5, 14, 5, 14)
        ));
    }

    static void styleRailToggle(JToggleButton button, boolean collapsed) {
        boolean dark = ThemeManager.isDarkMode();
        boolean selected = button.isSelected();
        String bg = dark ? "#000000" : "#FFFFFF";
        String fg = dark ? "#FFFFFF" : "#000000";
        String normalBg = selected ? fg : bg;
        String normalFg = selected ? bg : fg;
        String hoverBg = selected ? fg : dark ? "#1C1C1C" : "#EAEAEA";
        String pressedBg = selected ? fg : dark ? "#333333" : "#CFCFCF";
        int pad = collapsed ? 0 : 10;

        button.setCursor(Cursor.getPredefinedCursor(selected ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
        button.setBorder(boxBorder(6, pad));
        button.setBackground(selected ? ThemeManager.fg() : ThemeManager.bg());
        button.setForeground(selected ? ThemeManager.bg() : ThemeManager.fg());
        button.putClientProperty(FlatClientProperties.STYLE,
                "background:" + normalBg + "; foreground:" + normalFg
                        + "; selectedBackground:" + fg + "; selectedForeground:" + bg
                        + "; hoverBackground:" + hoverBg + "; hoverForeground:" + normalFg
                        + "; pressedBackground:" + pressedBg + "; pressedForeground:" + normalFg
                        + "; focusedBackground:" + normalBg + "; focusedForeground:" + normalFg);
    }

    /** Convenience batch overload to apply {@link #styleSecondaryButton(JButton)} across a toolbar or dialog. */
    public static void styleSecondaryButtons(JButton... buttons) {
        if (buttons == null) {
            return;
        }
        for (JButton b : buttons) {
            if (b != null) {
                styleSecondaryButton(b);
            }
        }
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

    /**
     * A table whose cells carry their full value as a tooltip. Columns are often narrower than the text in
     * them — long material names, invoice events — and this keeps the whole value reachable.
     */
    public static JTable tableWithTooltips(javax.swing.table.TableModel model) {
        return new JTable(model) {
            @Override
            public java.awt.Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column) {
                java.awt.Component component = super.prepareRenderer(renderer, row, column);
                Object value = getValueAt(row, column);
                if (component instanceof JComponent cell) {
                    cell.setToolTipText(value == null || value.toString().isBlank() ? null : value.toString());
                }
                return component;
            }
        };
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
        TableSorting.install(table);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setBackground(ThemeManager.bg());
        table.getTableHeader().setForeground(ThemeManager.fg());
        table.getTableHeader().setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
    }

    public static void applyBoxCard(JComponent panel, int padV, int padH) {
        panel.setBackground(ThemeManager.bg());
        panel.setBorder(boxBorder(padV, padH));
    }

    /** Creates a standard read-only Swing table model (prevents accidental inline cell editing). */
    public static DefaultTableModel nonEditableTableModel() {
        return new DefaultTableModel() {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    /** Adds a label + input component row to a two-column {@link java.awt.GridBagLayout} form panel. */
    public static void addFormRow(JPanel form, GridBagConstraints gbc, int row, String label, Component input) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        form.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        form.add(input, gbc);
    }

    /** Binds the {@code Escape} key anywhere inside {@code dialog} to call {@link JDialog#dispose()}. */
    public static void installEscapeToClose(JDialog dialog) {
        if (dialog == null) {
            return;
        }
        JRootPane rootPane = dialog.getRootPane();
        if (rootPane == null) {
            return;
        }
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "rewear.closeDialog");
        rootPane.getActionMap().put("rewear.closeDialog", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        });
    }
}
