package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPreferences;
import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Paints the rota calendar in both languages and for both kinds of user. Painting to an image catches a
 * layout or rendering error that merely constructing the view would not, and proves the grid is not blank.
 */
class RotaViewTest {

    @TempDir
    Path tempDir;

    /** Proportion of pixels that differ from the top-left corner, i.e. how much was actually drawn. */
    private static double inkFraction(BufferedImage image) {
        int background = image.getRGB(0, 0);
        int different = 0;
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                if (image.getRGB(x, y) != background) {
                    different++;
                }
            }
        }
        return different / (double) (image.getWidth() / 2 * (image.getHeight() / 2));
    }

    @Test
    void theCalendarDrawsForAPlannerAndForAWorkerInBothLanguages() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        I18nManager i18n = I18nManager.getInstance();
        i18n.loadBundledDefault();
        AppContext ctx = new AppContext(new DatabaseManager(tempDir.resolve("rota-ui.db")),
                new AppPreferences(tempDir.resolve("app.properties")), i18n);

        for (String username : List.of("manager", "sorter01")) {
            User user = ctx.getUserDao().findByUsername(username).orElseThrow();
            ctx.getSession().login(user);
            for (String language : List.of("en", "hu")) {
                i18n.setLanguage(language);
                AtomicReference<Double> ink = new AtomicReference<>(0.0);
                SwingUtilities.invokeAndWait(() -> {
                    RotaView view = new RotaView(ctx);
                    assertNotNull(view.getComponent());
                    // validate() lays out the whole tree; doLayout() would only place the top level.
                    view.setSize(new Dimension(1100, 700));
                    view.addNotify();
                    view.validate();
                    BufferedImage image = new BufferedImage(1100, 700, BufferedImage.TYPE_INT_RGB);
                    var g = image.createGraphics();
                    try {
                        view.paint(g);
                    } finally {
                        g.dispose();
                    }
                    ink.set(inkFraction(image));
                });
                assertTrue(ink.get() > 0.01,
                        "The calendar should be drawn, not blank (" + username + "/" + language + "): " + ink.get());
            }
        }
        i18n.setLanguage("en");
    }
}
