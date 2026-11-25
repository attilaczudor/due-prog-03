package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.RotaShift;
import hu.due.rewear.model.RotaShift.ShiftCode;
import hu.due.rewear.model.User;
import hu.due.rewear.service.RotaService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * The shift rota as a calendar: a month grid, a week, or a single day.
 * <p>
 * Everyone can open it. Someone who plans shifts sees the whole company and can click a day to plan it;
 * everyone else sees their own days, read only.
 */
public final class RotaView extends JPanel implements NavigableView {

    private enum Scale { MONTH, WEEK, DAY }

    private final AppContext ctx;
    private final RotaService rota;

    private final JPanel canvas = new JPanel(new BorderLayout());
    private final JLabel periodLabel = new JLabel("", SwingConstants.LEFT);
    private final JLabel summaryLabel = new JLabel();
    private final JButton monthBtn = new JButton();
    private final JButton weekBtn = new JButton();
    private final JButton dayBtn = new JButton();
    private final JButton prevBtn = new JButton("<");
    private final JButton nextBtn = new JButton(">");
    private final JButton todayBtn = new JButton();
    private final JButton planBtn = new JButton();

    private Scale scale = Scale.MONTH;
    private LocalDate anchor;

    public RotaView(AppContext ctx) {
        super(new BorderLayout(0, 10));
        this.ctx = ctx;
        this.rota = ctx.getRotaService();
        this.anchor = rota.today();
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JPanel bar = new JPanel(new WrapLayout(java.awt.FlowLayout.LEFT, 6, 4));
        bar.setOpaque(false);
        bar.add(prevBtn);
        bar.add(todayBtn);
        bar.add(nextBtn);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(monthBtn);
        bar.add(weekBtn);
        bar.add(dayBtn);
        if (rota.mayPlan()) {
            bar.add(Box.createHorizontalStrut(10));
            bar.add(planBtn);
        }
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.setOpaque(false);
        periodLabel.setFont(ThemeManager.monoFont(Font.BOLD, 15f));
        header.add(periodLabel, BorderLayout.NORTH);
        header.add(bar, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        summaryLabel.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        add(summaryLabel, BorderLayout.SOUTH);

        prevBtn.addActionListener(e -> step(-1));
        nextBtn.addActionListener(e -> step(1));
        todayBtn.addActionListener(e -> {
            anchor = rota.today();
            refreshData();
        });
        monthBtn.addActionListener(e -> setScale(Scale.MONTH));
        weekBtn.addActionListener(e -> setScale(Scale.WEEK));
        dayBtn.addActionListener(e -> setScale(Scale.DAY));
        planBtn.addActionListener(e -> planFor(anchor));

        applyTranslations();
        applyTheme();
    }

    private void setScale(Scale next) {
        scale = next;
        refreshData();
    }

    private void step(int direction) {
        anchor = switch (scale) {
            case MONTH -> anchor.plusMonths(direction);
            case WEEK -> anchor.plusWeeks(direction);
            case DAY -> anchor.plusDays(direction);
        };
        refreshData();
    }

    // ---- drawing ----------------------------------------------------------------------------

    @Override public void refreshData() {
        canvas.removeAll();
        switch (scale) {
            case MONTH -> drawMonth();
            case WEEK -> drawWeek();
            case DAY -> drawDay();
        }
        canvas.revalidate();
        canvas.repaint();
    }

    private void drawMonth() {
        YearMonth month = YearMonth.from(anchor);
        List<RotaService.Day> days = rota.month(month);
        periodLabel.setText(month.getYear() + ". " + monthName(month) + " — " + t("rota.scale.month"));

        JPanel grid = new JPanel(new GridLayout(0, 7, 4, 4));
        grid.setOpaque(false);
        for (DayOfWeek dow : DayOfWeek.values()) {
            JLabel head = new JLabel(dow.getDisplayName(TextStyle.SHORT, locale()).toUpperCase(locale()), SwingConstants.CENTER);
            head.setFont(ThemeManager.monoFont(Font.BOLD, 11f));
            head.setForeground(ThemeManager.muted());
            grid.add(head);
        }
        int working = 0;
        for (RotaService.Day day : days) {
            grid.add(monthCell(day, month));
            working += day.working();
        }
        canvas.add(new JScrollPane(grid), BorderLayout.CENTER);
        summaryLabel.setText(t("rota.summary.month", working));
    }

    private JComponent monthCell(RotaService.Day day, YearMonth month) {
        boolean inMonth = YearMonth.from(day.date()).equals(month);
        boolean isToday = day.date().equals(rota.today());
        JPanel cell = new JPanel(new BorderLayout(0, 2));
        cell.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(isToday ? ThemeManager.fg() : ThemeManager.border(), isToday ? 2 : 1),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        cell.setBackground(inMonth ? ThemeManager.bg() : ThemeManager.surface());

        JLabel number = new JLabel(String.valueOf(day.date().getDayOfMonth()));
        number.setFont(ThemeManager.monoFont(isToday ? Font.BOLD : Font.PLAIN, 12f));
        number.setForeground(inMonth ? ThemeManager.fg() : ThemeManager.muted());
        cell.add(number, BorderLayout.NORTH);

        JPanel shifts = new JPanel();
        shifts.setLayout(new BoxLayout(shifts, BoxLayout.Y_AXIS));
        shifts.setOpaque(false);
        for (ShiftCode code : ShiftCode.values()) {
            int count = day.inShift(code).size();
            if (count == 0) {
                continue;
            }
            JLabel line = new JLabel(shiftTag(code) + " " + count);
            line.setFont(ThemeManager.monoFont(Font.PLAIN, 10f));
            line.setForeground(code == ShiftCode.OFF ? ThemeManager.muted() : ThemeManager.fg());
            shifts.add(line);
        }
        cell.add(shifts, BorderLayout.CENTER);
        openOnClick(cell, day.date());
        return cell;
    }

    private void drawWeek() {
        List<RotaService.Day> days = rota.week(anchor);
        periodLabel.setText(days.get(0).date() + " – " + days.get(6).date() + " — " + t("rota.scale.week"));

        JPanel grid = new JPanel(new GridLayout(1, 7, 4, 4));
        grid.setOpaque(false);
        int working = 0;
        for (RotaService.Day day : days) {
            JPanel column = new JPanel(new BorderLayout(0, 4));
            column.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(day.date().equals(rota.today()) ? ThemeManager.fg() : ThemeManager.border(),
                            day.date().equals(rota.today()) ? 2 : 1),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            column.setBackground(ThemeManager.bg());
            JLabel head = new JLabel(day.date().getDayOfMonth() + " "
                    + day.date().getDayOfWeek().getDisplayName(TextStyle.SHORT, locale()));
            head.setFont(ThemeManager.monoFont(Font.BOLD, 11f));
            column.add(head, BorderLayout.NORTH);

            JPanel people = new JPanel();
            people.setLayout(new BoxLayout(people, BoxLayout.Y_AXIS));
            people.setOpaque(false);
            for (RotaShift shift : day.shifts()) {
                JLabel line = new JLabel(shiftTag(shift.shift()) + " " + shift.workerName());
                line.setFont(ThemeManager.monoFont(Font.PLAIN, 10f));
                line.setForeground(shift.working() ? ThemeManager.fg() : ThemeManager.muted());
                line.setToolTipText(shift.workerName() + " · " + t("role." + shift.roleCode().name())
                        + (shift.working() ? " · " + shift.timeRange() : ""));
                people.add(line);
            }
            column.add(new JScrollPane(people), BorderLayout.CENTER);
            openOnClick(column, day.date());
            grid.add(column);
            working += day.working();
        }
        canvas.add(grid, BorderLayout.CENTER);
        summaryLabel.setText(t("rota.summary.week", working));
    }

    private void drawDay() {
        RotaService.Day day = rota.day(anchor);
        periodLabel.setText(day.date() + " " + day.date().getDayOfWeek().getDisplayName(TextStyle.FULL, locale())
                + " — " + t("rota.scale.day"));

        JPanel columns = new JPanel(new GridLayout(1, 0, 6, 0));
        columns.setOpaque(false);
        for (ShiftCode code : ShiftCode.values()) {
            JPanel box = new JPanel(new BorderLayout(0, 4));
            box.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ThemeManager.border()),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            box.setBackground(ThemeManager.bg());
            List<RotaShift> inShift = day.inShift(code);
            JLabel head = new JLabel(t("rota.shift." + code.name()) + "  (" + inShift.size() + ")");
            head.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
            box.add(head, BorderLayout.NORTH);

            JPanel people = new JPanel();
            people.setLayout(new BoxLayout(people, BoxLayout.Y_AXIS));
            people.setOpaque(false);
            for (RotaShift shift : inShift) {
                String time = shift.working() ? "  " + shift.timeRange() : "";
                JLabel line = new JLabel(shift.workerName() + time);
                line.setFont(ThemeManager.monoFont(Font.PLAIN, 11f));
                JLabel role = new JLabel("   " + t("role." + shift.roleCode().name())
                        + (shift.note() == null ? "" : " · " + shift.note()));
                role.setFont(ThemeManager.monoFont(Font.PLAIN, 10f));
                role.setForeground(ThemeManager.muted());
                people.add(line);
                people.add(role);
                people.add(Box.createVerticalStrut(4));
            }
            box.add(new JScrollPane(people), BorderLayout.CENTER);
            columns.add(box);
        }
        canvas.add(columns, BorderLayout.CENTER);
        summaryLabel.setText(t("rota.summary.day", day.working(), day.off()));
    }

