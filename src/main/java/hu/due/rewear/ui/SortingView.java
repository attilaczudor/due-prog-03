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
    /** A stoppage is a wall-clock event on a work day, so its times are written and read as local ones. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SHORT_STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final AppContext ctx;
    private final SortingService service;
    private final SectionNavigation sections;
    private final DataTable<Request> requests = new DataTable<>();
    private final DataTable<Bag> bags = new DataTable<>();
    private final DataTable<Lot> lots = new DataTable<>();
    private final DataTable<hu.due.rewear.model.PurchaseLot> purchases = new DataTable<>();
    private final DataTable<hu.due.rewear.service.GradeYieldService.Line> yield = new DataTable<>();
    private final JTextField yieldFrom = new JTextField(10);
    private final JTextField yieldTo = new JTextField(10);
    private final JLabel yieldSummary = new JLabel(" ");
    private hu.due.rewear.dao.GradeYieldDao.Scope yieldScope = hu.due.rewear.dao.GradeYieldDao.Scope.all();
    private final DataTable<hu.due.rewear.model.DowntimeEvent> stoppages = new DataTable<>();
    private final JTextField downFrom = new JTextField(10);
    private final JTextField downTo = new JTextField(10);
    private final JLabel downSummary = new JLabel(" ");
    private hu.due.rewear.dao.DowntimeDao.Scope downScope = hu.due.rewear.dao.DowntimeDao.Scope.all();
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
        yieldFrom.setText(day.minusDays(30).toString());
        yieldTo.setText(day.toString());
        downFrom.setText(day.minusDays(7).toString());
        downTo.setText(day.toString());
        // The period the screen opens on is the period the boxes say it is. Opening on everything ever
        // recorded, with last week's dates showing above it, would be a figure nobody could place.
        downScope = new hu.due.rewear.dao.DowntimeDao.Scope(day.minusDays(7), day, null, null);
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
        // Provenance sits next to the material it explains: a purchase lot is only useful where somebody is
        // looking at what arrived.
        if (manages || role == RoleCode.OPERATOR) {
            var purchaseActions = controls();
            if (manages) {
                addAction(purchaseActions, "purchase.new", true, this::newPurchaseLot);
            }
            addAction(purchaseActions, "purchase.assess", role == RoleCode.OPERATOR, this::assessPurchaseLot);
            addAction(purchaseActions, "purchase.history", false, this::purchaseHistory);
            addSection("purchase.lots", MainFrame.VIEW_CUSTOMERS, purchases, purchaseActions);
        }
        if (role == RoleCode.OPERATOR || role == RoleCode.SORTING_HELPER) {
            addSection("sorting.bags", MainFrame.VIEW_INTAKE, bags, bagActions);
            addSection("sorting.requests", MainFrame.VIEW_TODO, requests, requestActions);
        } else {
            addSection("sorting.requests", MainFrame.VIEW_TODO, requests, requestActions);
            addSection("sorting.bags", MainFrame.VIEW_INTAKE, bags, bagActions);
        }
        // Yield is the production lead's measure, so it sits with the daily figures rather than with the
        // material: what matters is the mix that came out, not the weight that went in.
        if (manages) {
            var yieldActions = controls();
            yieldFrom.getAccessibleContext().setAccessibleName(t("yield.from"));
            yieldTo.getAccessibleContext().setAccessibleName(t("yield.to"));
            yieldActions.add(yieldFrom);
            yieldActions.add(yieldTo);
            addAction(yieldActions, "yield.show", true, this::showYield);
            addAction(yieldActions, "yield.by_lot", false, this::yieldByPurchaseLot);
            addAction(yieldActions, "yield.capture_baseline", false, this::captureBaseline);
            var yieldPanel = new JPanel(new BorderLayout(0, 6));
            yieldPanel.setOpaque(false);
            yieldPanel.add(yieldActions, BorderLayout.NORTH);
            yieldPanel.add(new JScrollPane(yield.table), BorderLayout.CENTER);
            yieldPanel.add(yieldSummary, BorderLayout.SOUTH);
            sections.addSection("yield.title", SidebarIcons.forView(MainFrame.VIEW_DASHBOARD, 16), yieldPanel);
        }
        // Where the hours went that were not spent sorting. It sits beside yield because the two answer the
        // same question from opposite ends: what came out of the time, and what happened to the rest of it.
        if (manages || role == RoleCode.SORTER) {
            boolean keepsReasons = RoleCode.MANAGEMENT.contains(role) || role == RoleCode.PRODUCTION_LEAD;
            var downActions = controls();
            downFrom.getAccessibleContext().setAccessibleName(t("downtime.from"));
            downTo.getAccessibleContext().setAccessibleName(t("downtime.to"));
            downActions.add(downFrom);
            downActions.add(downTo);
            addAction(downActions, "downtime.show", true, this::showDowntime);
            if (manages) {
                addAction(downActions, "downtime.record", false, this::recordStoppage);
                addAction(downActions, "downtime.close", false, this::closeStoppage);
            }
            addAction(downActions, "downtime.breakdown", false, this::downtimeBreakdown);
            if (keepsReasons) {
                addAction(downActions, "downtime.reason.new", false, this::newReason);
                addAction(downActions, "downtime.reason.edit", false, this::editReason);
            }
            var downPanel = new JPanel(new BorderLayout(0, 6));
            downPanel.setOpaque(false);
            downPanel.add(downActions, BorderLayout.NORTH);
            downPanel.add(new JScrollPane(stoppages.table), BorderLayout.CENTER);
            downPanel.add(downSummary, BorderLayout.SOUTH);
            sections.addSection("downtime.title", SidebarIcons.forView(MainFrame.VIEW_TODO, 16), downPanel);
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

    // ---------------------------------------------------------------- stoppages

    private hu.due.rewear.service.DowntimeService downtime() {
        return ctx.getDowntimeService();
    }

    private void showDowntime() {
        downScope = new hu.due.rewear.dao.DowntimeDao.Scope(
                LocalDate.parse(downFrom.getText().strip()), LocalDate.parse(downTo.getText().strip()),
                null, null);
    }

    /**
     * Records a stoppage. Leaving the "started again" box empty starts the clock instead of closing it,
     * which is what somebody standing at a stopped line actually wants.
     */
    private void recordStoppage() {
        var teamBox = choices(withAll(service.workspace(day).teams().stream()
                .map(row -> new Choice<>(row.id(), row.name())).toList(), "downtime.whole_line"));
        var sorterBox = choices(withAll(sorterChoices(), "downtime.everybody"));
        var stationBox = choices(withAll(ctx.getLocationDao()
                .findByType(LocationType.OPERATOR_STATION, true).stream()
                .map(row -> new Choice<>(row.id(), row.code())).toList(), "downtime.any_station"));
        var reasonBox = choices(withAll(reasonChoices(), "downtime.reason_unknown"));
        var started = new JTextField(BusinessClock.now().format(STAMP), 16);
        var ended = new JTextField(16);
        var note = new JTextField(24);
        if (!form("downtime.record", "downtime.team", teamBox, "downtime.sorter", sorterBox,
                "sorting.station", stationBox, "downtime.started", started, "downtime.ended", ended,
                "downtime.reason", reasonBox, "common.notes", note)) {
            return;
        }
        Long team = optional(teamBox);
        Long sorter = optional(sorterBox);
        Long station = optional(stationBox);
        Long reason = optional(reasonBox);
        var from = stamp(started.getText());
        var to = ended.getText().isBlank() ? null : stamp(ended.getText());
        if (to == null) {
            downtime().open(team, sorter, station, from, reason, note.getText());
            return;
        }
        if (reason == null) {
            throw new IllegalArgumentException(t("downtime.reason_required"));
        }
        downtime().log(team, sorter, station, from, to, reason, note.getText());
    }

    /** Closes the selected stoppage. A reason is not optional here: that is the point of the record. */
    private void closeStoppage() {
        var event = stoppages.selected();
        if (event == null || !event.running()) {
            JOptionPane.showMessageDialog(this, t("downtime.select_running"), t("common.warning"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        var reasonBox = choices(reasonChoices());
        if (event.reasonId() != null) {
            select(reasonBox, event.reasonId());
        }
        var note = new JTextField(event.note() == null ? "" : event.note(), 24);
        var since = new JLabel(event.scopeLabel() + " · " + event.startedAt().format(STAMP));
        if (form("downtime.close", "", since, "downtime.reason", reasonBox, "common.notes", note)) {
            downtime().close(event.id(), selected(reasonBox), note.getText());
        }
    }

    /** The same minutes grouped two ways: by loss group, which is what gets managed, and by reason. */
    private void downtimeBreakdown() {
        var report = downtime().report(downScope);
        if (report.isEmpty()) {
            JOptionPane.showMessageDialog(this, t("downtime.nothing_logged"), t("downtime.title"),
                    JOptionPane.PLAIN_MESSAGE);
            return;
        }
        var text = new StringBuilder();
        for (var group : report.groups()) {
            text.append(t("downtime.group." + group.group().name().toLowerCase(java.util.Locale.ROOT)))
                    .append(": ").append(group.minutes()).append(" ").append(t("downtime.min"))
                    .append(" · ").append(money(group.sharePercent())).append("%\n");
        }
        text.append("\n");
        for (var line : report.lines()) {
            text.append(line.code()).append(" · ").append(line.name(ctx.getI18n().getActiveLanguage()))
                    .append(": ").append(line.minutes()).append(" ").append(t("downtime.min"))
                    .append(line.attributable() ? " · " + t("downtime.attributable.yes") : "")
                    .append("\n");
        }
        JOptionPane.showMessageDialog(this, text.toString().strip(), t("downtime.breakdown"),
                JOptionPane.PLAIN_MESSAGE);
    }

    private void newReason() {
        var groupBox = groupChoices();
        var code = new JTextField(10);
        var english = new JTextField(24);
        var hungarian = new JTextField(24);
        var crewsOwn = new JCheckBox(t("downtime.attributable_hint"));
        if (form("downtime.reason.new", "downtime.reason.code", code, "downtime.group", groupBox,
                "downtime.reason.en", english, "downtime.reason.hu", hungarian, "", crewsOwn)) {
            downtime().createReason(code.getText(),
                    hu.due.rewear.model.LossGroup.valueOf((String) groupBox.getSelectedItem()),
                    english.getText(), hungarian.getText(), crewsOwn.isSelected());
        }
    }

    /**
     * Rewording is what the fortnightly review of the list is for, so this edits the text in place; the
     * retire box takes a reason out of use without touching the stoppages already logged under it.
     */
    private void editReason() {
        var reasons = downtime().reasons(false);
        if (reasons.isEmpty()) {
            return;
        }
        var reasonBox = choices(reasons.stream()
                .map(row -> new Choice<>(row.id(), row.code() + " · " + row.nameEn()
                        + (row.active() ? "" : " (" + t("downtime.retired") + ")"))).toList());
        var groupBox = groupChoices();
        var english = new JTextField(24);
        var hungarian = new JTextField(24);
        var crewsOwn = new JCheckBox(t("downtime.attributable_hint"));
        var retire = new JCheckBox(t("downtime.reason.retire"));
        Runnable fill = () -> {
            int index = reasonBox.getSelectedIndex();
            if (index < 0) {
                return;
            }
            long id = reasonBox.getItemAt(index).value();
            reasons.stream().filter(row -> row.id() == id).findFirst().ifPresent(row -> {
                groupBox.setSelectedItem(row.lossGroup().name());
                english.setText(row.nameEn());
                hungarian.setText(row.nameHu());
                crewsOwn.setSelected(row.attributable());
                retire.setSelected(!row.active());
            });
        };
        fill.run();
        reasonBox.addActionListener(event -> fill.run());
        if (!form("downtime.reason.edit", "downtime.reason", reasonBox, "downtime.group", groupBox,
                "downtime.reason.en", english, "downtime.reason.hu", hungarian, "", crewsOwn, "", retire)) {
            return;
        }
        long id = selected(reasonBox);
        var current = reasons.stream().filter(row -> row.id() == id).findFirst().orElseThrow();
        downtime().updateReason(id, hu.due.rewear.model.LossGroup.valueOf((String) groupBox.getSelectedItem()),
                english.getText(), hungarian.getText(), crewsOwn.isSelected());
        if (retire.isSelected() && current.active()) {
            downtime().retireReason(id);
        } else if (!retire.isSelected() && !current.active()) {
            downtime().reinstateReason(id);
        }
    }

    private JComboBox<String> groupChoices() {
        var box = new JComboBox<String>();
        for (var group : hu.due.rewear.model.LossGroup.values()) {
            box.addItem(group.name());
        }
        return box;
    }

    private List<Choice<Long>> reasonChoices() {
        return downtime().reasons(true).stream()
                .map(row -> new Choice<>(row.id(),
                        row.code() + " · " + row.name(ctx.getI18n().getActiveLanguage())))
                .toList();
    }

    private List<Choice<Long>> sorterChoices() {
        return ctx.getUserDao().findAll().stream()
                .filter(user -> user.active() && user.roleCode() == RoleCode.SORTER)
                .map(user -> new Choice<>(user.id(), user.displayName()))
                .toList();
    }

    /** Puts "everybody" at the top of a list, held as zero, because a stoppage often belongs to no one row. */
    private List<Choice<Long>> withAll(List<Choice<Long>> items, String labelKey) {
        var all = new ArrayList<Choice<Long>>();
        all.add(new Choice<>(0L, t(labelKey)));
        all.addAll(items);
        return all;
    }

    private static Long optional(JComboBox<Choice<Long>> options) {
        long value = selected(options);
        return value == 0 ? null : value;
    }

    private static java.time.LocalDateTime stamp(String text) {
        try {
            return java.time.LocalDateTime.parse(text.strip().replace(' ', 'T'));
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException("Use the form 2026-11-26 14:05 for a time.");
        }
    }

    private static String shortStamp(java.time.LocalDateTime value) {
        return value == null ? "—" : value.format(SHORT_STAMP);
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
        var weight = new JTextField(12);
        // Only assessed lots with something left can be drawn on, so the list cannot offer a choice the
        // service would then have to refuse.
        var open = ctx.getPurchaseLotService().openLots().stream()
                .filter(hu.due.rewear.model.PurchaseLot::isAssessed)
                .map(row -> new Choice<>(row.id(), row.reference() + " · " + row.originCountry()
                        + " · " + row.collectionChannel()))
                .toList();
        var source = choices(open);
        if (form("sorting.lot.receive", "sorting.lot", reference, "sorting.lot.material", material,
                "purchase.lot", source, "sorting.received_kg", weight)) {
            if (open.isEmpty()) {
                throw new IllegalArgumentException(t("purchase.none_open"));
            }
            service.receive(reference.getText(), material.getText(), null, decimal(weight), selected(source));
        }
    }

    private void showYield() {
        yieldScope = hu.due.rewear.dao.GradeYieldDao.Scope.all()
                .withPeriod(LocalDate.parse(yieldFrom.getText().strip()),
                        LocalDate.parse(yieldTo.getText().strip()));
    }

    /** Narrows the cascade to one bought lot, which is where a purchase price is judged. */
    private void yieldByPurchaseLot() {
        var lot = purchases.selected();
        if (lot == null) {
            JOptionPane.showMessageDialog(this, t("yield.select_lot"), t("common.warning"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        yieldScope = new hu.due.rewear.dao.GradeYieldDao.Scope(lot.id(), null, null, null, null, null);
        var margin = ctx.getGradeYieldService().marginForPurchaseLot(lot.id());
        JOptionPane.showMessageDialog(this,
                t("yield.margin_of") + " " + margin.lotReference() + "\n\n"
                        + t("yield.processed") + ": " + money(margin.processedKg()) + " kg\n"
                        + t("yield.cost") + ": " + money(margin.cost()) + " " + margin.currency() + "\n"
                        + t("yield.revenue") + ": " + money(margin.revenue()) + " " + margin.currency() + "\n"
                        + t("yield.margin") + ": " + money(margin.margin()) + " " + margin.currency()
                        + (margin.marginPercent() == null ? "" : "  (" + money(margin.marginPercent()) + "%)"),
                t("yield.title"), JOptionPane.PLAIN_MESSAGE);
    }

    private void captureBaseline() {
        var name = new JTextField(20);
        var from = new JTextField(yieldFrom.getText(), 10);
        var to = new JTextField(yieldTo.getText(), 10);
        var notes = new JTextField(24);
        var activate = new JCheckBox();
        activate.setSelected(true);
        activate.setOpaque(false);
        if (form("yield.capture_baseline", "yield.baseline_name", name, "yield.from", from, "yield.to", to,
                "yield.baseline_notes", notes, "yield.baseline_activate", activate)) {
            ctx.getGradeYieldService().captureBaseline(name.getText(),
                    LocalDate.parse(from.getText().strip()), LocalDate.parse(to.getText().strip()),
                    notes.getText(), activate.isSelected());
        }
    }

    private static String money(java.math.BigDecimal value) {
        return hu.due.rewear.util.FormatUtil.stripZeros(value);
    }

    private void newPurchaseLot() {
        var reference = new JTextField(18);
        var country = new JTextField(6);
        var channel = new JComboBox<>(hu.due.rewear.model.CollectionChannel.values());
        var suppliers = choices(ctx.getCustomerDao().findSuppliers(true).stream()
                .map(row -> new Choice<>(row.id(), row.name()))
                .toList());
        var price = new JTextField("0", 10);
        var currency = new JTextField("EUR", 6);
        var gross = new JTextField(10);
        var net = new JTextField(10);
        var customsCode = new JTextField(12);
        var customsText = new JTextField(24);
        if (form("purchase.new", "purchase.lot", reference, "purchase.supplier", suppliers,
                "purchase.country", country, "purchase.channel", channel,
                "purchase.gross_kg", gross, "purchase.net_kg", net,
                "purchase.price_per_kg", price, "purchase.currency", currency,
                "purchase.customs_code", customsCode, "purchase.customs_text", customsText)) {
            ctx.getPurchaseLotService().record(new hu.due.rewear.service.PurchaseLotService.NewLot(
                    reference.getText(),
                    suppliers.getItemCount() == 0 ? null : selected(suppliers),
                    country.getText(),
                    (hu.due.rewear.model.CollectionChannel) channel.getSelectedItem(),
                    customsCode.getText(), customsText.getText(),
                    decimal(price), currency.getText(), decimal(gross), decimal(net),
                    null, null, null));
        }
    }

    private void assessPurchaseLot() {
        var lot = purchases.selected();
        if (lot == null) {
            JOptionPane.showMessageDialog(this, t("sorting.lot.select_first"), t("common.warning"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        var status = new JComboBox<>(new hu.due.rewear.model.LotLegalStatus[]{
                hu.due.rewear.model.LotLegalStatus.WASTE,
                hu.due.rewear.model.LotLegalStatus.FIT_FOR_REUSE});
        var place = new JTextField(24);
        var basis = new JTextField(30);
        if (form("purchase.assess", "purchase.legal_status", status, "purchase.assess_place", place,
                "purchase.assess_basis", basis)) {
            ctx.getPurchaseLotService().assess(lot.id(),
                    (hu.due.rewear.model.LotLegalStatus) status.getSelectedItem(),
                    basis.getText(), place.getText());
        }
    }

    private void purchaseHistory() {
        var lot = purchases.selected();
        if (lot == null) {
            JOptionPane.showMessageDialog(this, t("sorting.lot.select_first"), t("common.warning"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        StringBuilder text = new StringBuilder();
        for (var event : ctx.getPurchaseLotService().history(lot.id())) {
            text.append(event.createdAt()).append("  ").append(event.eventType())
                .append(event.createdByName() == null ? "" : "  (" + event.createdByName() + ")")
                .append(event.detail() == null ? "" : "\n    " + event.detail())
                .append('\n');
        }
        var area = new JTextArea(text.toString(), 12, 56);
        area.setEditable(false);
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(area),
                t("purchase.history") + " — " + lot.reference(), JOptionPane.PLAIN_MESSAGE);
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
                "role.SUPPLY_DRIVER", driver, "sorting.station", station, "sorting.team.sorters", memberPane(sorters),
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
        purchases.set(ctx.getPurchaseLotService().all(), row -> new Object[]{row.reference(),
                dash(row.supplierName()), row.originCountry(), row.collectionChannel().name(),
                t("purchase.status." + row.legalStatus().name().toLowerCase(java.util.Locale.ROOT)),
                hu.due.rewear.util.FormatUtil.stripZeros(row.netWeightKg()),
                hu.due.rewear.util.FormatUtil.stripZeros(row.purchasePricePerKg()) + " " + row.currency(),
                hu.due.rewear.util.FormatUtil.stripZeros(row.totalCost()) + " " + row.currency(),
                dash(row.assessedByName())});
        if (ctx.getSession().getCurrentUser().map(u -> u.roleCode().runsProduction()).orElse(false)) {
            var cascade = ctx.getGradeYieldService().report(yieldScope);
            yield.set(cascade.lines(), row -> new Object[]{
                    row.isResidue() ? t("yield.residue") : row.code() + " · " + row.name(),
                    money(row.kg()), money(row.sharePercent()) + "%",
                    row.baselineBasisPoints() == null ? "—"
                            : money(java.math.BigDecimal.valueOf(row.baselineBasisPoints(), 2)) + "%",
                    row.variancePoints() == null ? "—" : signed(row.variancePoints()),
                    row.isResidue() ? "—" : money(row.valueEur()),
                    row.isResidue() ? "—" : money(row.valueHuf())});
            yieldSummary.setText(cascade.isEmpty() ? t("yield.nothing_sorted")
                    : t("yield.processed") + ": " + money(cascade.processedKg()) + " kg · "
                      + t("yield.recovery") + ": " + money(cascade.recoveryPercent()) + "% · "
                      + t("yield.value_eur") + ": " + money(cascade.valueEur()) + " · "
                      + t("yield.per_kg") + ": " + money(cascade.revenuePerProcessedKgEur()) + " EUR/kg"
                      + (cascade.baselineName() == null ? " · " + t("yield.no_baseline")
                         : " · " + t("yield.baseline") + ": " + cascade.baselineName()));
        }
        RoleCode role = ctx.getSession().requireCurrentUser().roleCode();
        if (role.runsProduction() || role == RoleCode.SORTER) {
            String language = ctx.getI18n().getActiveLanguage();
            stoppages.set(downtime().events(downScope), row -> new Object[]{
                    row.workDay().toString(), row.scopeLabel(),
                    row.reasonCode() == null ? t("downtime.running")
                            : row.reasonCode() + " · " + row.reasonName(language),
                    row.lossGroup() == null ? "—"
                            : t("downtime.group." + row.lossGroup().name().toLowerCase(java.util.Locale.ROOT)),
                    shortStamp(row.startedAt()), shortStamp(row.endedAt()),
                    row.minutes() == null ? "—" : String.valueOf(row.minutes()),
                    row.peopleAffected(),
                    row.minutes() == null ? "—" : String.valueOf((long) row.minutes() * row.peopleAffected()),
                    Boolean.TRUE.equals(row.attributable()) ? t("downtime.attributable.yes")
                            : t("downtime.attributable.no"),
                    dash(row.note()), dash(row.openedByName())});
            var stopped = downtime().report(downScope);
            var availability = stopped.availabilityPercent();
            downSummary.setText(stopped.isEmpty() ? t("downtime.nothing_logged")
                    : t("downtime.stopped") + ": " + stopped.stoppedMinutes() + " " + t("downtime.min")
                      + " · " + t("downtime.person_minutes") + ": " + stopped.personMinutes()
                      + " · " + t("downtime.attributable") + ": " + stopped.attributablePersonMinutes()
                      + " · " + t("downtime.not_theirs") + ": " + stopped.unattributablePersonMinutes()
                      + " · " + t("downtime.availability") + ": "
                      + (availability == null ? t("downtime.no_roster") : money(availability) + "%")
                      + " · " + t("downtime.waiting") + ": " + stopped.waitingForStockMinutes() + " "
                      + t("downtime.min"));
        }
        teams.set(state.teams(), row -> new Object[]{row.name(), row.sorterIds().size(), row.helperIds().size(), kg(row.targetGrams()),
                row.bonusBasisPoints() == null ? "—" : percent(row.bonusBasisPoints())});
        daily.set(state.daily(), row -> new Object[]{row.sorter(), row.helper(), kg(row.processedGrams()),
                kg(row.targetGrams()), row.reachedTarget() ? t("sorting.bonus.eligible") : t("sorting.bonus.below"),
                row.bonusBasisPoints() == null ? t("sorting.bonus.unset") : percent(row.bonusBasisPoints())});
    }

    @Override public void applyTranslations() {
        sections.applyTranslations();
        actions.forEach(action -> action.button().setText(t(action.key())));
        columns(requests, "sorting.batch", "common.status", "role.SORTER", "sorting.team", "role.SUPPLY_DRIVER",
                "sorting.requested_kg", "sorting.delivered_kg", "sorting.processed_kg", "sorting.output_kg",
                "sorting.returned_kg", "sorting.other_kg", "sorting.lot", "sorting.lot.material",
                "sorting.requested_at", "sorting.delivered_at", "sorting.finished_at", "sorting.cancelled_at");
        columns(bags, "sorting.bag", "common.status", "sorting.batch", "role.SORTER", "sorting.target_group", "sorting.product",
                "sorting.grade", "sorting.output_kg", "sorting.packaging", "sorting.packed_by", "sorting.station", "sorting.reported_at",
                "sorting.packed_at", "sorting.registered_at", "sorting.qr");
        columns(lots, "sorting.lot", "sorting.lot.material", "sorting.lot.area", "sorting.lot.location",
                "sorting.available_amount", "sorting.received_amount", "sorting.received_at");
        columns(purchases, "purchase.lot", "purchase.supplier", "purchase.country", "purchase.channel",
                "purchase.legal_status", "purchase.net_kg", "purchase.price_per_kg", "purchase.cost",
                "purchase.assessed_by");
        columns(yield, "sorting.grade", "yield.output_kg", "yield.share", "yield.baseline_share",
                "yield.variance", "yield.value_eur", "yield.value_huf");
        columns(stoppages, "sorting.work_day", "downtime.scope", "downtime.reason", "downtime.group",
                "downtime.started", "downtime.ended", "downtime.minutes", "downtime.people",
                "downtime.person_minutes", "downtime.attributable", "common.notes", "downtime.opened_by");
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
        for (var data : List.of(requests, bags, lots, purchases, yield, teams, daily)) BrutalistStyle.styleTable(data.table);
        yieldSummary.setForeground(hu.due.rewear.config.ThemeManager.muted());
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

    /** A variance reads better with its sign: +1.4 and -1.4 are different news. */
    private static String signed(java.math.BigDecimal points) {
        String text = hu.due.rewear.util.FormatUtil.stripZeros(points);
        return points.signum() > 0 ? "+" + text : text;
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String time(String instant) {
        return instant == null ? "" : TIME.format(hu.due.rewear.dao.DaoDateUtil.parse(instant));
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
