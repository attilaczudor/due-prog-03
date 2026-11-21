package hu.due.rewear.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.Window;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Pure vector renderer matching the bundled ReWear WMS SVG logos
 * ({@code /images/rewear-logo-light.svg}, {@code /images/rewear-logo-dark.svg},
 * and {@code /images/rewear-app-icon.svg}) with macOS-style rounded corners.
 * Renders at native display/print resolution everywhere: Login screen, Sidebar,
 * Window/Dock icon, Thermal/A4 QR Stickers, and PDF Delivery Notes.
 */
public final class LogoAssets {

    private static final double VIEWBOX = 512.0;

    private static final Path2D WORDMARK_PATH = buildWordmarkPath();

    private LogoAssets() {
    }

    /**
     * Paints the macOS-rounded ReWear SVG vector logo directly into any {@link Graphics2D}
     * at exact floating-point coordinates with infinite sharpness.
     */
    public static void paintLogo(Graphics2D g2, double x, double y, double size, boolean dark) {
        if (g2 == null || size <= 0) {
            return;
        }
        Graphics2D g = (Graphics2D) g2.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            g.translate(x, y);
            double scale = size / VIEWBOX;
            g.scale(scale, scale);

            Color ink = dark ? Color.WHITE : Color.BLACK;
            paintVectorArtwork(g, ink);
        } finally {
            g.dispose();
        }
    }

    /**
     * Paints the macOS rounded-square Dock/App icon (white vector mark on pure black squircle).
     */
    public static void paintAppIcon(Graphics2D g2, double x, double y, double size) {
        if (g2 == null || size <= 0) {
            return;
        }
        Graphics2D g = (Graphics2D) g2.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            g.translate(x, y);
            double scale = size / VIEWBOX;
            g.scale(scale, scale);

            // Solid pure-black macOS rounded app icon tile
            g.setColor(Color.BLACK);
            g.fill(new RoundRectangle2D.Double(16, 16, 480, 480, 212, 212));

            // Crisp white inner ReWear vector mark scaled 86% and centered
            g.translate(36.0, 36.0);
            g.scale(0.86, 0.86);
            paintVectorArtwork(g, Color.WHITE);
        } finally {
            g.dispose();
        }
    }

    private static void paintVectorArtwork(Graphics2D g, Color ink) {
        g.setColor(ink);

        // macOS-style rounded outer frame (rx=84 -> arc=168)
        g.setStroke(new BasicStroke(18f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g.draw(new RoundRectangle2D.Double(20, 20, 472, 472, 168, 168));

        // 3 QR Finder Patterns (top-left, top-right, bottom-left)
        BasicStroke finderStroke = new BasicStroke(14f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
        g.setStroke(finderStroke);
        drawFinder(g, 70, 68);
        drawFinder(g, 366, 68);
        drawFinder(g, 70, 276);

        // Bottom-Right 2x2 QR Module Grid
        g.setStroke(new BasicStroke(11f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g.draw(new Rectangle2D.Double(366, 276, 28, 28));
        g.draw(new Rectangle2D.Double(414, 276, 28, 28));
        g.draw(new Rectangle2D.Double(366, 324, 28, 28));
        g.draw(new Rectangle2D.Double(414, 324, 28, 28));

        // Central Clothes Hanger Hook (arc from 180 deg clockwise 270 deg to bottom + stem)
        g.setStroke(new BasicStroke(16f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        Path2D hook = new Path2D.Double();
        hook.append(new Arc2D.Double(224, 66, 64, 64, 180, -270, Arc2D.OPEN), false);
        hook.lineTo(256, 154);
        g.draw(hook);

        // Central Clothes Hanger Triangle
        g.setStroke(new BasicStroke(17f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        Path2D hanger = new Path2D.Double();
        hanger.moveTo(74, 246);
        hanger.lineTo(256, 152);
        hanger.lineTo(438, 246);
        hanger.closePath();
        g.draw(hanger);

        // Geometric Brutalist REWEAR Wordmark (horizontally centered)
        Graphics2D gw = (Graphics2D) g.create();
        try {
            gw.translate(-9.5, 0);
            gw.fill(WORDMARK_PATH);
        } finally {
            gw.dispose();
        }
    }

    private static void drawFinder(Graphics2D g, double x, double y) {
        g.draw(new Rectangle2D.Double(x, y, 76, 76));
        g.fill(new Rectangle2D.Double(x + 21, y + 21, 34, 34));
    }

    /** Returns a HiDPI-sharp vector Swing {@link Icon} matching the SVG logo. */
    public static Icon logoIcon(int sizePx, boolean dark) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                paintLogo((Graphics2D) g, x, y, sizePx, dark);
            }

            @Override
            public int getIconWidth() {
                return sizePx;
            }

            @Override
            public int getIconHeight() {
                return sizePx;
            }
        };
    }

    /** Renders the SVG vector logo to a high-resolution RGBA {@link BufferedImage}. */
    public static BufferedImage logoImage(boolean dark) {
        return renderLogoImage(512, dark);
    }

    public static BufferedImage renderLogoImage(int sizePx, boolean dark) {
        BufferedImage img = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            paintLogo(g2, 0, 0, sizePx, dark);
        } finally {
            g2.dispose();
        }
        return img;
    }

    /** Renders the macOS-style rounded App/Dock icon SVG to a crisp {@link BufferedImage}. */
    public static BufferedImage appIconImage() {
        return renderAppIconImage(512);
    }

    public static BufferedImage renderAppIconImage(int sizePx) {
        BufferedImage img = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            paintAppIcon(g2, 0, 0, sizePx);
        } finally {
            g2.dispose();
        }
        return img;
    }

    public static void applyWindowIcon(Window window) {
        if (window == null) {
            return;
        }
        window.setIconImages(List.of(
                renderAppIconImage(16),
                renderAppIconImage(32),
                renderAppIconImage(64),
                renderAppIconImage(128),
                renderAppIconImage(256)
        ));
    }

    public static void applyDockIcon() {
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(renderAppIconImage(512));
                }
            }
        } catch (Throwable ignored) {
            // Headless or unsupported desktop environment
        }
    }

    private static Path2D buildWordmarkPath() {
        Path2D p = new Path2D.Double(Path2D.WIND_EVEN_ODD);

        // R (x: 84..134)
        appendLetterR(p, 84);

        // E (x: 145..190)
        appendLetterE(p, 145);

        // W (x: 199..273)
        p.moveTo(199, 384);
        p.lineTo(212, 384);
        p.lineTo(221, 426);
        p.lineTo(230, 384);
        p.lineTo(242, 384);
        p.lineTo(251, 426);
        p.lineTo(260, 384);
        p.lineTo(273, 384);
        p.lineTo(258, 444);
        p.lineTo(244, 444);
        p.lineTo(236, 406);
        p.lineTo(228, 444);
        p.lineTo(214, 444);
        p.closePath();

        // E (x: 283..328)
        appendLetterE(p, 283);

        // A (x: 335..389)
        p.moveTo(355, 384);
        p.lineTo(369, 384);
        p.lineTo(389, 444);
        p.lineTo(375, 444);
        p.lineTo(371, 431);
        p.lineTo(353, 431);
        p.lineTo(349, 444);
        p.lineTo(335, 444);
        p.closePath();
        p.moveTo(362, 399);
        p.lineTo(356, 420);
        p.lineTo(368, 420);
        p.closePath();

        // R (x: 397..447)
        appendLetterR(p, 397);

        return p;
    }

    private static void appendLetterR(Path2D p, double ox) {
        p.moveTo(ox, 384);
        p.lineTo(ox + 32, 384);
        p.curveTo(ox + 43, 384, ox + 49, 391, ox + 49, 402);
        p.curveTo(ox + 49, 411, ox + 44, 417, ox + 36, 419);
        p.lineTo(ox + 50, 444);
        p.lineTo(ox + 36, 444);
        p.lineTo(ox + 23, 421);
        p.lineTo(ox + 13, 421);
        p.lineTo(ox + 13, 444);
        p.lineTo(ox, 444);
        p.closePath();

        // Counter cutout
        p.moveTo(ox + 13, 395);
        p.lineTo(ox + 13, 410);
        p.lineTo(ox + 29, 410);
        p.curveTo(ox + 34, 410, ox + 36, 407, ox + 36, 402.5);
        p.curveTo(ox + 36, 398, ox + 34, 395, ox + 29, 395);
        p.closePath();
    }

    private static void appendLetterE(Path2D p, double ox) {
        p.moveTo(ox, 384);
        p.lineTo(ox + 44, 384);
        p.lineTo(ox + 44, 396);
        p.lineTo(ox + 13, 396);
        p.lineTo(ox + 13, 408);
        p.lineTo(ox + 40, 408);
        p.lineTo(ox + 40, 419);
        p.lineTo(ox + 13, 419);
        p.lineTo(ox + 13, 432);
        p.lineTo(ox + 45, 432);
        p.lineTo(ox + 45, 444);
        p.lineTo(ox, 444);
        p.closePath();
    }
}