    private void openOnClick(JComponent cell, LocalDate date) {
        cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cell.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                anchor = date;
                if (e.getClickCount() >= 2 && rota.mayPlan()) {
                    planFor(date);
                } else {
                    setScale(Scale.DAY);
                }
            }
        });
    }

    // ---- planning ---------------------------------------------------------------------------

    private void planFor(LocalDate date) {
        I18nManager i18n = ctx.getI18n();
        List<User> workers;
        try {
            workers = rota.plannableWorkers();
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), t("common.error"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        JComboBox<Choice> worker = new JComboBox<>();
        for (User user : workers) {
            worker.addItem(new Choice(user.id(), user.displayName() + " — " + t("role." + user.roleCode().name())));
        }
        JComboBox<String> shift = new JComboBox<>();
        for (ShiftCode code : ShiftCode.values()) {
            shift.addItem(t("rota.shift." + code.name()));
        }
        JTextField day = new JTextField(date.toString(), 12);
        JTextField note = new JTextField(20);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        BrutalistStyle.addFormRow(form, gbc, 0, t("rota.worker"), worker);
        BrutalistStyle.addFormRow(form, gbc, 1, t("sorting.work_day"), day);
        BrutalistStyle.addFormRow(form, gbc, 2, t("rota.shift"), shift);
        BrutalistStyle.addFormRow(form, gbc, 3, t("invoice.history.note"), note);

        if (JOptionPane.showConfirmDialog(this, form, t("rota.plan"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            Choice chosen = (Choice) worker.getSelectedItem();
            if (chosen == null) {
                return;
            }
            rota.planShift(chosen.id(), LocalDate.parse(day.getText().strip()),
                    ShiftCode.values()[shift.getSelectedIndex()], note.getText());
            refreshData();
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private record Choice(long id, String label) {
        @Override public String toString() { return label; }
    }

    // ---- framework --------------------------------------------------------------------------

    private String shiftTag(ShiftCode code) {
        return switch (code) {
            case MORNING -> "[D]";
            case AFTERNOON -> "[A]";
            case NIGHT -> "[N]";
            case OFF -> "[–]";
        };
    }

    private Locale locale() {
        return "hu".equals(ctx.getI18n().getActiveLanguage()) ? new Locale("hu") : Locale.ENGLISH;
    }

    private String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, locale());
    }

    private String t(String key, Object... args) {
        return ctx.getI18n().t(key, args);
    }

    @Override public void applyTranslations() {
        monthBtn.setText(t("rota.scale.month"));
        weekBtn.setText(t("rota.scale.week"));
        dayBtn.setText(t("rota.scale.day"));
        todayBtn.setText(t("rota.today"));
        planBtn.setText(t("rota.plan"));
        refreshData();
    }

    @Override public void applyTheme() {
        canvas.setOpaque(false);
        setBackground(ThemeManager.bg());
        periodLabel.setForeground(ThemeManager.fg());
        summaryLabel.setForeground(ThemeManager.fg());
        BrutalistStyle.stylePrimaryButton(planBtn);
        BrutalistStyle.styleSecondaryButtons(monthBtn, weekBtn, dayBtn, prevBtn, nextBtn, todayBtn);
    }

    @Override public String getViewKey() { return MainFrame.VIEW_ROTA; }
    @Override public String getTitleI18nKey() { return "nav.rota"; }
    @Override public JComponent getComponent() { return this; }
}
