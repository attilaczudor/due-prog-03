package hu.due.rewear;

import com.formdev.flatlaf.FlatLightLaf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Font;

/**
 * Application entry point for ReWear WMS (due-prog-03).
 */
public final class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    private App() {
    }

    public static void main(String[] args) {
        LOG.info("Starting ReWear WMS (due-prog-03)...");
        FlatLightLaf.setup();

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("ReWear WMS — due-prog-03");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setMinimumSize(new Dimension(640, 400));

            JLabel label = new JLabel("ReWear WMS — Project Bootstrap Ready", SwingConstants.CENTER);
            label.setFont(label.getFont().deriveFont(Font.BOLD, 18f));
            frame.add(label);

            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
