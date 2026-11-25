package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.service.PayrollService;
import hu.due.rewear.service.PayrollService.Department;
import hu.due.rewear.service.PayrollService.Performance;
import hu.due.rewear.service.PayrollService.Rate;
import hu.due.rewear.service.PayrollService.Shift;
import hu.due.rewear.service.PayrollService.Worker;
import hu.due.rewear.service.PayrollService.Workspace;
import hu.due.rewear.service.SortingService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

final class PayrollView extends JPanel implements NavigableView {
    private final AppContext ctx;
    private final PayrollService service;
    private final SectionNavigation sections;
    private final JTextField dayField = new JTextField(10);
    private final JLabel dateLabel = new JLabel();
    private final JLabel draft = new JLabel();
    private final Grid<Worker> workers = new Grid<>();
    private final Grid<Department> departments = new Grid<>();
    private final Grid<Rate> rates = new Grid<>();
    private final Grid<Shift> shifts = new Grid<>();
    private final Grid<Performance> performance = new Grid<>();
    private final List<Action> actions = new ArrayList<>();
    private LocalDate day;
    private Workspace state;

    PayrollView(AppContext ctx) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        service = ctx.getPayrollService();
        sections = new SectionNavigation(ctx.getI18n());
        day = service.today();
        dayField.setText(day.toString());
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        var top = toolbar();
        top.add(dateLabel);
        top.add(dayField);
        addAction(top, "payroll.show_day", false, () -> day = readDate(dayField));
        top.add(draft);
        add(top, BorderLayout.NORTH);
        add(sections, BorderLayout.CENTER);
        var workerBar = toolbar();
        addAction(workerBar, "payroll.edit_monthly", true, this::editMonthly);
        section("payroll.workers", MainFrame.VIEW_CUSTOMERS, workers, workerBar);
        var departmentBar = toolbar();
        addAction(departmentBar, "payroll.add_department", true, this::addDepartment);
        section("payroll.departments", MainFrame.VIEW_LOCATIONS, departments, departmentBar);
        var rateBar = toolbar();
        addAction(rateBar, "payroll.add_rate", true, () -> editRate(null));
        addAction(rateBar, "common.edit", false, () -> {
            Rate rate = rates.selected();
            if (rate != null) editRate(rate);
        });
        section("payroll.rates", MainFrame.VIEW_INVOICES, rates, rateBar);
        var shiftBar = toolbar();
        addAction(shiftBar, "payroll.add_shift", true, () -> editShift(null));
        addAction(shiftBar, "common.edit", false, () -> {
            Shift shift = shifts.selected();
            if (shift != null) editShift(shift);
        });
        addAction(shiftBar, "common.delete", false, () -> {
            Shift shift = shifts.selected();
            if (shift != null && JOptionPane.showConfirmDialog(this, t("payroll.shift_delete"),
                    t("common.confirm"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) service.deleteShift(shift.id());
        });
        section("payroll.shifts", MainFrame.VIEW_TODO, shifts, shiftBar);
        section("payroll.performance", MainFrame.VIEW_SORTING, performance, toolbar());
        applyTranslations();
        applyTheme();
    }

    private static JPanel toolbar() { return new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4)); }

    private void section(String key, String icon, Grid<?> grid, JPanel controls) {
        var panel = new JPanel(new BorderLayout(0, 5));
        if (controls.getComponentCount() > 0) panel.add(controls, BorderLayout.NORTH);
        panel.add(new JScrollPane(grid.table), BorderLayout.CENTER);
        sections.addSection(key, SidebarIcons.forView(icon, 16), panel);
    }

    private void addAction(JPanel target, String key, boolean primary, Runnable run) {
        var button = new JButton();
        actions.add(new Action(button, key, primary));
        target.add(button);
        button.addActionListener(event -> {
            try {
                run.run();
                refreshData();
            } catch (RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void editMonthly() {
        Worker worker = workers.selected();
        if (worker == null) return;
        var amount = new JTextField(worker.monthlyGross() == null ? "" : worker.monthlyGross().toString(), 14);
        var effective = new JTextField(day.toString(), 11);
        if (form("payroll.edit_monthly", "payroll.worker", new JLabel(worker.name()),
                "payroll.monthly_gross", amount, "payroll.effective", effective)) {
            service.saveMonthlyGross(worker.id(), readDate(effective), readHuf(amount));
        }
    }

    private void addDepartment() {
        var name = new JTextField(20);
        var link = new JComboBox<Option<Long>>();
        link.addItem(new Option<>(null, "—"));
        state.unlinkedTeams().forEach(team -> link.addItem(new Option<>(team.id(), team.name())));
        if (form("payroll.add_department", "payroll.department", name, "sorting.team", link)) {
            service.addDepartment(name.getText(), choice(link));
        }
    }

    private void editRate(Rate rate) {
        var department = departmentChoices(rate == null ? null : rate.departmentId());
        var role = roleChoices(rate == null ? null : rate.role());
        var amount = new JTextField(rate == null ? "" : Long.toString(rate.dailyGross()), 14);
        var bonus = new JTextField(rate == null ? "0" : percentValue(rate.bonusPoints()), 8);
        var effective = new JTextField(day.toString(), 11);
        if (form("payroll.add_rate", "payroll.department", department, "payroll.duty", role,
                "payroll.daily_rate", amount, "payroll.bonus_rate", bonus, "payroll.effective", effective)) {
            service.saveRate(choice(department), choice(role), readDate(effective), readHuf(amount), readDecimal(bonus));
        }
    }

    private void editShift(Shift shift) {
        var worker = new JComboBox<Option<Long>>();
        state.workers().stream().filter(w -> w.active() || (shift != null && shift.workerId() == w.id()))
                .forEach(w -> worker.addItem(new Option<>(w.id(), w.name())));
        var department = departmentChoices(shift == null ? null : shift.departmentId());
        var role = roleChoices(shift == null ? null : shift.role());
        if (shift != null) select(worker, shift.workerId());
        else {
            Runnable syncRole = () -> {
                if (worker.getSelectedIndex() < 0) return;
                long id = choice(worker);
                state.workers().stream().filter(w -> w.id() == id).findFirst().ifPresent(w -> select(role, w.role()));
            };
            worker.addActionListener(event -> syncRole.run());
            syncRole.run();
        }
        var share = new JTextField(shift == null ? "100" : percentValue(shift.shiftPoints()), 8);
        var approve = new JCheckBox(t("payroll.bonus_approved"), shift != null && shift.bonusApproved());
        if (form("payroll.add_shift", "sorting.work_day", new JLabel(day.toString()), "payroll.worker", worker,
                "payroll.department", department, "payroll.duty", role, "payroll.shift_share", share, "", approve)) {
            service.saveShift(shift == null ? 0 : shift.id(), choice(worker), day, choice(department),
                    choice(role), readDecimal(share), approve.isSelected());
        }
    }

    private JComboBox<Option<Long>> departmentChoices(Long selected) {
        var box = new JComboBox<Option<Long>>();
        state.departments().forEach(department -> box.addItem(new Option<>(department.id(), department.name())));
        if (selected != null) select(box, selected);
        return box;
    }

    private JComboBox<Option<RoleCode>> roleChoices(RoleCode selected) {
        var box = new JComboBox<Option<RoleCode>>();
        for (RoleCode role : RoleCode.values()) box.addItem(new Option<>(role, roleName(role)));
        if (selected != null) select(box, selected);
        return box;
    }

    private boolean form(String title, Object... rows) {
        var panel = new JPanel(new GridBagLayout());
        var layout = new GridBagConstraints();
        layout.insets = new Insets(5, 5, 5, 5);
        layout.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < rows.length; i += 2) {
            String label = (String) rows[i];
            BrutalistStyle.addFormRow(panel, layout, i / 2, label.isEmpty() ? "" : t(label), (JComponent) rows[i+1]);
        }
        return JOptionPane.showConfirmDialog(this, panel, t(title), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
    }

    @Override public void refreshData() {
        state = service.workspace(day);
        var shiftTotals = new java.util.HashMap<Long, Long>();
        var incomplete = new java.util.HashSet<Long>();
        for (Shift shift : state.shifts()) {
            Long amount = shift.draftGross();
            if (amount == null) incomplete.add(shift.workerId());
            else shiftTotals.merge(shift.workerId(), amount, Math::addExact);
        }
        workers.set(state.workers(), w -> new Object[]{w.name(), roleName(w.role()), w.active() ? t("common.yes") : t("common.no"),
                money(w.monthlyGross()), w.effectiveFrom(), money(incomplete.contains(w.id()) ? null : shiftTotals.get(w.id()))});
        departments.set(state.departments(), d -> new Object[]{d.name(), d.sortingTeam()});
        rates.set(state.rates(), r -> new Object[]{r.department(), roleName(r.role()), r.effectiveFrom(),
                money(r.dailyGross()), percent(r.bonusPoints())});
        shifts.set(state.shifts(), s -> new Object[]{s.worker(), s.department(), roleName(s.role()), percent(s.shiftPoints()),
                money(s.baseGross()), s.bonusApproved() ? t("common.yes") : t("common.no"),
                money(s.bonusGross()), money(s.draftGross())});
        performance.set(state.performance(), p -> new Object[]{p.worker(), p.department(), roleName(p.role()),
                p.role() == RoleCode.SORTER ? kg(p.sortedGrams()) : "—", p.targetGrams() == null ? "—" : kg(p.targetGrams()),
                p.role() == RoleCode.SORTING_HELPER ? p.bags() : null,
                p.role() == RoleCode.SORTING_HELPER ? kg(p.packedGrams()) : "—"});
    }

    @Override public void applyTranslations() {
        sections.applyTranslations();
        dateLabel.setText(t("sorting.work_day"));
        draft.setText(t("payroll.draft_hint"));
        actions.forEach(a -> a.button().setText(t(a.key())));
        columns(workers, "payroll.worker", "payroll.duty", "payroll.active", "payroll.monthly_gross", "payroll.effective", "payroll.draft_gross");
        columns(departments, "payroll.department", "sorting.team");
        columns(rates, "payroll.department", "payroll.duty", "payroll.effective", "payroll.daily_rate", "payroll.bonus_rate");
        columns(shifts, "payroll.worker", "payroll.department", "payroll.duty", "payroll.shift_share",
                "payroll.shift_gross", "payroll.bonus_status", "payroll.bonus_gross", "payroll.draft_gross");
        columns(performance, "payroll.worker", "payroll.department", "payroll.duty", "sorting.processed_kg",
                "sorting.target_kg", "payroll.packed_bags", "payroll.packed_kg");
        refreshData();
    }

    private void columns(Grid<?> grid, String... keys) {
        grid.model.setColumnIdentifiers(Arrays.stream(keys).map(this::t).toArray());
    }

    @Override public void applyTheme() {
        sections.applyTheme();
        draft.setForeground(hu.due.rewear.config.ThemeManager.muted());
        for (var grid : List.of(workers, departments, rates, shifts, performance)) BrutalistStyle.styleTable(grid.table);
        actions.forEach(a -> {
            if (a.primary()) BrutalistStyle.stylePrimaryButton(a.button());
            else BrutalistStyle.styleSecondaryButton(a.button());
        });
    }

    @Override public String getViewKey() { return MainFrame.VIEW_PAYROLL; }
    @Override public String getTitleI18nKey() { return "nav.payroll"; }
    @Override public JComponent getComponent() { return this; }

    private String t(String key) { return ctx.getI18n().t(key); }
    private String roleName(RoleCode role) { return t("role." + role.name()); }
    private static String percent(int points) { return percentValue(points) + "%"; }
    private static String percentValue(int points) { return BigDecimal.valueOf(points, 2).stripTrailingZeros().toPlainString(); }
    private static String money(Long amount) { return amount == null ? "—" : amount + " HUF"; }
    private static String kg(long grams) { return SortingService.kg(grams).toPlainString(); }
    private static LocalDate readDate(JTextField input) { return LocalDate.parse(input.getText().strip()); }
    private static long readHuf(JTextField input) { return Long.parseLong(input.getText().strip().replace(" ", "")); }
    private static BigDecimal readDecimal(JTextField input) { return new BigDecimal(input.getText().strip().replace(',', '.')); }

    private static <T> void select(JComboBox<Option<T>> box, T value) {
        for (int i = 0; i < box.getItemCount(); i++) {
            if (box.getItemAt(i).value().equals(value)) { box.setSelectedIndex(i); return; }
        }
    }

    private static <T> T choice(JComboBox<Option<T>> box) {
        if (box.getSelectedIndex() < 0) throw new IllegalArgumentException("Select an available option.");
        return box.getItemAt(box.getSelectedIndex()).value();
    }

    private record Action(JButton button, String key, boolean primary) { }
    private record Option<T>(T value, String label) { @Override public String toString() { return label; } }

    private static final class Grid<T> {
        private final DefaultTableModel model = BrutalistStyle.nonEditableTableModel();
        private final JTable table = new JTable(model);
        private List<T> rows = List.of();

        Grid() { table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); }

        void set(List<T> values, Function<T,Object[]> cells) {
            T previous = selected();
            rows = values;
            model.setRowCount(0);
            rows.forEach(row -> model.addRow(cells.apply(row)));
            int index = previous == null ? -1 : rows.indexOf(previous);
            if (index >= 0) TableSorting.selectModelRow(table, index);
        }

        T selected() {
            int row = TableSorting.selectedModelRow(table);
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
        }
    }
}
