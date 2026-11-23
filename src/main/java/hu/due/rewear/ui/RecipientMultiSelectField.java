package hu.due.rewear.ui;

import com.formdev.flatlaf.FlatClientProperties;
import hu.due.rewear.config.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Brutalist Monochrome searchable multi-select recipient dropdown with live hot-search filtering
 * and removable recipient chips. Supports both internal-only team directories and optional
 * custom external email entry for privileged roles.
 */
public final class RecipientMultiSelectField extends JPanel {

    public record ContactOption(String displayName, String email) {
        public String normalizedEmail() {
            return email.trim().toLowerCase(Locale.ROOT);
        }

        public String formattedLabel() {
            return (displayName != null && !displayName.isBlank())
                    ? displayName + " <" + email + ">"
                    : email;
        }
    }

    private final List<ContactOption> directory;
    private final boolean allowCustomExternal;
    private final Map<String, String> selectedByNormEmail = new LinkedHashMap<>();

    private final JPanel chipsPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
    private final JTextField searchField = new JTextField();
    private final JButton dropdownBtn = new JButton("▾");

    private final JPopupMenu popup = new JPopupMenu();
    private final JPanel listPanel = new JPanel();
    private final JScrollPane listScroll = new JScrollPane(listPanel);
    private List<ContactOption> currentMatches = List.of();

