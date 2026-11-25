package hu.due.rewear.ui;

import com.formdev.flatlaf.FlatClientProperties;
import hu.due.rewear.config.ThemeManager;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/** Search field with one selected result, styled like the email address picker. */
final class InvoiceLookupField<T> extends JPanel {
    private final JTextField input = new JTextField(24);
    private final JPanel selection = new JPanel(new BorderLayout(4, 0));
    private final JLabel selectedLabel = new JLabel();
    private final JPopupMenu popup = new JPopupMenu();
    private final DefaultListModel<T> model = new DefaultListModel<>();
    private final JList<T> matches = new JList<>(model);
    private final JScrollPane results = new JScrollPane(matches);
    private final Function<T, String> display;
    private final Function<T, String> search;
    private final Consumer<T> changed;
    private List<T> choices = List.of();
    private T selected;

    InvoiceLookupField(String placeholder, String clearLabel, Function<T, String> display,
                       Function<T, String> search, Consumer<T> changed) {
        super(new BorderLayout(4, 0));
        this.display = display;
        this.search = search;
        this.changed = changed;
        setOpaque(false);
        input.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        input.getAccessibleContext().setAccessibleName(placeholder);
        add(input, BorderLayout.CENTER);

        var expand = expandButton;
        BrutalistStyle.styleSecondaryButton(expand);
        expand.setPreferredSize(new Dimension(30, 28));
        WorkspaceFolderRail.labelIconButton(expand, placeholder);
        expand.addActionListener(e -> {
            if (popup.isVisible()) popup.setVisible(false);
            else {
                input.requestFocusInWindow();
                showMatches();
            }
        });
        add(expand, BorderLayout.EAST);

        selection.setOpaque(false);
        var clear = new JButton("×");
        BrutalistStyle.styleSecondaryButton(clear);
        clear.setPreferredSize(new Dimension(30, 28));
        WorkspaceFolderRail.labelIconButton(clear, clearLabel);
        selection.add(selectedLabel, BorderLayout.CENTER);
        selection.add(clear, BorderLayout.EAST);
        selectedLabel.setBorder(BrutalistStyle.boxBorder(4, 8));
        selectedLabel.setForeground(ThemeManager.fg());
        clear.addActionListener(e -> {
            select(null);
            changed.accept(null);
            input.requestFocusInWindow();
        });

        matches.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        matches.setFocusable(false);
        var renderer = new DefaultListCellRenderer();
        matches.setCellRenderer((list, value, index, chosen, focus) ->
                renderer.getListCellRendererComponent(list, display.apply(value), index, chosen, focus));
        matches.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    int index = matches.locationToIndex(e.getPoint());
                    if (index >= 0 && matches.getCellBounds(index, index).contains(e.getPoint())) choose(model.get(index));
                }
            }
        });
        popup.setFocusable(false);
        popup.add(results);

        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { scheduleSearch(); }
            @Override public void removeUpdate(DocumentEvent e) { scheduleSearch(); }
            @Override public void changedUpdate(DocumentEvent e) { scheduleSearch(); }
        });
        input.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { showMatches(); }
        });
        bind(KeyEvent.VK_DOWN, "next", () -> {
            if (!popup.isVisible()) showMatches();
            else moveSelection(1);
        });
        bind(KeyEvent.VK_UP, "previous", () -> moveSelection(-1));
        bind(KeyEvent.VK_ESCAPE, "close", () -> popup.setVisible(false));
        bind(KeyEvent.VK_ENTER, "choose", () -> {
            if (popup.isVisible() && matches.getSelectedValue() != null) choose(matches.getSelectedValue());
            else showMatches();
        });
    }

    private void bind(int key, String name, Runnable action) {
        input.getInputMap().put(KeyStroke.getKeyStroke(key, 0), name);
        input.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    void setChoices(List<T> choices) {
        this.choices = List.copyOf(choices);
        if (popup.isVisible()) showMatches();
    }

    T selected() { return selected; }

    void select(T value) {
        selected = value;
        popup.setVisible(false);
        input.setText("");
        removeAll();
        if (value == null) {
            add(input, BorderLayout.CENTER);
            add(expandButton, BorderLayout.EAST);
        } else {
            String name = display.apply(value);
            selectedLabel.setText(name);
            selectedLabel.setToolTipText(name);
            add(selection, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    private final JButton expandButton = new JButton("▾");

    private void choose(T value) {
        select(value);
        changed.accept(value);
    }

    private void scheduleSearch() {
        if (input.isFocusOwner()) SwingUtilities.invokeLater(this::showMatches);
    }

    private void moveSelection(int step) {
        if (model.isEmpty() || !popup.isVisible()) return;
        int row = Math.max(0, Math.min(model.size() - 1, matches.getSelectedIndex() + step));
        matches.setSelectedIndex(row);
        matches.ensureIndexIsVisible(row);
    }

    private void showMatches() {
        if (selected != null || !input.isShowing()) return;
        String query = normalize(input.getText());
        model.clear();
        for (T choice : choices) {
            if (normalize(search.apply(choice)).contains(query)) model.addElement(choice);
        }
        if (model.isEmpty()) {
            popup.setVisible(false);
            return;
        }
        matches.setSelectedIndex(0);
        results.setPreferredSize(new Dimension(Math.max(260, getWidth()), Math.min(175, model.size() * 24 + 5)));
        if (!popup.isVisible()) popup.show(input, 0, input.getHeight());
        else popup.pack();
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").strip()
                .toLowerCase(Locale.ROOT);
    }
}
