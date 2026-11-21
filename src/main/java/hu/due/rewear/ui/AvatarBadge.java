package hu.due.rewear.ui;

import hu.due.rewear.config.ThemeManager;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Circular Brutalist Monochrome user avatar component rendering either an uploaded
 * profile image cropped cleanly to a circle, or a high-contrast inverted monogram badge.
 * Also provides a crisp vector Gear icon for the sidebar settings trigger.
 */
public final class AvatarBadge extends JComponent {

    private final int diameter;
    private String initials = "?";
    private BufferedImage avatarImage;

    public AvatarBadge(int diameter) {
        this.diameter = diameter;
        Dimension dim = new Dimension(diameter, diameter);
        setPreferredSize(dim);
        setMinimumSize(dim);
        setMaximumSize(dim);
        setOpaque(false);
    }

    public void setUserAppearance(String initials, byte[] avatarPng) {
        this.initials = (initials != null && !initials.isBlank()) ? initials : "?";
        this.avatarImage = decodeImage(avatarPng);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

            float inset = 1.5f;
            float d = diameter - inset * 2f;
            Ellipse2D.Float circle = new Ellipse2D.Float(inset, inset, d, d);

            if (avatarImage != null) {
                g2.setClip(circle);
                g2.drawImage(avatarImage, Math.round(inset), Math.round(inset), Math.round(d), Math.round(d), null);
                g2.setClip(null);
            } else {
                g2.setColor(ThemeManager.fg());
                g2.fill(circle);

                g2.setColor(ThemeManager.bg());
                float fontSize = Math.max(11f, diameter * 0.38f);
                g2.setFont(ThemeManager.monoFont(Font.BOLD, fontSize));
                FontMetrics fm = g2.getFontMetrics();
                int tx = Math.round((diameter - fm.stringWidth(initials)) / 2f);
                int ty = Math.round((diameter + fm.getAscent() - fm.getDescent()) / 2f);
                g2.drawString(initials, tx, ty);
            }

            g2.setColor(ThemeManager.border());
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(circle);
        } finally {
            g2.dispose();
        }
    }

    private static BufferedImage decodeImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(bais);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reads a user-selected PNG/JPG image, center-crops it to a square, scales smoothly
     * to 256x256, and returns compact PNG bytes suitable for SQLite BLOB storage.
     */
    public static byte[] encodeAvatarPng(File imageFile) throws IOException {
        BufferedImage src = ImageIO.read(imageFile);
        if (src == null) {
            throw new IOException("Unsupported or unreadable image format.");
        }
        int size = Math.min(src.getWidth(), src.getHeight());
        int sx = (src.getWidth() - size) / 2;
        int sy = (src.getHeight() - size) / 2;
        BufferedImage cropped = src.getSubimage(sx, sy, size, size);

        int target = 256;
        BufferedImage scaled = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(cropped, 0, 0, target, target, null);
        } finally {
            g2.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(scaled, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Crisp vector-drawn monochrome Gear icon that dynamically adapts to Light & Dark themes.
     */
    public static Icon createGearIcon(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color color = c != null ? c.getForeground() : ThemeManager.fg();
                    g2.setColor(color);

                    double cx = x + size / 2.0;
                    double cy = y + size / 2.0;
                    double outerR = size * 0.43;
                    double ringR = size * 0.30;
                    double holeR = size * 0.14;

                    // 8 mechanical gear teeth
                    g2.setStroke(new BasicStroke(Math.max(2.0f, size * 0.14f),
                            BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                    for (int i = 0; i < 8; i++) {
                        double angle = i * Math.PI / 4.0;
                        double x1 = cx + Math.cos(angle) * (ringR - 0.5);
                        double y1 = cy + Math.sin(angle) * (ringR - 0.5);
                        double x2 = cx + Math.cos(angle) * outerR;
                        double y2 = cy + Math.sin(angle) * outerR;
                        g2.drawLine((int) Math.round(x1), (int) Math.round(y1),
                                (int) Math.round(x2), (int) Math.round(y2));
                    }

                    // Main ring
                    float ringStroke = Math.max(2.0f, size * 0.16f);
                    g2.setStroke(new BasicStroke(ringStroke));
                    double r = (ringR + holeR) / 2.0;
                    g2.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2.0, r * 2.0));
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }
}
