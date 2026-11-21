package hu.due.rewear.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Loads and scales the ReWear WMS Brutalist logo assets for the app/dock icon,
 * login screen, sidebar header, thermal QR stickers, and PDF delivery notes.
 */
public final class LogoAssets {

    private static final Logger LOG = LoggerFactory.getLogger(LogoAssets.class);

    private static final BufferedImage LIGHT_LOGO = load("/images/rewear-logo-light.png");
    private static final BufferedImage DARK_LOGO = load("/images/rewear-logo-dark.png");
    private static final BufferedImage APP_ICON = load("/images/rewear-app-icon.png");

    private LogoAssets() {
    }

    private static BufferedImage load(String resourcePath) {
        try (InputStream is = LogoAssets.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.warn("Logo resource not found: {}", resourcePath);
                return null;
            }
            return ImageIO.read(is);
        } catch (IOException e) {
            LOG.warn("Failed to read logo resource {}: {}", resourcePath, e.getMessage());
            return null;
        }
    }

    public static BufferedImage logoImage(boolean dark) {
        return dark ? DARK_LOGO : LIGHT_LOGO;
    }

    public static BufferedImage appIconImage() {
        return APP_ICON != null ? APP_ICON : LIGHT_LOGO;
    }

    public static ImageIcon logoIcon(int sizePx, boolean dark) {
        BufferedImage src = logoImage(dark);
        if (src == null) {
            return null;
        }
        return new ImageIcon(scaleHighQuality(src, sizePx, sizePx));
    }

    public static void applyWindowIcon(Window window) {
        BufferedImage icon = appIconImage();
        if (window == null || icon == null) {
            return;
        }
        window.setIconImages(List.of(
                scaleHighQuality(icon, 16, 16),
                scaleHighQuality(icon, 32, 32),
                scaleHighQuality(icon, 64, 64),
                scaleHighQuality(icon, 128, 128),
                scaleHighQuality(icon, 256, 256)
        ));
    }

    public static void applyDockIcon() {
        BufferedImage icon = appIconImage();
        if (icon == null) {
            return;
        }
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(icon);
                }
            }
        } catch (Throwable ignored) {
            // Headless or unsupported desktop environment
        }
    }

    public static BufferedImage scaleHighQuality(BufferedImage src, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(src.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, width, height, null);
        } finally {
            g2.dispose();
        }
        return scaled;
    }
}
