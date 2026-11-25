package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.GarmentType;
import hu.due.rewear.model.PackageType;
import hu.due.rewear.model.QualityGrade;
import hu.due.rewear.model.TargetGroup;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.BigDecimal;
import java.util.List;

/**
 * Administration screen for all four QR code segment dictionaries:
 * Target Groups, Product/Garment Types, Quality Grades, and Package Types.
 * Enforces QR code naming conventions and prevents deleting codes referenced by existing packages.
 */
public final class DictionariesView extends JPanel implements NavigableView {

    private static final String TARGETS = "dictionaries.tab.targets";
    private static final String GARMENTS = "dictionaries.tab.garments";
    private static final String QUALITIES = "dictionaries.tab.qualities";
    private static final String PACKAGES = "dictionaries.tab.packages";

    private final AppContext ctx;
    private final SectionNavigation sections;

    private final JButton addBtn = new JButton();
    private final JButton editBtn = new JButton();
    private final JButton toggleActiveBtn = new JButton();
    private final JButton deleteBtn = new JButton();

    private final DefaultTableModel targetModel = BrutalistStyle.nonEditableTableModel();
    private final DefaultTableModel garmentModel = BrutalistStyle.nonEditableTableModel();
    private final DefaultTableModel qualityModel = BrutalistStyle.nonEditableTableModel();
    private final DefaultTableModel packageModel = BrutalistStyle.nonEditableTableModel();

    private final JTable targetTable = createTable(targetModel);
    private final JTable garmentTable = createTable(garmentModel);
    private final JTable qualityTable = createTable(qualityModel);
    private final JTable packageTable = createTable(packageModel);

    private List<TargetGroup> targets = List.of();
    private List<GarmentType> garments = List.of();
    private List<QualityGrade> qualities = List.of();
    private List<PackageType> packages = List.of();

    public DictionariesView(AppContext ctx) {
        this.ctx = ctx;
        sections = new SectionNavigation(ctx.getI18n());

        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        buildUi();
        wireEvents();
        applyTranslations();
    }

    private static JTable createTable(DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setRowHeight(26);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setFillsViewportHeight(true);
        return t;
    }

    private void buildUi() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        toolbar.add(addBtn);
        toolbar.add(editBtn);
        toolbar.add(toggleActiveBtn);
        toolbar.add(deleteBtn);
        sections.setContentHeader(toolbar);

