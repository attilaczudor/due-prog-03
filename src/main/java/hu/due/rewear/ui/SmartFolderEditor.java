package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.EmailMessage;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.SmartFolder;
import hu.due.rewear.model.SmartFolder.Field;
import hu.due.rewear.model.SmartFolder.Scope;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class SmartFolderEditor {
    private SmartFolderEditor() { }

    static Icon icon(String key, int size) {
        return SmartFolderIcons.icon(key, size);
    }

    static void show(Component owner, AppContext ctx, Scope scope, SmartFolder selected,
                     List<Invoice> invoices, Consumer<Long> changed) {
        show(owner, ctx, scope, selected, invoices, Map.of(), "folder", changed);
    }

    static void createFromInvoiceFilters(Component owner, AppContext ctx, List<Invoice> invoices,
                                         Map<Field, List<String>> rules, Consumer<Long> changed) {
        List<String> directions = rules.getOrDefault(Field.DIRECTION, List.of());
        String icon = directions.equals(List.of(InvoiceDirection.OUTGOING.name())) ? "sent"
                : directions.equals(List.of(InvoiceDirection.INCOMING.name())) ? "inbox" : "folder";
        show(owner, ctx, Scope.INVOICE, null, invoices, rules, icon, changed);
    }

    private static void show(Component owner, AppContext ctx, Scope scope, SmartFolder selected,
                             List<Invoice> invoices, Map<Field, List<String>> defaults,
                             String defaultIcon, Consumer<Long> changed) {
        var i18n = ctx.getI18n();
        boolean editing = selected != null && selected.id() > 0;
        Map<Field, List<String>> rules = selected == null ? defaults : selected.rules();
        Map<Field, Supplier<List<String>>> reads = new EnumMap<>(Field.class);
        JPanel form = new ScrollableColumn();
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));

        JTextField name = new JTextField(selected == null ? "" : selected.name());
        add(form, i18n.t("smart.name"), name);
        var icons = new IconPicker(i18n, selected == null ? defaultIcon : selected.icon());
        add(form, i18n.t("smart.icon"), icons);
        var hint = new JTextArea(i18n.t("smart.match_hint"), 3, 0);
        hint.setEditable(false);
        hint.setFocusable(false);
        hint.setOpaque(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setForeground(ThemeManager.muted());
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, hint.getPreferredSize().height));
        form.add(hint);
        form.add(Box.createVerticalStrut(8));

        if (scope == Scope.INVOICE) {
            var currencies = new LinkedHashMap<String, String>();
            ctx.getFinanceConfigDao().findCurrencies(false).forEach(c -> currencies.put(c.code(), c.code()));
            invoices.forEach(invoice -> currencies.putIfAbsent(invoice.effectiveCurrencyCode(), invoice.effectiveCurrencyCode()));
            String all = i18n.t("common.all");
            choose(form, reads, rules, Field.CURRENCY, i18n.t("currency.label"), currencies, all);
            choosePartners(form, reads, rules, InvoicePartnerOptions.load(ctx, invoices), i18n);
            choose(form, reads, rules, Field.DIRECTION, i18n.t("invoice.filter.direction"),
                    enumOptions(InvoiceDirection.values(), e -> i18n.t("invoice.direction." + e.name())), all);
            choose(form, reads, rules, Field.STATUS, i18n.t("common.status"),
                    enumOptions(InvoiceStatus.values(), e -> i18n.t("status.invoice." + e.name())), all);
            choose(form, reads, rules, Field.CATEGORY, i18n.t("invoice.category"),
                    enumOptions(InvoiceCategory.values(), e -> i18n.t("invoice.category." + e.name())), all);
            text(form, reads, rules, Field.SEARCH, i18n.t("common.search"));
        } else {
            var mailboxes = new LinkedHashMap<String, String>();
            for (String box : List.of(EmailMessage.FOLDER_INBOX, EmailMessage.FOLDER_SENT,
                    EmailMessage.FOLDER_DRAFTS, EmailMessage.FOLDER_TRASH)) {
                mailboxes.put(box, i18n.t("email.folder." + box.toLowerCase(java.util.Locale.ROOT)));
            }
            var mailboxRules = new EnumMap<Field, List<String>>(Field.class);
            mailboxRules.putAll(rules);
            if (editing) mailboxRules.putIfAbsent(Field.MAILBOX, List.of(EmailMessage.FOLDER_INBOX, EmailMessage.FOLDER_SENT));
            String all = i18n.t("common.all");
            choose(form, reads, mailboxRules, Field.MAILBOX, i18n.t("smart.mailbox"), mailboxes, all);
            text(form, reads, rules, Field.SENDER, i18n.t("smart.sender"));
            text(form, reads, rules, Field.RECIPIENT, i18n.t("smart.recipient"));
            text(form, reads, rules, Field.SUBJECT, i18n.t("smart.subject"));
            text(form, reads, rules, Field.BODY, i18n.t("smart.body"));
            text(form, reads, rules, Field.SEARCH, i18n.t("smart.keywords"));
            choose(form, reads, rules, Field.READ, i18n.t("smart.read"),
                    Map.of("true", i18n.t("common.yes"), "false", i18n.t("common.no")), all);
            choose(form, reads, rules, Field.ATTACHMENT, i18n.t("email.attachment"),
                    Map.of("true", i18n.t("common.yes"), "false", i18n.t("common.no")), all);
        }

        JScrollPane scroll = new JScrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        GraphicsConfiguration screen = owner.getGraphicsConfiguration();
        Rectangle bounds = screen.getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(screen);
        int availableHeight = bounds.height - screenInsets.top - screenInsets.bottom;
        int availableWidth = bounds.width - screenInsets.left - screenInsets.right;
        scroll.setPreferredSize(new Dimension(Math.min(570, Math.max(300, availableWidth - 72)),
                Math.min(660, Math.max(210, availableHeight - 132))));
        String[] actions = !editing
                ? new String[]{i18n.t("common.save"), i18n.t("common.cancel")}
                : new String[]{i18n.t("common.save"), i18n.t("common.cancel"), i18n.t("smart.delete")};
        String title = i18n.t(editing ? "smart.edit" : "smart.new");
        while (true) {
            int action = showEditor(owner, scroll, title, actions, availableWidth, availableHeight);
            if (action == 2 && editing) {
                if (JOptionPane.showConfirmDialog(owner, i18n.t("smart.delete_confirm", selected.name()),
                        i18n.t("smart.delete"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    try {
                        ctx.getSmartFolderDao().delete(ctx.getSession().requireCurrentUser().id(), scope, selected.id());
                        changed.accept(0L);
                        return;
                    } catch (RuntimeException ex) {
                        JOptionPane.showMessageDialog(owner, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
                    }
                }
                continue;
            }
            if (action != 0) return;
            if (name.getText().strip().isEmpty() || name.getText().strip().length() > 80) {
                JOptionPane.showMessageDialog(owner, i18n.t("smart.invalid_name"), title, JOptionPane.WARNING_MESSAGE);
                continue;
            }
            Map<Field, List<String>> saved = new EnumMap<>(Field.class);
            reads.forEach((field, read) -> {
                var values = read.get();
                if (!values.isEmpty()) saved.put(field, values);
            });
            try {
                long uid = ctx.getSession().requireCurrentUser().id();
                long id = ctx.getSmartFolderDao().save(uid, scope,
                        new SmartFolder(selected == null ? 0 : selected.id(), name.getText(), icons.selectedKey, saved));
                changed.accept(id);
                return;
            } catch (RuntimeException ex) {
                String message = ex.getCause() != null && String.valueOf(ex.getCause().getMessage()).contains("UNIQUE")
                        ? i18n.t("smart.duplicate") : ex.getMessage();
                JOptionPane.showMessageDialog(owner, message, i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static int showEditor(Component owner, JScrollPane scroll, String title, String[] actions,
                                  int availableWidth, int availableHeight) {
        var pane = new JOptionPane(scroll, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, actions, actions[0]);
        JDialog dialog = pane.createDialog(owner, title);
        dialog.setResizable(true);
        dialog.setMinimumSize(new Dimension(Math.min(380, availableWidth), Math.min(300, availableHeight)));
        dialog.setSize(Math.min(dialog.getWidth(), availableWidth), Math.min(dialog.getHeight(), availableHeight));
        dialog.setLocationRelativeTo(owner);
        BrutalistStyle.installEscapeToClose(dialog);
        try {
            pane.selectInitialValue();
            dialog.setVisible(true);
            return Arrays.asList(actions).indexOf(pane.getValue());
        } finally {
            dialog.dispose();
        }
    }

    private static final class ScrollableColumn extends JPanel implements Scrollable {
        ScrollableColumn() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 22; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(22, visible.height - 22);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static final class IconPicker extends JPanel {
        private static final int COLUMNS = 8;
        private static final int PAGE_SIZE = COLUMNS * 2;

        private final JPanel gallery = new JPanel(new GridLayout(0, COLUMNS, 4, 4));
        private final JTextField search = new JTextField();
        private final JButton more;
        private final JLabel empty;
        private final String language;
        private int limit = PAGE_SIZE;
        private String selectedKey;

        IconPicker(I18nManager i18n, String selectedKey) {
            super(new BorderLayout(0, 5));
            this.language = i18n.getActiveLanguage();
            this.selectedKey = SmartFolderIcons.choice(selectedKey).key();
            search.putClientProperty("JTextField.placeholderText", i18n.t("smart.icons.search"));
            search.getAccessibleContext().setAccessibleName(i18n.t("smart.icons.search"));
            add(search, BorderLayout.NORTH);
            var results = new JPanel(new BorderLayout());
            empty = new JLabel(i18n.t("smart.no_results"));
            results.add(gallery, BorderLayout.CENTER);
            results.add(empty, BorderLayout.NORTH);
            add(results, BorderLayout.CENTER);

            more = new JButton(i18n.t("smart.icons.more"), SmartFolderIcons.expandArrow());
            BrutalistStyle.styleSecondaryButton(more);
            more.addActionListener(e -> {
                limit += PAGE_SIZE;
                rebuild();
            });
            var footer = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
            footer.add(more);
            add(footer, BorderLayout.SOUTH);
            onTextChange(search, this::rebuild);
            rebuild();
        }

        private void rebuild() {
            String query = InvoiceLookupField.normalize(search.getText().strip());
            List<SmartFolderIcons.Choice> matches = SmartFolderIcons.CHOICES.stream()
                    .filter(choice -> choice.matches(query))
                    .limit(query.isEmpty() ? limit : SmartFolderIcons.CHOICES.size())
                    .toList();
            gallery.removeAll();
            var buttons = new ButtonGroup();
            for (var choice : matches) {
                String key = choice.key();
                var button = new JToggleButton(icon(key, 17));
                button.setPreferredSize(new Dimension(32, 32));
                WorkspaceFolderRail.labelIconButton(button, choice.label(language));
                button.addItemListener(e -> BrutalistStyle.styleRailToggle(button, true));
                button.addActionListener(e -> selectedKey = key);
                buttons.add(button);
                gallery.add(button);
                button.setSelected(selectedKey.equals(key));
                BrutalistStyle.styleRailToggle(button, true);
            }
            empty.setVisible(matches.isEmpty());
            more.getParent().setVisible(query.isEmpty() && limit < SmartFolderIcons.CHOICES.size());
            // BoxLayout caches each child's size requirements until invalidated.
            if (getParent() != null) getParent().invalidate();
            revalidate();
            repaint();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    static void onTextChange(JTextField input, Runnable changed) {
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { changed.run(); }
            @Override public void removeUpdate(DocumentEvent e) { changed.run(); }
            @Override public void changedUpdate(DocumentEvent e) { changed.run(); }
        });
    }

    private static void choosePartners(JPanel form, Map<Field, Supplier<List<String>>> reads,
                                       Map<Field, List<String>> rules, Map<String, InvoicePartnerOptions> directory,
                                       I18nManager i18n) {
        var labels = new LinkedHashMap<String, String>();
        var details = new LinkedHashMap<String, String>();
        var initialChoices = new LinkedHashMap<String, List<String>>();
        var saved = rules.getOrDefault(Field.PARTNER, List.of());
        var pending = new java.util.LinkedHashSet<>(saved);
        directory.forEach((key, partner) -> {
            labels.put(key, partner.name());
            details.put(key, partner.details());
            var previouslySaved = partner.matchingKeys().stream().filter(saved::contains).toList();
            if (!previouslySaved.isEmpty()) {
                initialChoices.put(key, previouslySaved);
                pending.removeAll(previouslySaved);
            }
        });
        var choices = new EnumMap<Field, List<String>>(Field.class);
        choices.put(Field.PARTNER, java.util.stream.Stream.concat(initialChoices.keySet().stream(), pending.stream()).toList());
        choose(form, reads, choices, Field.PARTNER, i18n.t("invoice.filters.partners"), labels, i18n.t("common.all"),
                details, i18n.t("invoice.entry.search_partner"), i18n.t("smart.no_results"));
        var selected = reads.get(Field.PARTNER);
        reads.put(Field.PARTNER, () -> selected.get().stream().flatMap(key -> {
            var previouslySaved = initialChoices.get(key);
            return (previouslySaved == null ? InvoicePartnerOptions.ruleKeys(directory, List.of(key)) : previouslySaved).stream();
        }).distinct().toList());
    }

    private static <E extends Enum<E>> Map<String, String> enumOptions(E[] values, Function<E, String> translate) {
        var options = new LinkedHashMap<String, String>();
        for (E value : values) options.put(value.name(), translate.apply(value));
        return options;
    }

    private static void choose(JPanel form, Map<Field, Supplier<List<String>>> reads,
                               Map<Field, List<String>> rules, Field field, String label, Map<String, String> options,
                               String allLabel) {
        choose(form, reads, rules, field, label, options, allLabel, Map.of(), null, null);
    }

    private static void choose(JPanel form, Map<Field, Supplier<List<String>>> reads,
                               Map<Field, List<String>> rules, Field field, String label, Map<String, String> options,
                               String allLabel, Map<String, String> details, String searchLabel, String emptyLabel) {
        var choices = new LinkedHashMap<String, JCheckBox>();
        var optionsPanel = new ScrollableColumn();
        var selected = rules.getOrDefault(field, List.of());
        var all = new LinkedHashMap<>(options);
        selected.forEach(key -> all.putIfAbsent(key, key));
        boolean allSelected = selected.isEmpty()
                || (field == Field.MAILBOX && selected.size() == options.size() && options.keySet().containsAll(selected));
        var selectAll = new JCheckBox(allLabel, allSelected);
        all.forEach((value, name) -> {
            var option = new JCheckBox(name, !allSelected && selected.contains(value));
            option.setToolTipText(name);
            option.setAlignmentX(Component.LEFT_ALIGNMENT);
            option.setMaximumSize(new Dimension(Integer.MAX_VALUE, option.getPreferredSize().height));
            optionsPanel.add(option);
            choices.put(value, option);
            option.addActionListener(e -> selectAll.setSelected(choices.values().stream().noneMatch(JCheckBox::isSelected)));
        });
        selectAll.addActionListener(e -> {
            choices.values().forEach(option -> option.setSelected(false));
            selectAll.setSelected(true);
        });
        var group = new JPanel(new BorderLayout(0, 4));
        group.setOpaque(false);
        var controls = new JPanel(new BorderLayout(0, 4));
        controls.add(selectAll, BorderLayout.NORTH);
        group.add(controls, BorderLayout.NORTH);
        int visibleRows = searchLabel == null ? 8 : 10;
        if (searchLabel != null) {
            var search = new JTextField();
            search.putClientProperty("JTextField.placeholderText", searchLabel);
            search.getAccessibleContext().setAccessibleName(searchLabel);
            controls.add(search, BorderLayout.CENTER);
            var empty = new JLabel(emptyLabel);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            empty.setVisible(false);
            optionsPanel.add(empty);
            onTextChange(search, () -> {
                String query = InvoiceLookupField.normalize(search.getText().strip());
                choices.forEach((key, choice) -> choice.setVisible(InvoiceLookupField.normalize(
                        choice.getText() + " " + details.getOrDefault(key, "")).contains(query)));
                empty.setVisible(choices.values().stream().noneMatch(Component::isVisible));
                if (optionsPanel.getParent() instanceof JViewport viewport) viewport.setViewPosition(new Point());
                optionsPanel.revalidate();
                optionsPanel.repaint();
            });
        }
        if (searchLabel != null || all.size() > visibleRows) {
            var scroller = new JScrollPane(optionsPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            int rowHeight = selectAll.getPreferredSize().height;
            int rowsHeight = Math.max(3, Math.min(visibleRows, choices.size())) * rowHeight;
            Insets border = scroller.getInsets();
            scroller.setPreferredSize(new Dimension(0, rowsHeight + border.top + border.bottom));
            group.add(scroller, BorderLayout.CENTER);
        } else {
            group.add(optionsPanel, BorderLayout.CENTER);
        }
        add(form, label, group);
        reads.put(field, () -> {
            if (selectAll.isSelected() && field == Field.MAILBOX) return List.copyOf(options.keySet());
            return choices.entrySet().stream().filter(e -> e.getValue().isSelected()).map(Map.Entry::getKey).toList();
        });
    }

    private static void text(JPanel form, Map<Field, Supplier<List<String>>> reads,
                             Map<Field, List<String>> rules, Field field, String label) {
        var input = new JTextField(String.join("; ", rules.getOrDefault(field, List.of())));
        add(form, label, input);
        reads.put(field, () -> SmartFolder.parseSearchTerms(input.getText()));
    }

    private static void add(JPanel form, String title, JComponent field) {
        JLabel label = new JLabel(title);
        label.setLabelFor(field);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (!(field instanceof IconPicker)) {
            field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
        }
        form.add(label);
        form.add(Box.createVerticalStrut(3));
        form.add(field);
        form.add(Box.createVerticalStrut(9));
    }
}
