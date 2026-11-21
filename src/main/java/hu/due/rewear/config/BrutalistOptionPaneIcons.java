package hu.due.rewear.config;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * Pure-vector Brutalist Monochrome modal icons for {@link javax.swing.JOptionPane}
 * (Information, Warning, Error, Question) that automatically adapt to Light and Dark themes.
 */
public final class BrutalistOptionPaneIcons {

    private static final int SIZE = 36;

    @FunctionalInterface
    private interface IconPainter {
        void paint(Graphics2D g, Color fg, Color bg);
    }

    private BrutalistOptionPaneIcons() {
    }

    /** Solid monochrome square badge with inverted geometric 'i'. */
    public static Icon information() {
        return create((g, fg, bg) -> {
            g.setColor(fg);
            g.fill(new Rectangle2D.Double(2, 2, 32, 32));

            g.setColor(bg);
            // Square dot
            g.fill(new Rectangle2D.Double(16, 8.5, 4, 4));
            // Vertical stem + crisp serif footing
            g.fill(new Rectangle2D.Double(16, 15.5, 4, 11.5));
            g.fill(new Rectangle2D.Double(13, 15.5, 7, 2.5));
            g.fill(new Rectangle2D.Double(13, 25, 10, 2.5));
        });
    }

    /** Sharp monochrome triangle badge with inverted '!'. */
    public static Icon warning() {
        return create((g, fg, bg) -> {
            Path2D tri = new Path2D.Double();
            tri.moveTo(18, 2);
            tri.lineTo(34.5, 32.5);
            tri.lineTo(1.5, 32.5);
            tri.closePath();
            g.setColor(fg);
            g.fill(tri);

            g.setColor(bg);
            g.fill(new Rectangle2D.Double(16.2, 12, 3.6, 10.5));
            g.fill(new Rectangle2D.Double(16.2, 25.2, 3.6, 3.6));
        });
    }

    /** Solid monochrome square badge with inverted bold geometric 'X'. */
    public static Icon error() {
        return create((g, fg, bg) -> {
            g.setColor(fg);
            g.fill(new Rectangle2D.Double(2, 2, 32, 32));

            g.setColor(bg);
            g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
            g.draw(new Line2D.Double(11.5, 11.5, 24.5, 24.5));
            g.draw(new Line2D.Double(24.5, 11.5, 11.5, 24.5));
        });
    }

    /** Boxed 2px monochrome square with crisp geometric '?'. */
    public static Icon question() {
        return create((g, fg, bg) -> {
            g.setColor(bg);
            g.fill(new Rectangle2D.Double(2, 2, 32, 32));

            g.setColor(fg);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
            g.draw(new Rectangle2D.Double(2, 2, 32, 32));

            // Geometric '?' curve + stem
            g.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
            g.draw(new Arc2D.Double(12.5, 8.5, 11, 9.5, -25, 205, Arc2D.OPEN));
            g.draw(new Line2D.Double(23.2, 14.5, 18, 19.5));
            g.draw(new Line2D.Double(18, 19.5, 18, 22));
            // Square dot
            g.fill(new Rectangle2D.Double(16.2, 25.5, 3.6, 3.6));
        });
    }

    private static Icon create(IconPainter painter) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                    g2.translate(x, y);
                    painter.paint(g2, ThemeManager.fg(), ThemeManager.bg());
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return SIZE;
            }

            @Override
            public int getIconHeight() {
                return SIZE;
            }
        };
    }
}
