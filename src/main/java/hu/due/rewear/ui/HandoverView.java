package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.model.HandoverIssue;
import hu.due.rewear.model.RotaShift.ShiftCode;
import hu.due.rewear.model.ShiftHandover;
import hu.due.rewear.model.User;
import hu.due.rewear.service.ShiftHandoverService;
import hu.due.rewear.service.ShiftHandoverService.NewHandover;
import hu.due.rewear.service.ShiftHandoverService.NewIssue;
import hu.due.rewear.util.BusinessClock;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The shift handover sheet, and everything still open across the shifts behind it.
 *
 * <p>The screen opens on the <em>last</em> shift rather than on today, which is the one piece of daily
 * management practice that decides the layout: the board has to be current before the next shift's meeting,
 * so writing it is a closing task and reading it is the first thing the incoming supervisor does.
 */
public final class HandoverView extends JPanel implements NavigableView {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;
    private final ShiftHandoverService service;

    private final JTextField dayField = new JTextField(10);
    private final JComboBox<String> shiftBox = new JComboBox<>();
    private final JButton showBtn = new JButton();
    private final JButton latestBtn = new JButton();
    private final JButton writeBtn = new JButton();
    private final JButton acknowledgeBtn = new JButton();
    private final JButton addIssueBtn = new JButton();
    private final JButton resolveBtn = new JButton();
    private final JLabel feedbackLabel = new JLabel(" ");
    private final JLabel summaryLabel = new JLabel(" ");

    private final JTextArea document = new JTextArea();
    private final DefaultTableModel issuesModel = BrutalistStyle.nonEditableTableModel();
    private final JTable issuesTable = BrutalistStyle.tableWithTooltips(issuesModel);
    private final DefaultTableModel historyModel = BrutalistStyle.nonEditableTableModel();
    private final JTable historyTable = BrutalistStyle.tableWithTooltips(historyModel);

    private ShiftHandover shown;
    private List<HandoverIssue> issueRows = List.of();
    private List<ShiftHandover> historyRows = List.of();
    private boolean pinnedToASpecificShift;

