package hu.due.rewear.ui;

import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A collapsible secondary menu with persistent cards and a fixed footer. */
final class SectionNavigation extends JPanel {

    private static final int EXPANDED_WIDTH = 236;
    private static final int COLLAPSED_WIDTH = 62;
    private static final int BUTTON_HEIGHT = 34;

    private final I18nManager i18n;
    private final JPanel rail = new JPanel(new BorderLayout(0, 8));
    private final JPanel buttons = new JPanel();
    private final JScrollPane menuScroll = new JScrollPane(buttons,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private final JSeparator footerSeparator = new JSeparator();
    private final JButton collapseButton = new JButton();
    private final FlowLayout footerAlignment = new FlowLayout(FlowLayout.RIGHT, 0, 0);
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final JPanel content = new JPanel(new BorderLayout(0, 8));
    private final ButtonGroup group = new ButtonGroup();
    private final Map<String, JToggleButton> sections = new LinkedHashMap<>();
    private String selectedKey;
    private boolean collapsed;
    private java.util.function.Consumer<String> onSelect = key -> { };

    SectionNavigation(I18nManager i18n) {
        super(new BorderLayout(12, 0));
        this.i18n = i18n;
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        menuScroll.setBorder(BorderFactory.createEmptyBorder());
        var bar = menuScroll.getVerticalScrollBar();
        bar.setUnitIncrement(BUTTON_HEIGHT + 4);
        bar.setPreferredSize(new Dimension(9, bar.getPreferredSize().height));
        rail.add(menuScroll, BorderLayout.CENTER);

        collapseButton.setPreferredSize(new Dimension(BUTTON_HEIGHT, BUTTON_HEIGHT));
        collapseButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        collapseButton.addActionListener(e -> {
            collapsed = !collapsed;
            applyTranslations();
            applyTheme();
        });
        JPanel collapseHost = new JPanel(footerAlignment);
        collapseHost.setOpaque(false);
        collapseHost.add(collapseButton);
        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        footer.add(footerSeparator, BorderLayout.NORTH);
        footer.add(collapseHost, BorderLayout.CENTER);
        rail.add(footer, BorderLayout.SOUTH);

        content.add(cards, BorderLayout.CENTER);
        add(rail, BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        applyTranslations();
        applyTheme();
    }

    void addSection(String labelKey, Icon icon, JComponent view) {
        JToggleButton button = new JToggleButton(icon);
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
        // BoxLayout fills the viewport width while the button keeps its fixed row height.
        button.setPreferredSize(new Dimension(0, BUTTON_HEIGHT));
        button.setMinimumSize(new Dimension(0, BUTTON_HEIGHT));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUTTON_HEIGHT));
        button.addActionListener(e -> select(labelKey));
        button.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                button.scrollRectToVisible(new Rectangle(button.getSize()));
            }
        });
        group.add(button);
        sections.put(labelKey, button);
        buttons.add(button);
        buttons.add(Box.createVerticalStrut(4));
        cards.add(view, labelKey);
        if (selectedKey == null) {
            select(labelKey);
        }
    }

    void setContentHeader(JComponent header) {
        content.add(header, BorderLayout.NORTH);
    }

    String selectedKey() {
        return selectedKey;
    }

    void setSelectionListener(java.util.function.Consumer<String> listener) {
        onSelect = listener;
    }

    void select(String labelKey) {
        selectedKey = labelKey;
        sections.get(labelKey).setSelected(true);
        cardLayout.show(cards, labelKey);
        sections.values().forEach(button -> BrutalistStyle.styleRailToggle(button, collapsed));
        onSelect.accept(labelKey);
    }

    void applyTranslations() {
        rail.setPreferredSize(new Dimension(collapsed ? COLLAPSED_WIDTH : EXPANDED_WIDTH, 0));
        footerAlignment.setAlignment(collapsed ? FlowLayout.CENTER : FlowLayout.RIGHT);
        collapseButton.setIcon(collapsed ? SidebarIcons.arrowRight(16) : SidebarIcons.arrowLeft(16));
        String collapseLabel = i18n.t(collapsed ? "sidebar.expand" : "sidebar.collapse");
        collapseButton.setToolTipText(collapseLabel);
        collapseButton.getAccessibleContext().setAccessibleName(collapseLabel);
        sections.forEach((key, button) -> {
            String title = i18n.t(key);
            button.setText(collapsed ? "" : title.toUpperCase(Locale.ROOT));
            button.setToolTipText(title);
            button.getAccessibleContext().setAccessibleName(title);
            button.setHorizontalAlignment(collapsed ? SwingConstants.CENTER : SwingConstants.LEFT);
            button.setIconTextGap(collapsed ? 0 : 10);
        });
        revalidate();
        repaint();
    }

    void applyTheme() {
        BrutalistStyle.applyBoxCard(rail, 10, collapsed ? 8 : 10);
        buttons.setBackground(ThemeManager.bg());
        menuScroll.getViewport().setBackground(ThemeManager.bg());
        footerSeparator.setForeground(ThemeManager.border());
        footerSeparator.setBackground(ThemeManager.border());
        BrutalistStyle.styleSecondaryButton(collapseButton);
        collapseButton.setBorder(BorderFactory.createLineBorder(ThemeManager.border()));
        sections.values().forEach(button -> BrutalistStyle.styleRailToggle(button, collapsed));
    }
}
