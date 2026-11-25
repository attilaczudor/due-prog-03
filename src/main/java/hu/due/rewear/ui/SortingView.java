package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import hu.due.rewear.service.LabelPreset;
import hu.due.rewear.service.SortingService;
import hu.due.rewear.service.SortingService.*;
import hu.due.rewear.util.BusinessClock;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class SortingView extends JPanel implements NavigableView {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(BusinessClock.ZONE);

    private final AppContext ctx;
    private final SortingService service;
    private final SectionNavigation sections;
    private final DataTable<Request> requests = new DataTable<>();
    private final DataTable<Bag> bags = new DataTable<>();
    private final DataTable<Lot> lots = new DataTable<>();
    private final DataTable<Team> teams = new DataTable<>();
    private final DataTable<Daily> daily = new DataTable<>();
    private final List<ActionButton> actions = new ArrayList<>();
    private final JTextField workDate = new JTextField(10);
    private LocalDate day;

    SortingView(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.service = ctx.getSortingService();
        this.sections = new SectionNavigation(ctx.getI18n());
        this.day = service.today();
        workDate.setText(day.toString());
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        add(sections);
        RoleCode role = ctx.getSession().requireCurrentUser().roleCode();
        // The production leaders run the sorting floor alongside management.
        boolean manages = role.runsProduction();
        var requestActions = controls();
        var bagActions = controls();
        if (role == RoleCode.SORTER) {
            addAction(requestActions, "sorting.request.report", false, this::reportBag);
            addAction(requestActions, "sorting.request.finish", false, this::finish);
            addAction(requestActions, "common.cancel", false, () -> {
                Request request = requests.selected();
                if (request != null && confirmation("sorting.request.cancel_confirm")) service.cancelRequest(request.id());
            });
        } else if (role == RoleCode.SUPPLY_DRIVER) {
            addAction(requestActions, "sorting.request.deliver", true, this::deliver);
        } else if (role == RoleCode.SORTING_HELPER) {
            addAction(bagActions, "sorting.bag.pack", true, this::packBag);
        } else if (role == RoleCode.OPERATOR || manages) {
            addAction(bagActions, "sorting.bag.register", true, this::registerBag);
            addAction(bagActions, "scanner.action.reprint", false, this::reprint);
        }

        // Raw materials come first: they are where the whole sorting flow starts.
        if (manages || role == RoleCode.SUPPLY_DRIVER || role == RoleCode.SORTER) {
            var rawActions = controls();
            if (role == RoleCode.SORTER) addAction(rawActions, "sorting.lot.call", true, this::requestSupply);
            if (manages) addAction(rawActions, "sorting.lot.receive", true, this::receive);
            if (manages || role == RoleCode.SUPPLY_DRIVER) addAction(rawActions, "sorting.lot.store", role == RoleCode.SUPPLY_DRIVER, this::storeLot);
            addSection("sorting.materials", MainFrame.VIEW_INVENTORY, lots, rawActions);
        }
        if (role == RoleCode.OPERATOR || role == RoleCode.SORTING_HELPER) {
            addSection("sorting.bags", MainFrame.VIEW_INTAKE, bags, bagActions);
            addSection("sorting.requests", MainFrame.VIEW_TODO, requests, requestActions);
        } else {
            addSection("sorting.requests", MainFrame.VIEW_TODO, requests, requestActions);
            addSection("sorting.bags", MainFrame.VIEW_INTAKE, bags, bagActions);
        }
        if (manages) {
            var teamActions = controls();
            addAction(teamActions, "sorting.team.new", true, () -> editTeam(null));
            addAction(teamActions, "common.edit", false, () -> {
                Team team = teams.selected();
                if (team != null) editTeam(team);
            });
            addSection("sorting.teams", MainFrame.VIEW_CUSTOMERS, teams, teamActions);
        }
        if (manages || role == RoleCode.SORTER || role == RoleCode.SORTING_HELPER) {
            var dayActions = controls();
            workDate.getAccessibleContext().setAccessibleName(t("sorting.work_day"));
            dayActions.add(workDate);
            addAction(dayActions, "sorting.show_day", false, () -> day = LocalDate.parse(workDate.getText().strip()));
            addSection("sorting.daily", MainFrame.VIEW_DASHBOARD, daily, dayActions);
        }
        applyTranslations();
        applyTheme();
    }

    private static JPanel controls() {
        var host = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        host.setOpaque(false);
        return host;
    }

    private void addAction(JPanel controls, String label, boolean primary, Runnable action) {
        var button = new JButton();
        actions.add(new ActionButton(button, label, primary));
        controls.add(button);
        button.addActionListener(event -> {
            try {
                action.run();
                refreshData();
            } catch (RuntimeException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void addSection(String label, String icon, DataTable<?> data, JPanel controls) {
        var panel = new JPanel(new BorderLayout(0, 6));
        if (controls.getComponentCount() > 0) panel.add(controls, BorderLayout.NORTH);
        panel.add(new JScrollPane(data.table), BorderLayout.CENTER);
        sections.addSection(label, SidebarIcons.forView(icon, 16), panel);
    }

    /** Calls for the selected raw material line. No weight is entered: the driver's delivery decides it. */
    private void requestSupply() {
        Lot lot = lots.selected();
        if (lot == null) {
            JOptionPane.showMessageDialog(this, t("sorting.lot.select_first"), t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        String where = lot.stored() ? lot.locationCode() : t("sorting.lot.awaiting_storage");
        String message = lot.material() + " · " + lot.sourceArea() + "\n" + lot.reference() + " · " + where + " · "
                + amount(lot.availableGrams()) + "\n\n" + t("sorting.lot.call_confirm");
        if (JOptionPane.showConfirmDialog(this, message, t("sorting.lot.call"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION) {
            service.requestSupply(lot.id());
        }
    }

    private void storeLot() {
        Lot lot = lots.selected();
        if (lot == null) {
            JOptionPane.showMessageDialog(this, t("sorting.lot.select_first"), t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        var location = choices(ctx.getLocationDao().findByType(LocationType.STORAGE, true).stream()
                .map(row -> new Choice<>(row.id(), row.code())).toList());
        if (lot.locationId() != null) select(location, lot.locationId());
        var info = new JLabel(lot.reference() + " · " + lot.material() + " · " + amount(lot.availableGrams()));
        if (form("sorting.lot.store", "", info, "sorting.lot.location", location)) {
            service.storeLot(lot.id(), selected(location));
        }
    }

    private void deliver() {
        Request request = requests.selected();
        if (request == null || !request.awaitingDelivery()) return;
        if (request.requestedLotId() != null) {
            // The sorter called for a line without a weight, so the driver just records what was actually brought.
            Lot called = lots.rows.stream().filter(row -> row.id() == request.requestedLotId()).findFirst().orElse(null);
            var actual = new JTextField(12);
            var info = new JLabel(called == null ? request.lot()
                    : called.material() + " · " + called.reference() + " · " + amount(called.availableGrams()));
            if (form("sorting.request.deliver", "sorting.lot", info, "sorting.delivered_kg", actual)) {
                service.deliver(request.id(), decimal(actual));
            }
            return;
        }
        var lot = choices(lots.rows.stream().filter(row -> row.availableGrams()>0)
                .map(row -> new Choice<>(row.id(), row.reference() + " · " + kg(row.availableGrams()) + " kg")).toList());
        var actual = new JTextField(kg(request.requestedGrams()), 12);
        if (form("sorting.request.deliver", "sorting.lot", lot, "sorting.delivered_kg", actual)) {
            service.deliver(request.id(), selected(lot), decimal(actual));
        }
    }

    private void reportBag() {
        Request request = requests.selected();
        if (request == null || !request.processing()) return;
        String language = ctx.getI18n().getActiveLanguage();
        var target = choices(ctx.getDictionaryDao().findTargetGroups(true).stream()
                .map(row -> new Choice<>(row.id(), row.displayLabel(language))).toList());
        var product = choices(ctx.getDictionaryDao().findGarmentTypes(true).stream()
                .map(row -> new Choice<>(row.id(), row.displayLabel(language))).toList());
        var grade = choices(ctx.getDictionaryDao().findQualityGrades(true).stream()
                .map(row -> new Choice<>(row.id(), row.displayLabel(language))).toList());
        var weight = new JTextField("15", 12);
        if (form("sorting.request.report", "sorting.target_group", target, "sorting.product", product,
                "sorting.grade", grade, "sorting.output_kg", weight)) {
            service.reportBag(request.id(), selected(target), selected(product), selected(grade), decimal(weight));
        }
    }

    private void finish() {
        Request request = requests.selected();
        if (request == null || !request.processing()) return;
        var weight = new JTextField(kg(request.deliveredGrams()), 12);
        var hint = new JLabel(t("sorting.finish.hint"));
        if (form("sorting.request.finish", "sorting.processed_kg", weight, "", hint)) {
            service.finish(request.id(), decimal(weight));
        }
    }

    private void packBag() {
        Bag bag = bags.selected();
        if (bag == null || bag.packedAt() != null) return;
        String language = ctx.getI18n().getActiveLanguage();
        var packaging = choices(ctx.getDictionaryDao().findPackageTypes(true).stream()
                .map(row -> new Choice<>(row.id(), row.displayLabel(language))).toList());
        if (form("sorting.bag.pack", "sorting.packaging", packaging)) service.pack(bag.id(), selected(packaging));
    }

    private void registerBag() {
        Bag bag = bags.selected();
        if (bag == null || bag.packedAt() == null || bag.registeredAt() != null) return;
        print(service.registerBag(bag.id()));
    }

    private void reprint() {
        Bag bag = bags.selected();
        if (bag == null || bag.qr() == null) return;
        ctx.getPackageDao().findByQrCode(bag.qr()).ifPresent(this::print);
    }

    private void print(hu.due.rewear.model.InventoryPackage inventoryPackage) {
        PrintPreviewDialog.showPreview(SwingUtilities.getWindowAncestor(this), ctx,
                List.of(inventoryPackage), LabelPreset.THERMAL_100X150);
    }

    private void receive() {
        var reference = new JTextField(18);
        var material = new JTextField(24);
        var area = new JTextField(24);
        var weight = new JTextField(12);
        if (form("sorting.lot.receive", "sorting.lot", reference, "sorting.lot.material", material,
                "sorting.lot.area", area, "sorting.received_kg", weight)) {
            service.receive(reference.getText(), material.getText(), area.getText(), decimal(weight));
        }
    }

    private void editTeam(Team team) {
        var users = ctx.getUserDao().findAll().stream().filter(User::active).toList();
        var sorters = memberList(people(users, RoleCode.SORTER), team == null ? List.of() : team.sorterIds());
        var helpers = memberList(people(users, RoleCode.SORTING_HELPER), team == null ? List.of() : team.helperIds());
        var driver = choices(people(users, RoleCode.SUPPLY_DRIVER));
        var station = choices(ctx.getLocationDao().findByType(LocationType.OPERATOR_STATION, true).stream()
                .map(row -> new Choice<>(row.id(), row.code())).toList());
        var name = new JTextField(team == null ? "" : team.name(), 20);
        var target = new JTextField(team == null ? "1000" : kg(team.targetGrams()), 12);
        var rate = new JTextField(team == null || team.bonusBasisPoints() == null ? ""
                : BigDecimal.valueOf(team.bonusBasisPoints(), 2).stripTrailingZeros().toPlainString(), 12);
        if (team != null) {
            select(driver, team.driverId());
            select(station, team.stationId());
        }
        if (form(team == null ? "sorting.team.new" : "common.edit", "sorting.team", name, "sorting.team.helpers", memberPane(helpers),
                "role.FORKLIFT", driver, "sorting.station", station, "sorting.team.sorters", memberPane(sorters),
                "sorting.target_kg", target, "sorting.bonus_rate", rate)) {
            service.saveTeam(team == null ? 0 : team.id(), name.getText(), helpers.getSelectedValuesList().stream().map(Choice::value).toList(), selected(driver),
                    selected(station), sorters.getSelectedValuesList().stream().map(Choice::value).toList(), decimal(target),
                    rate.getText().isBlank() ? null : decimal(rate));
        }
    }

    private static JList<Choice<Long>> memberList(List<Choice<Long>> options, List<Long> selected) {
        var model = new DefaultListModel<Choice<Long>>();
        options.forEach(model::addElement);
        var list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setSelectedIndices(java.util.stream.IntStream.range(0, model.size())
                .filter(i -> selected.contains(model.get(i).value())).toArray());
        return list;
    }

    private static JScrollPane memberPane(JList<?> members) {
        var scroller = new JScrollPane(members);
        scroller.setPreferredSize(new Dimension(280, 105));
        return scroller;
    }

    private static List<Choice<Long>> people(List<User> people, RoleCode role) {
        return people.stream().filter(user -> user.roleCode() == role)
                .map(user -> new Choice<>(user.id(), user.displayName() + " (@" + user.username() + ")")).toList();
    }

    private boolean form(String title, Object... rows) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints layout = new GridBagConstraints();
        layout.insets = new Insets(5, 5, 5, 5);
        layout.fill = GridBagConstraints.HORIZONTAL;
        layout.anchor = GridBagConstraints.WEST;
        for (int i=0; i<rows.length; i+=2) {
            String label = (String) rows[i];
            BrutalistStyle.addFormRow(panel, layout, i/2, label.isEmpty() ? "" : t(label), (JComponent) rows[i+1]);
        }
        return JOptionPane.showConfirmDialog(this, panel, t(title), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private boolean confirmation(String key) {
        return JOptionPane.showConfirmDialog(this, t(key), t("common.confirm"),
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    @Override public void refreshData() {
        Workspace state = service.workspace(day);
        requests.set(state.requests(), row -> new Object[]{row.id(), requestStatus(row), row.sorter(), row.team(), row.driver(),
                dash(kg(row.requestedGrams())), kg(row.deliveredGrams()), kg(row.processedGrams()), kg(row.outputGrams()),
                kg(row.processedGrams() == null ? null : row.deliveredGrams() - row.processedGrams()),
                kg(row.processedGrams() == null ? null : row.processedGrams() - row.outputGrams()), row.lot(), row.material() == null ? "" : row.material(),
                time(row.requestedAt()), time(row.deliveredAt()), time(row.finishedAt()), time(row.cancelledAt())});
        bags.set(state.bags(), row -> new Object[]{row.id(), bagStatus(row), row.requestId(), row.sorter(), row.target(), row.garment(),
                row.grade(), kg(row.weightGrams()), row.packaging(), row.packer(), row.station(), time(row.reportedAt()),
                time(row.packedAt()), time(row.registeredAt()), row.qr()});
        lots.set(state.lots(), row -> new Object[]{row.reference(), dash(row.material()), dash(row.sourceArea()),
                row.stored() ? row.locationCode() : t("sorting.lot.awaiting_storage"),
                amount(row.availableGrams()), amount(row.receivedGrams()), time(row.receivedAt())});
        teams.set(state.teams(), row -> new Object[]{row.name(), row.sorterIds().size(), row.helperIds().size(), kg(row.targetGrams()),
                row.bonusBasisPoints() == null ? "—" : percent(row.bonusBasisPoints())});
        daily.set(state.daily(), row -> new Object[]{row.sorter(), row.helper(), kg(row.processedGrams()),
                kg(row.targetGrams()), row.reachedTarget() ? t("sorting.bonus.eligible") : t("sorting.bonus.below"),
                row.bonusBasisPoints() == null ? t("sorting.bonus.unset") : percent(row.bonusBasisPoints())});
    }

    @Override public void applyTranslations() {
        sections.applyTranslations();
        actions.forEach(action -> action.button().setText(t(action.key())));
        columns(requests, "sorting.batch", "common.status", "role.SORTER", "sorting.team", "role.FORKLIFT",
                "sorting.requested_kg", "sorting.delivered_kg", "sorting.processed_kg", "sorting.output_kg",
                "sorting.returned_kg", "sorting.other_kg", "sorting.lot", "sorting.lot.material",
                "sorting.requested_at", "sorting.delivered_at", "sorting.finished_at", "sorting.cancelled_at");
        columns(bags, "sorting.bag", "common.status", "sorting.batch", "role.SORTER", "sorting.target_group", "sorting.product",
                "sorting.grade", "sorting.output_kg", "sorting.packaging", "sorting.packed_by", "sorting.station", "sorting.reported_at",
                "sorting.packed_at", "sorting.registered_at", "sorting.qr");
        columns(lots, "sorting.lot", "sorting.lot.material", "sorting.lot.area", "sorting.lot.location",
                "sorting.available_amount", "sorting.received_amount", "sorting.received_at");
        columns(teams, "sorting.team", "sorting.team.sorters", "sorting.team.helpers", "sorting.target_kg", "sorting.bonus_rate");
        columns(daily, "role.SORTER", "role.SORTING_HELPER", "sorting.processed_kg", "sorting.target_kg",
                "common.status", "sorting.bonus_rate");
        refreshData();
    }

    private String requestStatus(Request request) {
        if (request.awaitingDelivery()) return t("sorting.status.requested");
        if (request.processing()) return t("sorting.status.delivered");
        return t(request.cancelledAt() == null ? "sorting.status.finished" : "sorting.status.cancelled");
    }

    private String bagStatus(Bag bag) {
        if (bag.registeredAt() != null) return t("sorting.status.registered");
        return t(bag.packedAt() == null ? "sorting.status.graded" : "sorting.status.packed");
    }

    private void columns(DataTable<?> data, String... keys) {
        data.model.setColumnIdentifiers(java.util.Arrays.stream(keys).map(this::t).toArray());
    }

    @Override public void applyTheme() {
        sections.applyTheme();
        for (var data : List.of(requests, bags, lots, teams, daily)) BrutalistStyle.styleTable(data.table);
        actions.forEach(action -> {
            if (action.primary()) BrutalistStyle.stylePrimaryButton(action.button());
            else BrutalistStyle.styleSecondaryButton(action.button());
        });
    }

    @Override public String getViewKey() { return MainFrame.VIEW_SORTING; }
    @Override public String getTitleI18nKey() { return "nav.sorting"; }
    @Override public JComponent getComponent() { return this; }

    private String t(String key) { return ctx.getI18n().t(key); }

    private static String kg(Long grams) {
        return grams == null ? "" : SortingService.kg(grams).toPlainString();
    }

    /** Raw loads are talked about in tonnes; the exact kilograms are in the cell's tooltip. */
    private static String amount(long grams) {
        return SortingService.tonnes(grams).toPlainString() + " t";
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String time(String instant) {
        return instant == null ? "" : TIME.format(Instant.parse(instant));
    }

    private static String percent(int basisPoints) {
        return BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString() + "%";
    }

    private static BigDecimal decimal(JTextField field) {
        return new BigDecimal(field.getText().strip().replace(',', '.'));
    }

    private static JComboBox<Choice<Long>> choices(List<Choice<Long>> items) {
        var box = new JComboBox<Choice<Long>>();
        items.forEach(box::addItem);
        return box;
    }

    private static long selected(JComboBox<Choice<Long>> options) {
        int index = options.getSelectedIndex();
        if (index < 0) throw new IllegalArgumentException("Select an available option.");
        return options.getItemAt(index).value();
    }

    private static void select(JComboBox<Choice<Long>> options, long id) {
        for (int i=0; i<options.getItemCount(); i++) {
            if (options.getItemAt(i).value() == id) options.setSelectedIndex(i);
        }
    }

    private record ActionButton(JButton button, String key, boolean primary) { }
    private record Choice<T>(T value, String label) { @Override public String toString() { return label; } }

    private static final class DataTable<T> {
        private final DefaultTableModel model = BrutalistStyle.nonEditableTableModel();
        private final JTable table = BrutalistStyle.tableWithTooltips(model);
        private List<T> rows = List.of();

        void set(List<T> values, Function<T, Object[]> cells) {
            int row = TableSorting.selectedModelRow(table);
            Object key = row >= 0 && model.getColumnCount() > 0 ? model.getValueAt(row, 0) : null;
            rows = values;
            model.setRowCount(0);
            for (T value : values) model.addRow(cells.apply(value));
            for (int i=0; key != null && i<model.getRowCount(); i++) {
                if (key.equals(model.getValueAt(i, 0))) {
                    TableSorting.selectModelRow(table, i);
                    break;
                }
            }
            if (table.getColumnCount() > 8) {
                table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
                for (int i=0; i<table.getColumnCount(); i++) {
                    var column = table.getColumnModel().getColumn(i);
                    column.setPreferredWidth(i == 0 ? 90 : 148);
                }
            }
        }

        T selected() {
            int row = TableSorting.selectedModelRow(table);
            return row < 0 || row >= rows.size() ? null : rows.get(row);
        }
    }
}