    public RecipientMultiSelectField(List<ContactOption> directory,
                                     boolean allowCustomExternal,
                                     String placeholderText,
                                     String initialCsv) {
        this.directory = List.copyOf(directory);
        this.allowCustomExternal = allowCustomExternal;

        setLayout(new BorderLayout(0, 0));
        setBackground(ThemeManager.bg());
        setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));

        chipsPanel.setOpaque(false);
        chipsPanel.setVisible(false);
        add(chipsPanel, BorderLayout.NORTH);

        JPanel inputRow = new JPanel(new BorderLayout(0, 0));
        inputRow.setOpaque(false);

        searchField.setFont(ThemeManager.monoFont(Font.PLAIN, 12.5f));
        searchField.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 6));
        searchField.setBackground(ThemeManager.bg());
        searchField.setForeground(ThemeManager.fg());
        searchField.setCaretColor(ThemeManager.fg());
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholderText);

        dropdownBtn.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        dropdownBtn.setFocusPainted(false);
        dropdownBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        dropdownBtn.setPreferredSize(new Dimension(28, 28));
        dropdownBtn.setMargin(new Insets(0, 0, 0, 0));
        dropdownBtn.setBackground(ThemeManager.bg());
        dropdownBtn.setForeground(ThemeManager.fg());
        dropdownBtn.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, ThemeManager.border()));

        inputRow.add(searchField, BorderLayout.CENTER);
        inputRow.add(dropdownBtn, BorderLayout.EAST);
        add(inputRow, BorderLayout.CENTER);

        // Non-focusable popup so typing in searchField stays uninterrupted
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(ThemeManager.bg());
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.getVerticalScrollBar().setUnitIncrement(14);
        popup.setFocusable(false);
        popup.setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));
        popup.setBackground(ThemeManager.bg());
        popup.add(listScroll);

        wireEvents();
        loadInitialCsv(initialCsv);
    }

    private void wireEvents() {
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onQueryChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onQueryChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onQueryChanged();
            }
        });

        searchField.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!popup.isVisible()) {
                    showFilteredPopup();
                }
            }
        });

        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    popup.setVisible(false);
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    if (!popup.isVisible()) {
                        showFilteredPopup();
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    e.consume();
                    handleEnterKey();
                } else if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE && searchField.getText().isEmpty()
                        && !selectedByNormEmail.isEmpty()) {
                    String lastKey = null;
                    for (String k : selectedByNormEmail.keySet()) {
                        lastKey = k;
                    }
                    if (lastKey != null) {
                        selectedByNormEmail.remove(lastKey);
                        rebuildChips();
                        if (popup.isVisible()) {
                            rebuildPopupList();
                        }
                    }
                }
            }
        });

        dropdownBtn.addActionListener(e -> {
            if (popup.isVisible()) {
                popup.setVisible(false);
            } else {
                searchField.requestFocusInWindow();
                showFilteredPopup();
            }
        });
    }

    private void onQueryChanged() {
        if (!isShowing()) {
            return;
        }
        SwingUtilities.invokeLater(this::showFilteredPopup);
    }

    private void handleEnterKey() {
        String raw = searchField.getText().trim();
        if (raw.isEmpty()) {
            return;
        }
        if (!currentMatches.isEmpty()) {
            ContactOption top = currentMatches.get(0);
            toggleRecipient(top.email(), top.displayName());
            searchField.setText("");
            showFilteredPopup();
            return;
        }
        if (allowCustomExternal && raw.contains("@") && raw.contains(".") && !raw.contains(" ")) {
            addRecipient(raw, raw);
            searchField.setText("");
            popup.setVisible(false);
        }
    }

    private void loadInitialCsv(String initialCsv) {
        if (initialCsv == null || initialCsv.isBlank()) {
            return;
        }
        for (String token : initialCsv.split("[,;]")) {
            String addr = token.trim();
            if (addr.isEmpty()) {
                continue;
            }
            if (addr.contains("<") && addr.contains(">")) {
                addr = addr.substring(addr.indexOf('<') + 1, addr.indexOf('>')).trim();
            }
            if (addr.isEmpty()) {
                continue;
            }
            String norm = addr.toLowerCase(Locale.ROOT);
            ContactOption match = findInDirectory(norm);
            if (match != null) {
                selectedByNormEmail.put(norm, match.email());
            } else if (allowCustomExternal) {
                selectedByNormEmail.put(norm, addr);
            }
        }
        rebuildChips();
    }

    private ContactOption findInDirectory(String normEmail) {
        for (ContactOption opt : directory) {
            if (opt.normalizedEmail().equals(normEmail)) {
                return opt;
            }
        }
        return null;
    }

    private void addRecipient(String email, String displayName) {
        String clean = email.trim();
        if (clean.isEmpty()) {
            return;
        }
        selectedByNormEmail.put(clean.toLowerCase(Locale.ROOT), clean);
        rebuildChips();
    }

    private void toggleRecipient(String email, String displayName) {
        String norm = email.trim().toLowerCase(Locale.ROOT);
        if (selectedByNormEmail.containsKey(norm)) {
            selectedByNormEmail.remove(norm);
        } else {
            selectedByNormEmail.put(norm, email.trim());
        }
        rebuildChips();
    }

    private void showFilteredPopup() {
        if (!isShowing()) {
            return;
        }
        rebuildPopupList();
        int popupW = Math.max(getWidth(), 320);
        int rowCount = Math.max(1, currentMatches.size());
        int prefH = Math.min(220, rowCount * 30 + 8);
        listScroll.setPreferredSize(new Dimension(popupW - 2, prefH));
        popup.pack();
        if (!popup.isVisible()) {
            popup.show(this, 0, getHeight());
        }
        searchField.requestFocusInWindow();
    }

    private void rebuildPopupList() {
        String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<ContactOption> matches = new ArrayList<>();
        for (ContactOption opt : directory) {
            if (q.isEmpty()
                    || opt.formattedLabel().toLowerCase(Locale.ROOT).contains(q)
                    || opt.normalizedEmail().contains(q)) {
                matches.add(opt);
            }
        }
        currentMatches = matches;

        listPanel.removeAll();
        if (matches.isEmpty()) {
            javax.swing.JLabel empty = new javax.swing.JLabel("  —");
            empty.setFont(ThemeManager.monoFont(Font.PLAIN, 11.5f));
            empty.setForeground(ThemeManager.muted());
            empty.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            listPanel.add(empty);
        } else {
            for (ContactOption opt : matches) {
                boolean checked = selectedByNormEmail.containsKey(opt.normalizedEmail());
                JCheckBox cb = new JCheckBox(opt.formattedLabel(), checked);
                cb.setFocusable(false);
                cb.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
                cb.setBackground(ThemeManager.bg());
                cb.setForeground(ThemeManager.fg());
                cb.setOpaque(true);
                cb.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
                cb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                cb.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                cb.addActionListener(e -> {
                    toggleRecipient(opt.email(), opt.displayName());
                    searchField.requestFocusInWindow();
                });
                listPanel.add(cb);
            }
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    private void rebuildChips() {
        chipsPanel.removeAll();
        for (Map.Entry<String, String> entry : selectedByNormEmail.entrySet()) {
            String norm = entry.getKey();
            String email = entry.getValue();
            ContactOption opt = findInDirectory(norm);
            String label = (opt != null && opt.displayName() != null && !opt.displayName().isBlank())
                    ? opt.displayName()
                    : email;

            JButton chip = new JButton(label + "  ×");
            chip.setToolTipText(email);
            chip.setFont(ThemeManager.monoFont(Font.BOLD, 11f));
            chip.setFocusPainted(false);
            chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            chip.setBackground(ThemeManager.fg());
            chip.setForeground(ThemeManager.bg());
            chip.setOpaque(true);
            chip.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            chip.addActionListener(e -> {
                selectedByNormEmail.remove(norm);
                rebuildChips();
                if (popup.isVisible()) {
                    rebuildPopupList();
                }
            });
            chipsPanel.add(chip);
        }
        chipsPanel.setVisible(!selectedByNormEmail.isEmpty());
        chipsPanel.revalidate();
        chipsPanel.repaint();
        revalidate();
        repaint();
    }

    /** Returns selected recipient email addresses joined by {@code ", "}. */
    public String getSelectedAddressesCsv() {
        // Also accept a single typed custom external email if the user didn't hit Enter first
        String raw = searchField.getText().trim();
        if (allowCustomExternal && raw.contains("@") && raw.contains(".") && !raw.contains(" ")) {
            selectedByNormEmail.putIfAbsent(raw.toLowerCase(Locale.ROOT), raw);
        }
        return String.join(", ", selectedByNormEmail.values());
    }
}
