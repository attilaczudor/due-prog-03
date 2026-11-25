package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext.StartupStep;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Locale;

/** Displays initialization progress while storage and data are prepared off the Swing event thread. */
public final class StartupWindow extends JWindow {
    private final I18nManager i18n;
    private final JLabel stage = new JLabel();
    private final JProgressBar progress = new JProgressBar(0, 100);

    public StartupWindow(I18nManager i18n) {
        this.i18n = i18n;
        setName("rewear-startup");
        var layout = new JPanel();
        layout.setLayout(new BoxLayout(layout, BoxLayout.Y_AXIS));
        layout.setBackground(ThemeManager.bg());
        layout.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeManager.border()),
                BorderFactory.createEmptyBorder(26, 32, 26, 32)));

        var logo = new JLabel(LogoAssets.logoIcon(76, ThemeManager.isDarkMode()));
        var title = new JLabel("ReWear WMS");
        title.setFont(ThemeManager.monoFont(Font.BOLD, 17f));
        title.setForeground(ThemeManager.fg());
        stage.setForeground(ThemeManager.muted());
        stage.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
        for (var component : new javax.swing.JComponent[]{logo, title, stage, progress}) {
            component.setAlignmentX(Component.CENTER_ALIGNMENT);
            layout.add(component);
            if (component != progress) layout.add(Box.createVerticalStrut(14));
        }
        var barSize = new Dimension(360, 18);
        progress.setPreferredSize(barSize);
        progress.setMaximumSize(barSize);
        progress.setStringPainted(true);
        progress.getAccessibleContext().setAccessibleName(i18n.t("startup.progress"));
        updateStep(StartupStep.STORAGE);
        setContentPane(layout);
        pack();
        setLocationRelativeTo(null);
    }

    public void updateStep(StartupStep step) {
        stage.setText(i18n.t("startup." + step.name().toLowerCase(Locale.ROOT)));
        progress.setValue(switch (step) {
            case STORAGE -> 10;
            case DATABASE -> 35;
            case INITIAL_DATA -> 65;
            case SERVICES -> 90;
        });
    }
}
