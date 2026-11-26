package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.AppModule;
import hu.due.rewear.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Opens every module screen each role can reach, through the real {@link MainFrame}, and checks that it is
 * actually drawn. This walks the same path a person does — lazy view creation, navigation, the refresh that
 * runs before the screen appears — so a screen that throws or comes up blank fails the build.
 * <p>
 * Pass {@code -Dshot.dir=/some/folder} to also write a PNG of each screen for a human to look at.
 */
class ScreenWalkthroughTest {

    private static final int WIDTH = 1400;
    private static final int HEIGHT = 900;

    @TempDir
    Path tempDir;

    /** Share of sampled pixels that differ from the corner, i.e. how much was really painted. */
    private static double inkFraction(BufferedImage image) {
        int background = image.getRGB(0, 0);
        int different = 0;
        int sampled = 0;
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                sampled++;
                if (image.getRGB(x, y) != background) {
                    different++;
                }
            }
        }
        return different / (double) sampled;
    }

    /** The i18n key of every navigation button in the window, however the sidebar happens to be built. */
    private static void collectNavKeys(java.awt.Container root, Set<String> into) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JToggleButton button) {
                Object key = button.getClientProperty("i18nKey");
                if (key != null) {
                    into.add(key.toString());
                }
            }
            if (child instanceof java.awt.Container container) {
                collectNavKeys(container, into);
            }
        }
    }

    private static BufferedImage paint(MainFrame frame) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            frame.getContentPane().paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    @Test
    void everyScreenEveryRoleCanOpenIsDrawnWithoutFailing() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ThemeManager.applyTheme(false);
        AppContext ctx = new AppContext(new DatabaseManager(tempDir.resolve("walkthrough.db")),
                new AppPreferences(tempDir.resolve("app.properties")), i18n);
        // Only the keys these screens ask for, not whatever ran before them in this JVM.
        i18n.forgetMissingKeys();

        String shotDir = System.getProperty("shot.dir");
        List<String> problems = new ArrayList<>();
        List<String> visited = new ArrayList<>();

        for (String username : List.of("superadmin", "manager", "operator", "sorter01", "forklift", "forklift2")) {
            User user = ctx.getUserDao().findByUsername(username).orElseThrow();
            ctx.getSession().login(user);

            AtomicReference<MainFrame> frameRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = new MainFrame(ctx, () -> { });
                frame.setSize(WIDTH, HEIGHT);
                frame.addNotify();
                frame.validate();
                frameRef.set(frame);
            });
            MainFrame frame = frameRef.get();
            try {
                // A screen that draws but has no way in is not reachable at all: the sidebar's groups are
                // written out by hand, so a module added to the registry and forgotten there is invisible to
                // everybody except a test that navigates by view key.
                Set<String> navKeys = new java.util.HashSet<>();
                collectNavKeys(frame.getContentPane(), navKeys);
                for (AppModule module : AppModule.values()) {
                    if (ctx.getSession().canAccessModule(module) && !navKeys.contains(module.navI18nKey())) {
                        problems.add(username + "/" + module.name().toLowerCase()
                                + " has no way into it from the sidebar");
                    }
                }
                for (AppModule module : AppModule.values()) {
                    if (!ctx.getSession().canAccessModule(module)) {
                        continue;
                    }
                    String label = username + "/" + module.name().toLowerCase();
                    AtomicReference<Double> ink = new AtomicReference<>(-1.0);
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            frame.navigateTo(module.viewKey());
                            frame.validate();
                            BufferedImage image = paint(frame);
                            ink.set(inkFraction(image));
                            if (shotDir != null) {
                                ImageIO.write(image, "png", new File(shotDir, label.replace('/', '-') + ".png"));
                            }
                        } catch (Throwable t) {
                            failure.set(t);
                        }
                    });
                    visited.add(label);
                    if (failure.get() != null) {
                        problems.add(label + " threw " + failure.get());
                    } else if (ink.get() < 0.02) {
                        problems.add(label + " drew almost nothing (" + ink.get() + ")");
                    }
                }
            } finally {
                SwingUtilities.invokeAndWait(frame::dispose);
            }
            ctx.getSession().logout();
        }

        assertTrue(visited.size() > 40, "Expected to walk most of the application: " + visited.size());
        // A missing translation key throws nothing: the header simply reads [role.FORKLIFT] and the build
        // stays green until somebody looks at that screen. Two of those survived several passes that way.
        assertTrue(i18n.missingKeys().isEmpty(),
                "Screens asked for translation keys the bundle does not have: " + i18n.missingKeys());
        if (!problems.isEmpty()) {
            fail("Screens that did not come up properly:\n" + String.join("\n", problems));
        }
    }

    @Test
    void theFirstRunSetupScreenIsDrawnInBothLanguages() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        ThemeManager.applyTheme(false);
        AppContext ctx = new AppContext(new DatabaseManager(tempDir.resolve("fresh.db")),
                new AppPreferences(tempDir.resolve("fresh.properties")), i18n,
                step -> { }, AppContext.InitialData.AWAIT_SETUP);
        String shotDir = System.getProperty("shot.dir");

        for (String language : List.of("en", "hu")) {
            i18n.setLanguage(language);
            AtomicReference<Double> ink = new AtomicReference<>(-1.0);
            SwingUtilities.invokeAndWait(() -> {
                SetupWizardDialog dialog = new SetupWizardDialog(ctx);
                try {
                    dialog.addNotify();
                    dialog.validate();
                    BufferedImage image = new BufferedImage(dialog.getWidth(), dialog.getHeight(),
                            BufferedImage.TYPE_INT_RGB);
                    var g = image.createGraphics();
                    try {
                        dialog.getContentPane().paint(g);
                    } finally {
                        g.dispose();
                    }
                    ink.set(inkFraction(image));
                    if (shotDir != null) {
                        ImageIO.write(image, "png", new File(shotDir, "setup-" + language + ".png"));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    dialog.dispose();
                }
            });
            assertTrue(ink.get() > 0.02, "The setup screen should be drawn (" + language + "): " + ink.get());
        }
        i18n.setLanguage("en");
    }
}
