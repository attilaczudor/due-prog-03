package hu.due.rewear.ui;

import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.SmartFolder;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class WorkspaceFolderRail extends JPanel {
    record Entry(String key, String label, Icon icon) { }

    private final I18nManager i18n;
    private final JButton create = new JButton();
    private final JButton collapse = new JButton();
    private final JPanel buttons = new JPanel();
    private final JPanel tools = new JPanel();
    private final JPanel footer = new JPanel();
    private final List<JButton> footerButtons = new ArrayList<>();
    private final JSeparator topLine = new JSeparator();
    private final JSeparator bottomLine = new JSeparator();
    private final Consumer<String> onSelect;
    private Consumer<SmartFolder> onEdit;
    private final String createKey;
    private final JScrollPane scroll = new JScrollPane(buttons,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private final int expandedWidth;
    private List<Entry> standard = List.of();
    private List<SmartFolder> smart = List.of();
    private final Map<String, JToggleButton> folderButtons = new LinkedHashMap<>();
    private String selected;
    private boolean collapsed;

    WorkspaceFolderRail(I18nManager i18n, String createKey, int expandedWidth, Runnable onCreate,
                        Consumer<String> onSelect) {
        super(new BorderLayout(0, 8));
        this.i18n = i18n;
        this.createKey = createKey;
        this.expandedWidth = expandedWidth;
        this.onSelect = onSelect;

        var header = new JPanel(new BorderLayout(0, 10));
        header.setOpaque(false);
        create.addActionListener(e -> onCreate.run());
        header.add(create, BorderLayout.CENTER);
        header.add(topLine, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(38);
        add(scroll, BorderLayout.CENTER);

        var bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        tools.setOpaque(false);
        tools.setLayout(new BoxLayout(tools, BoxLayout.Y_AXIS));
        bottom.add(tools, BorderLayout.NORTH);
        var footerHost = new JPanel(new BorderLayout(0, 8));
        footerHost.setOpaque(false);
        footerHost.add(bottomLine, BorderLayout.NORTH);
        footerHost.add(footer, BorderLayout.CENTER);
        bottom.add(footerHost, BorderLayout.CENTER);
        footer.setOpaque(false);
        add(bottom, BorderLayout.SOUTH);

        collapse.addActionListener(e -> {
            collapsed = !collapsed;
            render();
        });
        render();
    }

    void addUtility(JButton button) {
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        tools.add(button);
    }

    void addFooterButton(JButton button) {
        footerButtons.add(button);
        render();
    }

    void setFolderEditor(Consumer<SmartFolder> onEdit) {
        this.onEdit = onEdit;
    }

    void setEntries(List<Entry> standard, List<SmartFolder> folders, String selected) {
        boolean changed = !sameEntries(standard, this.standard) || !folders.equals(smart);
        boolean selectionChanged = !Objects.equals(selected, this.selected);
        this.standard = List.copyOf(standard);
        this.smart = List.copyOf(folders);
        this.selected = selected;
        if (changed) render();
        else if (selectionChanged) updateSelection();
        if (selectionChanged && folderButtons.containsKey(selected)) {
            var button = folderButtons.get(selected);
            SwingUtilities.invokeLater(() -> button.scrollRectToVisible(new Rectangle(button.getSize())));
        }
    }

    private static boolean sameEntries(List<Entry> first, List<Entry> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            Entry a = first.get(i);
            Entry b = second.get(i);
            if (!a.key().equals(b.key()) || !a.label().equals(b.label())) return false;
        }
        return true;
    }

    static String smartKey(long id) {
        return "smart:" + id;
    }

    static void labelIconButton(AbstractButton button, String label) {
        button.setToolTipText(label);
        button.getAccessibleContext().setAccessibleName(label);
    }

    void render() {
        setPreferredSize(new Dimension(collapsed ? 62 : expandedWidth, 0));
        String createLabel = i18n.t(createKey);
        create.setText(collapsed ? "" : "+ " + createLabel.toUpperCase(java.util.Locale.ROOT));
        create.setIcon(collapsed ? SidebarIcons.mailCompose(16) : null);
        create.setPreferredSize(new Dimension(0, 36));
        labelIconButton(create, createLabel);
        collapse.setIcon(collapsed ? SidebarIcons.arrowRight(16) : SidebarIcons.arrowLeft(16));
        labelIconButton(collapse, i18n.t(collapsed ? "sidebar.expand" : "sidebar.collapse"));

        buttons.removeAll();
        folderButtons.clear();
        standard.forEach(this::addEntry);
        if (!smart.isEmpty()) {
            var line = new JPanel();
            line.setAlignmentX(LEFT_ALIGNMENT);
            line.setMinimumSize(new Dimension(0, 1));
            line.setPreferredSize(new Dimension(0, 1));
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            line.setBackground(ThemeManager.border());
            buttons.add(Box.createVerticalStrut(6));
            buttons.add(line);
            buttons.add(Box.createVerticalStrut(9));
            smart.forEach(folder -> addEntry(new Entry(smartKey(folder.id()), folder.name(),
                    SmartFolderEditor.icon(folder.icon(), 16)), folder));
        }

        footer.removeAll();
        if (collapsed) {
            footer.setLayout(new GridLayout(0, 1, 0, 4));
            footerButtons.forEach(footer::add);
            footer.add(collapse);
        } else {
            footer.setLayout(new BorderLayout());
            var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            left.setOpaque(false);
            footerButtons.forEach(left::add);
            footer.add(left, BorderLayout.WEST);
            footer.add(collapse, BorderLayout.EAST);
        }
        tools.setVisible(!collapsed);
        applyTheme();
        revalidate();
        repaint();
    }

    private void addEntry(Entry entry) {
        addEntry(entry, null);
    }

    private void addEntry(Entry entry, SmartFolder folder) {
        var button = new JToggleButton(collapsed ? "" : entry.label(), entry.icon());
        labelIconButton(button, entry.label());
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setHorizontalAlignment(collapsed ? SwingConstants.CENTER : SwingConstants.LEFT);
        button.setIconTextGap(10);
        button.setPreferredSize(new Dimension(0, 34));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        button.setMinimumSize(new Dimension(0, 34));
        button.setSelected(entry.key().equals(selected));
        BrutalistStyle.styleRailToggle(button, collapsed);
        button.addActionListener(e -> {
            button.setSelected(true);
            onSelect.accept(entry.key());
        });
        button.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) {
                button.scrollRectToVisible(new Rectangle(button.getSize()));
            }
        });
        folderButtons.put(entry.key(), button);
        if (folder == null || onEdit == null || collapsed) {
            buttons.add(button);
        } else {
            var row = new JPanel(new BorderLayout(0, 0));
            row.setOpaque(false);
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            row.setPreferredSize(new Dimension(0, 34));
            row.setMinimumSize(new Dimension(0, 34));
            var gear = new JButton(AvatarBadge.createGearIcon(16));
            styleIconButton(gear);
            gear.setPreferredSize(new Dimension(34, 34));
            labelIconButton(gear, i18n.t("smart.edit") + " — " + folder.name());
            gear.addActionListener(e -> onEdit.accept(folder));
            row.add(button, BorderLayout.CENTER);
            row.add(gear, BorderLayout.EAST);
            buttons.add(row);
        }
        buttons.add(Box.createVerticalStrut(4));
    }

    private void updateSelection() {
        folderButtons.forEach((key, button) -> {
            button.setSelected(key.equals(selected));
            BrutalistStyle.styleRailToggle(button, collapsed);
        });
    }

    void applyTheme() {
        BrutalistStyle.applyBoxCard(this, 10, collapsed ? 8 : 10);
        buttons.setBackground(ThemeManager.bg());
        scroll.getViewport().setBackground(ThemeManager.bg());
        topLine.setForeground(ThemeManager.border());
        bottomLine.setForeground(ThemeManager.border());
        BrutalistStyle.stylePrimaryButton(create);
        styleIconButton(collapse);
        footerButtons.forEach(WorkspaceFolderRail::styleIconButton);
    }

    static void styleIconButton(JButton button) {
        BrutalistStyle.styleSecondaryButton(button);
        button.setPreferredSize(new Dimension(34, 34));
        button.setBorder(BorderFactory.createLineBorder(ThemeManager.border()));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }
}