        sections.addSection(TARGETS, SidebarIcons.forView(MainFrame.VIEW_CUSTOMERS, 16), new JScrollPane(targetTable));
        sections.addSection(GARMENTS, SidebarIcons.forView(MainFrame.VIEW_DICTIONARIES, 16), new JScrollPane(garmentTable));
        sections.addSection(QUALITIES, SidebarIcons.forView(MainFrame.VIEW_TODO, 16), new JScrollPane(qualityTable));
        sections.addSection(PACKAGES, SidebarIcons.forView(MainFrame.VIEW_INTAKE, 16), new JScrollPane(packageTable));
        add(sections, BorderLayout.CENTER);
    }

    private void wireEvents() {
        addBtn.addActionListener(e -> handleAdd());
        editBtn.addActionListener(e -> handleEdit());
        toggleActiveBtn.addActionListener(e -> handleToggleActive());
        deleteBtn.addActionListener(e -> handleDelete());
    }

    private void handleAdd() {
        switch (sections.selectedKey()) {
            case TARGETS -> openTargetDialog(null);
            case GARMENTS -> openGarmentDialog(null);
            case QUALITIES -> openQualityDialog(null);
            case PACKAGES -> openPackageTypeDialog(null);
            default -> {}
        }
    }

    private void handleEdit() {
        switch (sections.selectedKey()) {
            case TARGETS -> {
                int r = TableSorting.selectedModelRow(targetTable);
                if (r >= 0 && r < targets.size()) openTargetDialog(targets.get(r));
            }
            case GARMENTS -> {
                int r = TableSorting.selectedModelRow(garmentTable);
                if (r >= 0 && r < garments.size()) openGarmentDialog(garments.get(r));
            }
            case QUALITIES -> {
                int r = TableSorting.selectedModelRow(qualityTable);
                if (r >= 0 && r < qualities.size()) openQualityDialog(qualities.get(r));
            }
            case PACKAGES -> {
                int r = TableSorting.selectedModelRow(packageTable);
                if (r >= 0 && r < packages.size()) openPackageTypeDialog(packages.get(r));
            }
            default -> {}
        }
    }

    private void handleToggleActive() {
        switch (sections.selectedKey()) {
            case TARGETS -> {
                int r = TableSorting.selectedModelRow(targetTable);
                if (r >= 0 && r < targets.size()) {
                    TargetGroup g = targets.get(r);
                    ctx.getDictionaryDao().updateTargetGroup(g.id(), g.code(), g.nameEn(), g.nameHu(), !g.active());
                    refreshData();
                }
            }
            case GARMENTS -> {
                int r = TableSorting.selectedModelRow(garmentTable);
                if (r >= 0 && r < garments.size()) {
                    GarmentType g = garments.get(r);
                    ctx.getDictionaryDao().updateGarmentType(g.id(), g.code(), g.nameEn(), g.nameHu(),
                            g.priceEurPerKg(), g.priceHufPerKg(), !g.active());
                    refreshData();
                }
            }
            case QUALITIES -> {
                int r = TableSorting.selectedModelRow(qualityTable);
                if (r >= 0 && r < qualities.size()) {
                    QualityGrade q = qualities.get(r);
                    ctx.getDictionaryDao().updateQualityGrade(q.id(), q.code(), q.nameEn(), q.nameHu(),
                            q.defaultPricePerKg(), q.defaultPriceHufPerKg(), !q.active());
                    refreshData();
                }
            }
            case PACKAGES -> {
                int r = TableSorting.selectedModelRow(packageTable);
                if (r >= 0 && r < packages.size()) {
                    PackageType p = packages.get(r);
                    ctx.getDictionaryDao().updatePackageType(p.id(), p.code(), p.nameEn(), p.nameHu(), p.tareWeightKg(), p.maxWeightKg(), !p.active());
                    refreshData();
                }
            }
            default -> {}
        }
    }

    private void handleDelete() {
        I18nManager i18n = ctx.getI18n();
        switch (sections.selectedKey()) {
            case TARGETS -> {
                int r = TableSorting.selectedModelRow(targetTable);
                if (r < 0 || r >= targets.size()) return;
                TargetGroup g = targets.get(r);
                if (ctx.getDictionaryDao().isTargetGroupReferenced(g.id())) {
                    JOptionPane.showMessageDialog(this, i18n.t("dictionaries.in_use", g.code()),
                            i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (confirmDelete(g.code())) {
                    ctx.getDictionaryDao().deleteTargetGroup(g.id());
                    refreshData();
                }
            }
            case GARMENTS -> {
                int r = TableSorting.selectedModelRow(garmentTable);
                if (r < 0 || r >= garments.size()) return;
                GarmentType g = garments.get(r);
                if (ctx.getDictionaryDao().isGarmentTypeReferenced(g.id())) {
                    JOptionPane.showMessageDialog(this, i18n.t("dictionaries.in_use", g.code()),
                            i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (confirmDelete(g.code())) {
                    ctx.getDictionaryDao().deleteGarmentType(g.id());
                    refreshData();
                }
            }
            case QUALITIES -> {
                int r = TableSorting.selectedModelRow(qualityTable);
                if (r < 0 || r >= qualities.size()) return;
                QualityGrade q = qualities.get(r);
                if (ctx.getDictionaryDao().isQualityGradeReferenced(q.id())) {
                    JOptionPane.showMessageDialog(this, i18n.t("dictionaries.in_use", q.code()),
                            i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (confirmDelete(q.code())) {
                    ctx.getDictionaryDao().deleteQualityGrade(q.id());
                    refreshData();
                }
            }
            case PACKAGES -> {
                int r = TableSorting.selectedModelRow(packageTable);
                if (r < 0 || r >= packages.size()) return;
                PackageType p = packages.get(r);
                if (ctx.getDictionaryDao().isPackageTypeReferenced(p.id())) {
                    JOptionPane.showMessageDialog(this, i18n.t("dictionaries.in_use", p.code()),
                            i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (confirmDelete(p.code())) {
                    ctx.getDictionaryDao().deletePackageType(p.id());
                    refreshData();
                }
            }
            default -> {}
        }
    }

    private boolean confirmDelete(String code) {
        I18nManager i18n = ctx.getI18n();
        return JOptionPane.showConfirmDialog(this, i18n.t("dictionaries.delete_confirm", code),
                i18n.t("common.confirm"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private void openTargetDialog(TargetGroup existing) {
        I18nManager i18n = ctx.getI18n();
        JTextField codeF = new JTextField(existing != null ? existing.code() : "", 14);
        JTextField enF = new JTextField(existing != null ? existing.nameEn() : "", 20);
        JTextField huF = new JTextField(existing != null ? existing.nameHu() : "", 20);
        JCheckBox activeBox = new JCheckBox(i18n.t("common.activate"), existing == null || existing.active());

        JPanel form = buildBaseForm(codeF, enF, huF, existing != null ? activeBox : null);
        if (JOptionPane.showConfirmDialog(this, form, i18n.t("dictionaries.tab.targets"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            String code = validateCode(codeF.getText());
            if (existing == null) {
                ctx.getDictionaryDao().insertTargetGroup(code, requireText(enF), requireText(huF));
            } else {
                ctx.getDictionaryDao().updateTargetGroup(existing.id(), code, requireText(enF), requireText(huF), activeBox.isSelected());
            }
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openGarmentDialog(GarmentType existing) {
        I18nManager i18n = ctx.getI18n();
        BigDecimal fxRate = ctx.getSettingsDao().getEurToHufRate();
        JTextField codeF = new JTextField(existing != null ? existing.code() : "", 14);
        JTextField enF = new JTextField(existing != null ? existing.nameEn() : "", 20);
        JTextField huF = new JTextField(existing != null ? existing.nameHu() : "", 20);
        JTextField eurF = new JTextField(existing != null ? CurrencyCode.EUR.formatPlain(existing.priceEurPerKg()) : "5.00", 12);
        JTextField hufF = new JTextField(existing != null ? CurrencyCode.HUF.formatPlain(existing.priceHufPerKg())
                : CurrencyCode.HUF.formatPlain(new BigDecimal("5.00").multiply(fxRate)), 12);
        JCheckBox activeBox = new JCheckBox(i18n.t("common.activate"), existing == null || existing.active());

        JButton convertBtn = new JButton(i18n.t("currency.convert_btn") + " (1 EUR = " + CurrencyCode.EUR.formatPlain(fxRate) + " HUF)");
        BrutalistStyle.styleSecondaryButton(convertBtn);
        convertBtn.addActionListener(e -> convertEurHufFields(eurF, hufF, fxRate));

        JPanel form = buildBaseForm(codeF, enF, huF, null);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 4; form.add(new JLabel(i18n.t("currency.price_eur_kg")), gbc);
        gbc.gridx = 1; form.add(eurF, gbc);
        gbc.gridx = 0; gbc.gridy = 5; form.add(new JLabel(i18n.t("currency.price_huf_kg")), gbc);
        gbc.gridx = 1; form.add(hufF, gbc);
        gbc.gridx = 1; gbc.gridy = 6; form.add(convertBtn, gbc);
        if (existing != null) {
            gbc.gridx = 1; gbc.gridy = 7; form.add(activeBox, gbc);
        }

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("dictionaries.tab.garments"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            String code = validateCode(codeF.getText());
            BigDecimal priceEur = CurrencyCode.EUR.round(CurrencyCode.parseAmount(eurF.getText()));
            BigDecimal priceHuf = CurrencyCode.HUF.round(CurrencyCode.parseAmount(hufF.getText()));
            if (priceEur.compareTo(BigDecimal.ZERO) < 0 || priceHuf.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(i18n.t("validation.prices_non_negative"));
            }
            if (existing == null) {
                ctx.getDictionaryDao().insertGarmentType(code, requireText(enF), requireText(huF), priceEur, priceHuf);
            } else {
                ctx.getDictionaryDao().updateGarmentType(existing.id(), code, requireText(enF), requireText(huF),
                        priceEur, priceHuf, activeBox.isSelected());
            }
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openQualityDialog(QualityGrade existing) {
        I18nManager i18n = ctx.getI18n();
        BigDecimal fxRate = ctx.getSettingsDao().getEurToHufRate();
        JTextField codeF = new JTextField(existing != null ? existing.code() : "", 14);
        JTextField enF = new JTextField(existing != null ? existing.nameEn() : "", 20);
        JTextField huF = new JTextField(existing != null ? existing.nameHu() : "", 20);
        JTextField eurF = new JTextField(existing != null ? CurrencyCode.EUR.formatPlain(existing.defaultPricePerKg()) : "5.00", 12);
        JTextField hufF = new JTextField(existing != null ? CurrencyCode.HUF.formatPlain(existing.defaultPriceHufPerKg())
                : CurrencyCode.HUF.formatPlain(new BigDecimal("5.00").multiply(fxRate)), 12);
        JCheckBox activeBox = new JCheckBox(i18n.t("common.activate"), existing == null || existing.active());

        JButton convertBtn = new JButton(i18n.t("currency.convert_btn") + " (1 EUR = " + CurrencyCode.EUR.formatPlain(fxRate) + " HUF)");
        BrutalistStyle.styleSecondaryButton(convertBtn);
        convertBtn.addActionListener(e -> convertEurHufFields(eurF, hufF, fxRate));

        JPanel form = buildBaseForm(codeF, enF, huF, null);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 4; form.add(new JLabel(i18n.t("currency.price_eur_kg")), gbc);
        gbc.gridx = 1; form.add(eurF, gbc);
        gbc.gridx = 0; gbc.gridy = 5; form.add(new JLabel(i18n.t("currency.price_huf_kg")), gbc);
        gbc.gridx = 1; form.add(hufF, gbc);
        gbc.gridx = 1; gbc.gridy = 6; form.add(convertBtn, gbc);
        if (existing != null) {
            gbc.gridx = 1; gbc.gridy = 7; form.add(activeBox, gbc);
        }

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("dictionaries.tab.qualities"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            String code = validateCode(codeF.getText());
            BigDecimal priceEur = CurrencyCode.EUR.round(CurrencyCode.parseAmount(eurF.getText()));
            BigDecimal priceHuf = CurrencyCode.HUF.round(CurrencyCode.parseAmount(hufF.getText()));
            if (priceEur.compareTo(BigDecimal.ZERO) < 0 || priceHuf.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(i18n.t("validation.prices_non_negative"));
            }
            if (existing == null) {
                ctx.getDictionaryDao().insertQualityGrade(code, requireText(enF), requireText(huF), priceEur, priceHuf);
            } else {
                ctx.getDictionaryDao().updateQualityGrade(existing.id(), code, requireText(enF), requireText(huF),
                        priceEur, priceHuf, activeBox.isSelected());
            }
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void convertEurHufFields(JTextField eurF, JTextField hufF, BigDecimal fxRate) {
        try {
            String eurTxt = eurF.getText().trim();
            if (!eurTxt.isEmpty()) {
                BigDecimal eur = CurrencyCode.parseAmount(eurTxt);
                hufF.setText(CurrencyCode.HUF.formatPlain(CurrencyCode.EUR.convertTo(eur, CurrencyCode.HUF, fxRate)));
                return;
            }
            String hufTxt = hufF.getText().trim();
            if (!hufTxt.isEmpty()) {
                BigDecimal huf = CurrencyCode.parseAmount(hufTxt);
                eurF.setText(CurrencyCode.EUR.formatPlain(CurrencyCode.HUF.convertTo(huf, CurrencyCode.EUR, fxRate)));
            }
        } catch (Exception ignored) {
            // Ignore malformed intermediate numbers while typing
        }
    }

    private void openPackageTypeDialog(PackageType existing) {
        I18nManager i18n = ctx.getI18n();
        JTextField codeF = new JTextField(existing != null ? existing.code() : "", 14);
        JTextField enF = new JTextField(existing != null ? existing.nameEn() : "", 20);
        JTextField huF = new JTextField(existing != null ? existing.nameHu() : "", 20);
        JTextField tareF = new JTextField(existing != null ? existing.tareWeightKg().toPlainString() : "0.50", 12);
        JTextField maxF = new JTextField(existing != null ? existing.maxWeightKg().toPlainString() : "50", 12);
        JCheckBox activeBox = new JCheckBox(i18n.t("common.activate"), existing == null || existing.active());

        JPanel form = buildBaseForm(codeF, enF, huF, null);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 4; form.add(new JLabel(i18n.t("dictionaries.tare_weight")), gbc);
        gbc.gridx = 1; form.add(tareF, gbc);
        gbc.gridx = 0; gbc.gridy = 5; form.add(new JLabel(i18n.t("dictionaries.max_weight")), gbc);
        gbc.gridx = 1; form.add(maxF, gbc);
        if (existing != null) {
            gbc.gridx = 1; gbc.gridy = 6; form.add(activeBox, gbc);
        }

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("dictionaries.tab.packages"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            String code = validateCode(codeF.getText());
            BigDecimal tare = new BigDecimal(tareF.getText().trim().replace(',', '.'));
            BigDecimal maxW = new BigDecimal(maxF.getText().trim().replace(',', '.'));
            if (tare.compareTo(BigDecimal.ZERO) < 0 || maxW.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(i18n.t("validation.invalid_package_weights"));
            }
            if (existing == null) {
                ctx.getDictionaryDao().insertPackageType(code, requireText(enF), requireText(huF), tare, maxW);
            } else {
                ctx.getDictionaryDao().updatePackageType(existing.id(), code, requireText(enF), requireText(huF), tare, maxW, activeBox.isSelected());
            }
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private JPanel buildBaseForm(JTextField codeF, JTextField enF, JTextField huF, JCheckBox activeBox) {
        I18nManager i18n = ctx.getI18n();
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; form.add(new JLabel(i18n.t("common.code") + " *"), gbc);
        gbc.gridx = 1; form.add(codeF, gbc);
        gbc.gridx = 1; gbc.gridy = 1;
        JLabel hint = new JLabel(i18n.t("dictionaries.code_hint"));
        hint.setFont(hint.getFont().deriveFont(11f));
        form.add(hint, gbc);

        gbc.gridx = 0; gbc.gridy = 2; form.add(new JLabel(i18n.t("common.name.en") + " *"), gbc);
        gbc.gridx = 1; form.add(enF, gbc);

        gbc.gridx = 0; gbc.gridy = 3; form.add(new JLabel(i18n.t("common.name.hu") + " *"), gbc);
        gbc.gridx = 1; form.add(huF, gbc);

        if (activeBox != null) {
            gbc.gridx = 1; gbc.gridy = 4; form.add(activeBox, gbc);
        }
        return form;
    }

    private String validateCode(String raw) {
        String code = ctx.getProductCodeService().normalizeCodeSegment(raw);
        if (!ctx.getProductCodeService().isValidCodeSegment(code)) {
            throw new IllegalArgumentException(ctx.getI18n().t("dictionaries.code_hint"));
        }
        return code;
    }

    private String requireText(JTextField field) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(ctx.getI18n().t("validation.names_bilingual_required"));
        }
        return text;
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_DICTIONARIES;
    }

    @Override
    public String getTitleI18nKey() {
        return "dictionaries.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        I18nManager i18n = ctx.getI18n();
        String yes = i18n.t("common.yes");
        String no = i18n.t("common.no");

        targets = ctx.getDictionaryDao().findTargetGroups(false);
        targetModel.setRowCount(0);
        for (TargetGroup g : targets) {
            targetModel.addRow(new Object[]{g.code(), g.nameEn(), g.nameHu(), g.active() ? yes : no});
        }

        garments = ctx.getDictionaryDao().findGarmentTypes(false);
        garmentModel.setRowCount(0);
        for (GarmentType g : garments) {
            garmentModel.addRow(new Object[]{
                    g.code(), g.nameEn(), g.nameHu(),
                    CurrencyCode.EUR.format(g.priceEurPerKg()),
                    CurrencyCode.HUF.format(g.priceHufPerKg()),
                    g.active() ? yes : no
            });
        }

        qualities = ctx.getDictionaryDao().findQualityGrades(false);
        qualityModel.setRowCount(0);
        for (QualityGrade q : qualities) {
            qualityModel.addRow(new Object[]{
                    q.code(), q.nameEn(), q.nameHu(),
                    CurrencyCode.EUR.format(q.defaultPricePerKg()),
                    CurrencyCode.HUF.format(q.defaultPriceHufPerKg()),
                    q.active() ? yes : no
            });
        }

        packages = ctx.getDictionaryDao().findPackageTypes(false);
        packageModel.setRowCount(0);
        for (PackageType p : packages) {
            packageModel.addRow(new Object[]{p.code(), p.nameEn(), p.nameHu(), p.tareWeightKg(), p.maxWeightKg(), p.active() ? yes : no});
        }
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        addBtn.setText(i18n.t("common.add"));
        editBtn.setText(i18n.t("common.edit"));
        toggleActiveBtn.setText(i18n.t("common.activate") + " / " + i18n.t("common.deactivate"));
        deleteBtn.setText(i18n.t("common.delete"));

        sections.applyTranslations();

        Object[] baseCols = new Object[]{i18n.t("common.code"), i18n.t("common.name.en"), i18n.t("common.name.hu"), i18n.t("common.status")};
        Object[] pricedCols = new Object[]{
                i18n.t("common.code"), i18n.t("common.name.en"), i18n.t("common.name.hu"),
                i18n.t("currency.price_eur_kg"), i18n.t("currency.price_huf_kg"), i18n.t("common.status")
        };
        targetModel.setColumnIdentifiers(baseCols);
        garmentModel.setColumnIdentifiers(pricedCols);
        qualityModel.setColumnIdentifiers(pricedCols);
        packageModel.setColumnIdentifiers(new Object[]{
                i18n.t("common.code"), i18n.t("common.name.en"), i18n.t("common.name.hu"),
                i18n.t("dictionaries.tare_weight"), i18n.t("dictionaries.max_weight"), i18n.t("common.status")
        });

        refreshData();
        applyTheme();
    }

    @Override
    public void applyTheme() {
        sections.applyTheme();
        BrutalistStyle.stylePrimaryButton(addBtn);
        BrutalistStyle.styleSecondaryButtons(editBtn, toggleActiveBtn, deleteBtn);
        BrutalistStyle.styleTable(targetTable);
        BrutalistStyle.styleTable(garmentTable);
        BrutalistStyle.styleTable(qualityTable);
        BrutalistStyle.styleTable(packageTable);
    }
}