    public HandoverView(AppContext ctx) {
        super(new BorderLayout(0, 10));
        this.ctx = ctx;
        this.service = ctx.getShiftHandoverService();
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        var slot = ShiftHandoverService.currentSlot(BusinessClock.now());
        dayField.setText(slot.workDay().toString());
        for (ShiftCode code : ShiftCode.values()) {
            if (code.working()) {
                shiftBox.addItem(code.name());
            }
        }
        shiftBox.setSelectedItem(slot.shift().name());

        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        bar.setOpaque(false);
        bar.add(dayField);
        bar.add(shiftBox);
        bar.add(showBtn);
        bar.add(latestBtn);
        bar.add(writeBtn);
        bar.add(acknowledgeBtn);
        bar.add(addIssueBtn);
        bar.add(resolveBtn);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(bar, BorderLayout.NORTH);
        header.add(feedbackLabel, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        document.setEditable(false);
        document.setLineWrap(true);
        document.setWrapStyleWord(true);
        document.setOpaque(false);

        JPanel tables = new JPanel(new GridLayout(1, 2, 8, 0));
        tables.setOpaque(false);
        tables.add(titled("handover.issues", issuesTable));
        tables.add(titled("handover.history", historyTable));

        JPanel centre = new JPanel(new GridLayout(2, 1, 0, 8));
        centre.setOpaque(false);
        JPanel sheet = new JPanel(new BorderLayout(0, 4));
        sheet.setOpaque(false);
        JLabel sheetTitle = new JLabel();
        sheetTitle.setName("handover.sheet");
        sheetTitle.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        sheet.add(sheetTitle, BorderLayout.NORTH);
        sheet.add(new JScrollPane(document), BorderLayout.CENTER);
        centre.add(sheet);
        centre.add(tables);
        add(centre, BorderLayout.CENTER);

        summaryLabel.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        add(summaryLabel, BorderLayout.SOUTH);

        showBtn.addActionListener(e -> guarded(() -> pinnedToASpecificShift = true));
        latestBtn.addActionListener(e -> guarded(() -> pinnedToASpecificShift = false));
        writeBtn.addActionListener(e -> guarded(this::write));
        acknowledgeBtn.addActionListener(e -> guarded(this::acknowledge));
        addIssueBtn.addActionListener(e -> guarded(this::addIssue));
        resolveBtn.addActionListener(e -> guarded(this::resolve));

        applyTranslations();
        applyTheme();
    }

    private JPanel titled(String key, JTable table) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel title = new JLabel(t(key));
        title.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        title.setName(key);
        panel.add(title, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private void guarded(Runnable action) {
        try {
            action.run();
            feedbackLabel.setText(" ");
            refreshData();
        } catch (RuntimeException ex) {
            feedbackLabel.setText(t("common.warning") + " — " + ex.getMessage());
        }
    }

    private String t(String key) {
        return ctx.getI18n().t(key);
    }

    // ---------------------------------------------------------------- actions

    /** Writes the shift up: four fields, the one thing that matters, and whatever is being left behind. */
    private void write() {
        LocalDate day = LocalDate.parse(dayField.getText().strip());
        ShiftCode shift = ShiftCode.valueOf((String) shiftBox.getSelectedItem());
        var figures = service.figuresFor(new ShiftHandoverService.ShiftSlot(day, shift));

        var equipment = area();
        var staffing = area();
        var safety = area();
        var mustKnow = new JTextField(30);
        var issue = new JTextField(30);
        var owners = owners();
        var due = new JTextField(day.plusDays(1).toString(), 10);
        var measured = new JLabel(t("handover.processed") + ": "
                + hu.due.rewear.util.FormatUtil.stripZeros(
                        java.math.BigDecimal.valueOf(figures.processedGrams(), 3)) + " kg · "
                + t("handover.downtime") + ": " + figures.downtimeMinutes() + " " + t("downtime.min"));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        BrutalistStyle.addFormRow(form, gbc, 0, t("handover.measured"), measured);
        BrutalistStyle.addFormRow(form, gbc, 1, t("handover.must_know"), mustKnow);
        BrutalistStyle.addFormRow(form, gbc, 2, t("handover.equipment"), new JScrollPane(equipment));
        BrutalistStyle.addFormRow(form, gbc, 3, t("handover.staffing"), new JScrollPane(staffing));
        BrutalistStyle.addFormRow(form, gbc, 4, t("handover.safety"), new JScrollPane(safety));
        BrutalistStyle.addFormRow(form, gbc, 5, t("handover.issue"), issue);
        BrutalistStyle.addFormRow(form, gbc, 6, t("handover.owner"), owners);
        BrutalistStyle.addFormRow(form, gbc, 7, t("handover.due"), due);
        if (JOptionPane.showConfirmDialog(this, form, t("handover.write"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        List<NewIssue> issues = issue.getText().isBlank() ? List.of()
                : List.of(new NewIssue(issue.getText(), selectedOwner(owners),
                        LocalDate.parse(due.getText().strip())));
        service.submit(new NewHandover(day, shift, equipment.getText(), staffing.getText(), safety.getText(),
                mustKnow.getText(), issues));
        pinnedToASpecificShift = false;
    }

    private void acknowledge() {
        if (shown == null) {
            throw new IllegalStateException(t("handover.none"));
        }
        service.acknowledge(shown.id());
    }

    private void addIssue() {
        if (shown == null) {
            throw new IllegalStateException(t("handover.none"));
        }
        var summary = new JTextField(30);
        var owners = owners();
        var due = new JTextField(shown.workDay().plusDays(1).toString(), 10);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        BrutalistStyle.addFormRow(form, gbc, 0, t("handover.issue"), summary);
        BrutalistStyle.addFormRow(form, gbc, 1, t("handover.owner"), owners);
        BrutalistStyle.addFormRow(form, gbc, 2, t("handover.due"), due);
        if (JOptionPane.showConfirmDialog(this, form, t("handover.add_issue"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            service.addIssue(shown.id(), new NewIssue(summary.getText(), selectedOwner(owners),
                    LocalDate.parse(due.getText().strip())));
        }
    }

    private void resolve() {
        int row = TableSorting.selectedModelRow(issuesTable);
        if (row < 0 || row >= issueRows.size()) {
            throw new IllegalStateException(t("handover.select_issue"));
        }
        HandoverIssue issue = issueRows.get(row);
        var what = new JTextField(30);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        BrutalistStyle.addFormRow(form, gbc, 0, "", new JLabel(issue.summary()));
        BrutalistStyle.addFormRow(form, gbc, 1, t("handover.resolution"), what);
        if (JOptionPane.showConfirmDialog(this, form, t("handover.resolve"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            service.resolveIssue(issue.id(), what.getText());
        }
    }

    private static JTextArea area() {
        JTextArea field = new JTextArea(3, 30);
        field.setLineWrap(true);
        field.setWrapStyleWord(true);
        return field;
    }

    private JComboBox<String> owners() {
        JComboBox<String> box = new JComboBox<>();
        for (User user : activeUsers()) {
            box.addItem(user.displayName());
        }
        return box;
    }

    private List<User> activeUsers() {
        return ctx.getUserDao().findAll().stream().filter(User::active).toList();
    }

    private long selectedOwner(JComboBox<String> owners) {
        int index = owners.getSelectedIndex();
        List<User> candidates = activeUsers();
        if (index < 0 || index >= candidates.size()) {
            throw new IllegalArgumentException(t("handover.owner_required"));
        }
        return candidates.get(index).id();
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void refreshData() {
        // Anything that has sat for two days goes up a tier the moment this screen is read, whether or not
        // anybody remembers to ask for it.
        service.escalateStaleIssues();
        if (pinnedToASpecificShift) {
            try {
                shown = service.find(LocalDate.parse(dayField.getText().strip()),
                        ShiftCode.valueOf((String) shiftBox.getSelectedItem())).orElse(null);
            } catch (RuntimeException ex) {
                shown = null;
            }
        } else {
            shown = service.latest().orElse(null);
            if (shown != null) {
                dayField.setText(shown.workDay().toString());
                shiftBox.setSelectedItem(shown.shift().name());
            }
        }
        document.setText(sheetText());
        document.setCaretPosition(0);

        issueRows = service.openIssues();
        var now = BusinessClock.now();
        var today = BusinessClock.today();
        issuesModel.setRowCount(0);
        for (HandoverIssue issue : issueRows) {
            issuesModel.addRow(new Object[]{issue.workDay().toString(), issue.summary(), issue.ownerName(),
                    issue.dueOn().toString(), issue.hoursOpen(now),
                    issue.escalated() ? t("handover.escalated")
                            : issue.overdue(today) ? t("handover.overdue") : t("handover.open")});
        }

        historyRows = service.recent(20);
        historyModel.setRowCount(0);
        for (ShiftHandover row : historyRows) {
            historyModel.addRow(new Object[]{row.workDay().toString(), t("rota.shift." + row.shift().name()),
                    row.outgoingByName(),
                    hu.due.rewear.util.FormatUtil.stripZeros(row.processedKg()) + " / "
                            + hu.due.rewear.util.FormatUtil.stripZeros(row.targetKg()) + " kg",
                    row.downtimeMinutes() + " " + t("downtime.min"),
                    row.acknowledged() ? row.acknowledgedByName() : t("handover.not_picked_up")});
        }

        summaryLabel.setText(shown == null ? t("handover.none")
                : t("handover.open_issues") + ": " + issueRows.size() + " · "
                  + t("handover.escalated") + ": "
                  + issueRows.stream().filter(HandoverIssue::escalated).count() + " · "
                  + t("handover.overdue") + ": "
                  + issueRows.stream().filter(issue -> issue.overdue(today)).count());
        acknowledgeBtn.setEnabled(shown != null && !shown.acknowledged());
        addIssueBtn.setEnabled(shown != null);
    }

    /** The sheet itself, as a supervisor would read it: the one thing that matters at the top. */
    private String sheetText() {
        if (shown == null) {
            return t("handover.none_for_shift");
        }
        String language = ctx.getI18n().getActiveLanguage();
        StringBuilder text = new StringBuilder();
        text.append(shown.workDay()).append(" · ").append(t("rota.shift." + shown.shift().name()))
                .append(" · ").append(shown.outgoingByName())
                .append(" · ").append(shown.submittedAt().format(STAMP)).append('\n');
        text.append(shown.acknowledged()
                ? t("handover.picked_up_by") + ": " + shown.acknowledgedByName()
                  + " · " + shown.acknowledgedAt().format(STAMP)
                : t("handover.not_picked_up")).append("\n\n");
        text.append(t("handover.must_know")).append(":\n").append(shown.mustKnow()).append("\n\n");
        text.append(t("handover.measured")).append(":\n")
                .append(t("handover.processed")).append(": ")
                .append(hu.due.rewear.util.FormatUtil.stripZeros(shown.processedKg())).append(" kg / ")
                .append(hu.due.rewear.util.FormatUtil.stripZeros(shown.targetKg())).append(" kg · ")
                .append(t("handover.graded")).append(": ")
                .append(hu.due.rewear.util.FormatUtil.stripZeros(shown.outputKg())).append(" kg · ")
                .append(t("handover.downtime")).append(": ").append(shown.downtimeMinutes()).append(' ')
                .append(t("downtime.min"));
        if (shown.primaryCause(language) != null) {
            text.append(" · ").append(t("handover.primary_cause")).append(": ")
                    .append(shown.primaryCause(language));
        }
        text.append("\n\n");
        text.append(t("handover.equipment")).append(":\n").append(shown.equipmentStatus()).append("\n\n");
        text.append(t("handover.staffing")).append(":\n").append(shown.staffingNotes()).append("\n\n");
        text.append(t("handover.safety")).append(":\n").append(shown.safetyItems()).append("\n\n");
        text.append(t("handover.issues")).append(":\n");
        if (shown.issues().isEmpty()) {
            text.append(t("handover.no_issues")).append('\n');
        }
        for (HandoverIssue issue : shown.issues()) {
            text.append("· ").append(issue.summary()).append(" — ").append(issue.ownerName())
                    .append(" · ").append(issue.dueOn())
                    .append(issue.open() ? "" : " · " + t("handover.resolved"))
                    .append(issue.escalated() ? " · " + t("handover.escalated") : "")
                    .append('\n');
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- view plumbing

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_HANDOVER;
    }

    @Override
    public String getTitleI18nKey() {
        return "nav.handover";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void applyTranslations() {
        showBtn.setText(t("handover.show"));
        latestBtn.setText(t("handover.last_shift"));
        writeBtn.setText(t("handover.write"));
        acknowledgeBtn.setText(t("handover.pick_up"));
        addIssueBtn.setText(t("handover.add_issue"));
        resolveBtn.setText(t("handover.resolve"));
        dayField.getAccessibleContext().setAccessibleName(t("sorting.work_day"));
        shiftBox.getAccessibleContext().setAccessibleName(t("rota.shift"));
        issuesModel.setColumnIdentifiers(new Object[]{t("sorting.work_day"), t("handover.issue"),
                t("handover.owner"), t("handover.due"), t("handover.hours_open"), t("common.status")});
        historyModel.setColumnIdentifiers(new Object[]{t("sorting.work_day"), t("rota.shift"),
                t("handover.outgoing"), t("handover.processed"), t("handover.downtime"),
                t("handover.picked_up_by")});
        for (JComponent component : List.of(this)) {
            retitle(component);
        }
        refreshData();
    }

    /** The titles above the two tables and the sheet carry their key in the component name. */
    private void retitle(JComponent root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getName() != null) {
                label.setText(t(label.getName()));
            }
            if (child instanceof JComponent component) {
                retitle(component);
            }
        }
    }

    @Override
    public void applyTheme() {
        document.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
        BrutalistStyle.styleTable(issuesTable);
        BrutalistStyle.styleTable(historyTable);
        BrutalistStyle.stylePrimaryButton(latestBtn);
        BrutalistStyle.styleSecondaryButtons(showBtn, writeBtn, acknowledgeBtn, addIssueBtn, resolveBtn);
    }
}
