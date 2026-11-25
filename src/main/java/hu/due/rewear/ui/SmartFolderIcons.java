package hu.due.rewear.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import hu.due.rewear.config.ThemeManager;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Offline SVG catalogue. Stable keys also resolve icons saved by earlier releases. */
final class SmartFolderIcons {
    private static final String ROOT = "images/smart-folder/";
    static final List<Choice> CHOICES = readChoices();

    record Choice(String key, String asset, String english, String hungarian, String tags) {
        String label(String language) { return "hu".equalsIgnoreCase(language) ? hungarian : english; }

        boolean matches(String query) {
            return InvoiceLookupField.normalize(key + " " + english + " " + hungarian + " " + tags).contains(query);
        }
    }

    private SmartFolderIcons() { }

    static Choice choice(String key) {
        return CHOICES.stream().filter(entry -> entry.key().equals(key)).findFirst().orElse(CHOICES.get(2));
    }

    static Icon icon(String key, int size) {
        if ("inbox".equals(key)) return SidebarIcons.mailInbox(size);
        if ("sent".equals(key)) return SidebarIcons.mailSent(size);
        Choice choice = choice(key);
        String resource = ROOT + choice.asset() + ".svg";
        if (choice.asset().startsWith("flags/")) return new FlatSVGIcon(resource, size, Math.round(size * 0.75f));

        var black = new FlatSVGIcon(resource, size, size).setColorFilter(new FlatSVGIcon.ColorFilter(color -> Color.BLACK));
        var white = new FlatSVGIcon(resource, size, size).setColorFilter(new FlatSVGIcon.ColorFilter(color -> Color.WHITE));
        return new Icon() {
            @Override public int getIconWidth() { return black.getIconWidth(); }
            @Override public int getIconHeight() { return black.getIconHeight(); }
            @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
                boolean selected = component instanceof AbstractButton button && button.isSelected();
                (ThemeManager.isDarkMode() != selected ? white : black).paintIcon(component, graphics, x, y);
            }
        };
    }

    static Icon expandArrow() { return new FlatSVGIcon(ROOT + "lucide/chevron-down.svg", 16, 16)
            .setColorFilter(new FlatSVGIcon.ColorFilter(color -> ThemeManager.fg())); }

    private static List<Choice> readChoices() {
        try (var stream = Objects.requireNonNull(SmartFolderIcons.class.getResourceAsStream("/" + ROOT + "catalog.txt"));
             var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank()).map(line -> {
                String[] fields = line.split("\\|", 5);
                return new Choice(fields[0], fields[1], fields[2], fields[3], fields[4]);
            }).toList();
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
