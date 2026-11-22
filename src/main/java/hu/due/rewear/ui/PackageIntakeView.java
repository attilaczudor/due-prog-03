package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.GarmentType;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.PackageType;
import hu.due.rewear.model.QualityGrade;
import hu.due.rewear.model.TargetGroup;
import hu.due.rewear.model.WarehouseLocation;
import hu.due.rewear.service.LabelPreset;
import hu.due.rewear.util.FormatUtil;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Package Intake & Live QR Generator screen.
 * Includes inline '+' quick-add buttons so users can create new Target Groups, Product Types,
 * Quality Grades, and Package Types directly in the app and immediately build QR codes from them.
 */
public final class PackageIntakeView extends JPanel implements NavigableView {

    private final AppContext ctx;

    private final JLabel targetLabel = new JLabel();
    private final JLabel garmentLabel = new JLabel();
    private final JLabel qualityLabel = new JLabel();
    private final JLabel packageTypeLabel = new JLabel();
    private final JLabel weightLabel = new JLabel();
    private final JLabel locationLabel = new JLabel();
    private final JLabel quantityLabel = new JLabel();
    private final JLabel notesLabel = new JLabel();

    private final JComboBox<ComboEntry<TargetGroup>> targetCombo = new JComboBox<>();
    private final JComboBox<ComboEntry<GarmentType>> garmentCombo = new JComboBox<>();
    private final JComboBox<ComboEntry<QualityGrade>> qualityCombo = new JComboBox<>();
    private final JComboBox<ComboEntry<PackageType>> packageTypeCombo = new JComboBox<>();
    private final JComboBox<ComboEntry<WarehouseLocation>> locationCombo = new JComboBox<>();

    private final JButton addTargetBtn = new JButton("+");
    private final JButton addGarmentBtn = new JButton("+");
    private final JButton addQualityBtn = new JButton("+");
    private final JButton addPackageTypeBtn = new JButton("+");

    private final JTextField weightField = new JTextField("25", 10);
    private final JSpinner quantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 50, 1));
    private final JTextArea notesArea = new JTextArea(3, 24);
    private final JLabel capacityInfoLabel = new JLabel(" ");

    private final JLabel previewCardTitle = new JLabel();
    private final JLabel generatedCodeHeader = new JLabel();
    private final JLabel generatedCodeValue = new JLabel("—", SwingConstants.CENTER);
    private final JLabel qrImageLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel decodedSummaryLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JButton regenHexBtn = new JButton();

    private final JButton saveAndPrintBtn = new JButton();
    private final JButton saveOnlyBtn = new JButton();

    private JPanel formPanel;
    private JPanel previewCardPanel;

    private String currentHexSuffix;
    private boolean loadingCombos = false;

    private record ComboEntry<T>(T value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public PackageIntakeView(AppContext ctx) {
        this.ctx = ctx;
        this.currentHexSuffix = ctx.getProductCodeService().generateHexSuffix();

        setLayout(new BorderLayout(18, 12));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        buildUi();
        wireEvents();
        refreshData();
        applyTranslations();
    }

    private void buildUi() {
        // Left: Form panel
        JPanel leftPanel = new JPanel(new BorderLayout(0, 12));
        formPanel = new JPanel(new GridBagLayout());
        JPanel form = formPanel;
        BrutalistStyle.applyBoxCard(form, 16, 18);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        addComboWithQuickAdd(form, gbc, row++, targetLabel, targetCombo, addTargetBtn);
        addComboWithQuickAdd(form, gbc, row++, garmentLabel, garmentCombo, addGarmentBtn);
        addComboWithQuickAdd(form, gbc, row++, qualityLabel, qualityCombo, addQualityBtn);
        addComboWithQuickAdd(form, gbc, row++, packageTypeLabel, packageTypeCombo, addPackageTypeBtn);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        form.add(weightLabel, gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        form.add(weightField, gbc);
        gbc.gridwidth = 1;
        row++;

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        form.add(locationLabel, gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        form.add(locationCombo, gbc);
        gbc.gridwidth = 1;
        row++;

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        capacityInfoLabel.setFont(capacityInfoLabel.getFont().deriveFont(11.5f));
        form.add(capacityInfoLabel, gbc);
        gbc.gridwidth = 1;

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        form.add(quantityLabel, gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        form.add(quantitySpinner, gbc);
        gbc.gridwidth = 1;

        leftPanel.add(form, BorderLayout.NORTH);

        JPanel actionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        saveAndPrintBtn.setFont(saveAndPrintBtn.getFont().deriveFont(Font.BOLD, 13.5f));
        saveAndPrintBtn.setPreferredSize(new Dimension(235, 40));
        saveOnlyBtn.setPreferredSize(new Dimension(170, 40));
        actionBar.add(saveAndPrintBtn);
        actionBar.add(saveOnlyBtn);
        leftPanel.add(actionBar, BorderLayout.CENTER);

        // Right: Live QR & Code Preview Card
        previewCardPanel = new JPanel();
        JPanel previewCard = previewCardPanel;
        previewCard.setLayout(new BoxLayout(previewCard, BoxLayout.Y_AXIS));
        previewCard.setPreferredSize(new Dimension(420, 490));
        BrutalistStyle.applyBoxCard(previewCard, 18, 20);

        previewCardTitle.setFont(previewCardTitle.getFont().deriveFont(Font.BOLD, 15f));
        previewCardTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        generatedCodeHeader.setFont(generatedCodeHeader.getFont().deriveFont(Font.PLAIN, 12f));
        generatedCodeHeader.setAlignmentX(Component.CENTER_ALIGNMENT);

        generatedCodeValue.setFont(new Font(Font.MONOSPACED, Font.BOLD, 15));
        generatedCodeValue.setAlignmentX(Component.CENTER_ALIGNMENT);
        generatedCodeValue.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x94A3B8)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));

        qrImageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        qrImageLabel.setPreferredSize(new Dimension(240, 240));
        qrImageLabel.setMinimumSize(new Dimension(240, 240));
        qrImageLabel.setMaximumSize(new Dimension(240, 240));

        decodedSummaryLabel.setFont(decodedSummaryLabel.getFont().deriveFont(Font.PLAIN, 12.5f));
        decodedSummaryLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        regenHexBtn.setAlignmentX(Component.CENTER_ALIGNMENT);

        previewCard.add(previewCardTitle);
        previewCard.add(Box.createVerticalStrut(12));
        previewCard.add(qrImageLabel);
        previewCard.add(Box.createVerticalStrut(14));
        previewCard.add(generatedCodeHeader);
        previewCard.add(Box.createVerticalStrut(6));
        previewCard.add(generatedCodeValue);
        previewCard.add(Box.createVerticalStrut(10));
        previewCard.add(decodedSummaryLabel);
        previewCard.add(Box.createVerticalStrut(14));
        previewCard.add(regenHexBtn);
        previewCard.add(Box.createVerticalGlue());

        add(leftPanel, BorderLayout.CENTER);
        add(previewCard, BorderLayout.EAST);
    }

    private void addComboWithQuickAdd(JPanel form, GridBagConstraints gbc, int row,
                                      JLabel label, JComboBox<?> combo, JButton plusBtn) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        form.add(label, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        form.add(combo, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.0;
        plusBtn.setPreferredSize(new Dimension(38, 28));
        form.add(plusBtn, gbc);
    }

    private void wireEvents() {
        targetCombo.addActionListener(e -> updateLivePreview());
        garmentCombo.addActionListener(e -> updateLivePreview());
        qualityCombo.addActionListener(e -> updateLivePreview());
        packageTypeCombo.addActionListener(e -> updateLivePreview());
        locationCombo.addActionListener(e -> updateCapacityIndicator());
        quantitySpinner.addChangeListener(e -> updateCapacityIndicator());

        weightField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateLivePreview(); updateCapacityIndicator(); }
            @Override public void removeUpdate(DocumentEvent e) { updateLivePreview(); updateCapacityIndicator(); }
            @Override public void changedUpdate(DocumentEvent e) { updateLivePreview(); updateCapacityIndicator(); }
        });

        addTargetBtn.addActionListener(e -> handleQuickAdd(QuickAddDictionaryDialog.DictionaryKind.TARGET_GROUP));
        addGarmentBtn.addActionListener(e -> handleQuickAdd(QuickAddDictionaryDialog.DictionaryKind.GARMENT_TYPE));
        addQualityBtn.addActionListener(e -> handleQuickAdd(QuickAddDictionaryDialog.DictionaryKind.QUALITY_GRADE));
        addPackageTypeBtn.addActionListener(e -> handleQuickAdd(QuickAddDictionaryDialog.DictionaryKind.PACKAGE_TYPE));

        regenHexBtn.addActionListener(e -> {
            currentHexSuffix = ctx.getProductCodeService().generateHexSuffix();
            updateLivePreview();
        });

        saveAndPrintBtn.addActionListener(e -> savePackages(true));
        saveOnlyBtn.addActionListener(e -> savePackages(false));
    }

    private void handleQuickAdd(QuickAddDictionaryDialog.DictionaryKind kind) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        Long createdId = QuickAddDictionaryDialog.showDialog(owner, ctx, kind);
        if (createdId != null) {
            reloadDropdowns(
                    kind == QuickAddDictionaryDialog.DictionaryKind.TARGET_GROUP ? createdId : selectedTargetId(),
                    kind == QuickAddDictionaryDialog.DictionaryKind.GARMENT_TYPE ? createdId : selectedGarmentId(),
                    kind == QuickAddDictionaryDialog.DictionaryKind.QUALITY_GRADE ? createdId : selectedQualityId(),
                    kind == QuickAddDictionaryDialog.DictionaryKind.PACKAGE_TYPE ? createdId : selectedPackageTypeId(),
                    selectedLocationId()
            );
            updateLivePreview();
        }
    }

    private void savePackages(boolean openPrintPreview) {
        I18nManager i18n = ctx.getI18n();
        TargetGroup tg = selectedTarget();
        GarmentType gt = selectedGarment();
        QualityGrade qg = selectedQuality();
        PackageType pt = selectedPackageType();
        WarehouseLocation loc = selectedLocation();

        if (tg == null || gt == null || qg == null || pt == null || loc == null) {
            JOptionPane.showMessageDialog(this, i18n.t("intake.validation.select_all"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        BigDecimal weight = parsePositiveWeight();
        if (weight == null) {
            JOptionPane.showMessageDialog(this, i18n.t("intake.validation.weight"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        int qty = (Integer) quantitySpinner.getValue();
        try {
            List<InventoryPackage> created = ctx.getInventoryService().createPackages(
                    tg, gt, qg, pt, loc, weight, qty,
                    null,
                    currentHexSuffix,
                    ctx.getSession().requireCurrentUser().id()
            );

            // Prepare fresh hex suffix for next entry
            currentHexSuffix = ctx.getProductCodeService().generateHexSuffix();
            quantitySpinner.setValue(1);
            refreshData();

            if (openPrintPreview && !created.isEmpty()) {
                Window owner = SwingUtilities.getWindowAncestor(this);
                LabelPreset defaultPreset = created.size() > 1 ? LabelPreset.A4_4UP : LabelPreset.THERMAL_100X150;
                PrintPreviewDialog.showPreview(owner, ctx, created, defaultPreset);
            } else {
                JOptionPane.showMessageDialog(this, i18n.t("intake.saved_success", created.size()),
                        i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void reloadDropdowns(Long targetId, Long garmentId, Long qualityId, Long packageTypeId, Long locationId) {
        loadingCombos = true;
        try {
            String lang = ctx.getI18n().getActiveLanguage();

            targetCombo.removeAllItems();
            for (TargetGroup item : ctx.getDictionaryDao().findTargetGroups(true)) {
                ComboEntry<TargetGroup> entry = new ComboEntry<>(item, item.displayLabel(lang));
                targetCombo.addItem(entry);
                if (targetId != null && item.id() == targetId) {
                    targetCombo.setSelectedItem(entry);
                }
            }

            garmentCombo.removeAllItems();
            for (GarmentType item : ctx.getDictionaryDao().findGarmentTypes(true)) {
                ComboEntry<GarmentType> entry = new ComboEntry<>(item, item.displayLabel(lang));
                garmentCombo.addItem(entry);
                if (garmentId != null && item.id() == garmentId) {
                    garmentCombo.setSelectedItem(entry);
                }
            }

            qualityCombo.removeAllItems();
            for (QualityGrade item : ctx.getDictionaryDao().findQualityGrades(true)) {
                ComboEntry<QualityGrade> entry = new ComboEntry<>(item, item.displayLabel(lang));
                qualityCombo.addItem(entry);
                if (qualityId != null && item.id() == qualityId) {
                    qualityCombo.setSelectedItem(entry);
                }
            }

            packageTypeCombo.removeAllItems();
            for (PackageType item : ctx.getDictionaryDao().findPackageTypes(true)) {
                ComboEntry<PackageType> entry = new ComboEntry<>(item, item.displayLabel(lang));
                packageTypeCombo.addItem(entry);
                if (packageTypeId != null && item.id() == packageTypeId) {
                    packageTypeCombo.setSelectedItem(entry);
                }
            }

            locationCombo.removeAllItems();
            for (WarehouseLocation item : ctx.getLocationDao().findAll(true)) {
                String label = item.code() + " (" + FormatUtil.stripZeros(item.usedWeightKg()) + " / " + FormatUtil.stripZeros(item.maxCapacityKg()) + " kg)";
                ComboEntry<WarehouseLocation> entry = new ComboEntry<>(item, label);
                locationCombo.addItem(entry);
                if (locationId != null && item.id() == locationId) {
                    locationCombo.setSelectedItem(entry);
                }
            }
        } finally {
            loadingCombos = false;
        }
    }

    private void updateLivePreview() {
        if (loadingCombos) {
            return;
        }
        TargetGroup tg = selectedTarget();
        GarmentType gt = selectedGarment();
        QualityGrade qg = selectedQuality();
        PackageType pt = selectedPackageType();
        BigDecimal weight = parsePositiveWeight();

        if (tg == null || gt == null || qg == null || pt == null || weight == null) {
            generatedCodeValue.setText("—");
            qrImageLabel.setIcon(null);
            decodedSummaryLabel.setText(" ");
            return;
        }

        try {
            String code = ctx.getProductCodeService().buildCode(
                    LocalDateTime.now(), tg.code(), gt.code(), qg.code(), pt.code(), weight, currentHexSuffix
            );
            generatedCodeValue.setText(code);
            BufferedImage qr = ctx.getQrCodeService().generateQrImage(code, 230);
            qrImageLabel.setIcon(new ImageIcon(qr));

            String lang = ctx.getI18n().getActiveLanguage();
            decodedSummaryLabel.setText("<html><center>"
                    + tg.localizedName(lang) + " &bull; " + gt.localizedName(lang) + "<br>"
                    + qg.localizedName(lang) + " &bull; " + pt.localizedName(lang)
                    + " &bull; <b>" + FormatUtil.stripZeros(weight) + " " + ctx.getI18n().t("common.kg") + "</b>"
                    + "</center></html>");
        } catch (Exception ex) {
            generatedCodeValue.setText("—");
            qrImageLabel.setIcon(null);
            decodedSummaryLabel.setText(ex.getMessage());
        }
    }

    private void updateCapacityIndicator() {
        if (loadingCombos) {
            return;
        }
        WarehouseLocation loc = selectedLocation();
        if (loc == null) {
            capacityInfoLabel.setText(" ");
            return;
        }
        I18nManager i18n = ctx.getI18n();
        BigDecimal weight = parsePositiveWeight();
        int qty = (Integer) quantitySpinner.getValue();
        BigDecimal added = weight != null ? weight.multiply(BigDecimal.valueOf(qty)) : BigDecimal.ZERO;
        BigDecimal projected = loc.usedWeightKg().add(added);

        if (projected.compareTo(loc.maxCapacityKg()) > 0) {
            capacityInfoLabel.setForeground(new Color(0xD97706));
            capacityInfoLabel.setText(i18n.t("intake.validation.capacity", FormatUtil.stripZeros(loc.maxCapacityKg())));
        } else {
            capacityInfoLabel.setForeground(new Color(0x059669));
            capacityInfoLabel.setText(i18n.t("intake.capacity_status",
                    FormatUtil.stripZeros(loc.usedWeightKg()), FormatUtil.stripZeros(loc.maxCapacityKg()), FormatUtil.stripZeros(loc.remainingCapacityKg())));
        }
    }

    private BigDecimal parsePositiveWeight() {
        String raw = weightField.getText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal val = new BigDecimal(raw.trim().replace(',', '.'));
            if (val.compareTo(BigDecimal.ZERO) <= 0 || val.scale() > 2) {
                return null;
            }
            return val;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private TargetGroup selectedTarget() {
        Object o = targetCombo.getSelectedItem();
        return o instanceof ComboEntry<?> e ? ((ComboEntry<TargetGroup>) e).value() : null;
    }

    @SuppressWarnings("unchecked")
    private GarmentType selectedGarment() {
        Object o = garmentCombo.getSelectedItem();
        return o instanceof ComboEntry<?> e ? ((ComboEntry<GarmentType>) e).value() : null;
    }

    @SuppressWarnings("unchecked")
    private QualityGrade selectedQuality() {
        Object o = qualityCombo.getSelectedItem();
        return o instanceof ComboEntry<?> e ? ((ComboEntry<QualityGrade>) e).value() : null;
    }

    @SuppressWarnings("unchecked")
    private PackageType selectedPackageType() {
        Object o = packageTypeCombo.getSelectedItem();
        return o instanceof ComboEntry<?> e ? ((ComboEntry<PackageType>) e).value() : null;
    }

    @SuppressWarnings("unchecked")
    private WarehouseLocation selectedLocation() {
        Object o = locationCombo.getSelectedItem();
        return o instanceof ComboEntry<?> e ? ((ComboEntry<WarehouseLocation>) e).value() : null;
    }

    private Long selectedTargetId() { TargetGroup v = selectedTarget(); return v != null ? v.id() : null; }
    private Long selectedGarmentId() { GarmentType v = selectedGarment(); return v != null ? v.id() : null; }
    private Long selectedQualityId() { QualityGrade v = selectedQuality(); return v != null ? v.id() : null; }
    private Long selectedPackageTypeId() { PackageType v = selectedPackageType(); return v != null ? v.id() : null; }
    private Long selectedLocationId() { WarehouseLocation v = selectedLocation(); return v != null ? v.id() : null; }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_INTAKE;
    }

    @Override
    public String getTitleI18nKey() {
        return "intake.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        reloadDropdowns(selectedTargetId(), selectedGarmentId(), selectedQualityId(), selectedPackageTypeId(), selectedLocationId());
        updateLivePreview();
        updateCapacityIndicator();
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        targetLabel.setText(i18n.t("intake.target_group") + ":");
        garmentLabel.setText(i18n.t("intake.garment_type") + ":");
        qualityLabel.setText(i18n.t("intake.quality_grade") + ":");
        packageTypeLabel.setText(i18n.t("intake.package_type") + ":");
        weightLabel.setText(i18n.t("intake.weight_kg") + ":");
        locationLabel.setText(i18n.t("intake.location") + ":");
        quantityLabel.setText(i18n.t("intake.quantity") + ":");
        notesLabel.setText(i18n.t("common.notes") + ":");

        String tooltip = i18n.t("intake.quick_add.tooltip");
        addTargetBtn.setToolTipText(tooltip);
        addGarmentBtn.setToolTipText(tooltip);
        addQualityBtn.setToolTipText(tooltip);
        addPackageTypeBtn.setToolTipText(tooltip);

        previewCardTitle.setText(i18n.t("intake.live_preview"));
        generatedCodeHeader.setText(i18n.t("intake.generated_code") + ":");
        regenHexBtn.setText(i18n.t("intake.regenerate_hex"));
        saveAndPrintBtn.setText(i18n.t("intake.save_and_print"));
        saveOnlyBtn.setText(i18n.t("intake.save_only"));

        refreshData();
        applyTheme();
    }

    @Override
    public void applyTheme() {
        setBackground(ThemeManager.bg());
        if (formPanel != null) {
            BrutalistStyle.applyBoxCard(formPanel, 16, 18);
        }
        if (previewCardPanel != null) {
            BrutalistStyle.applyBoxCard(previewCardPanel, 18, 20);
        }
        BrutalistStyle.stylePrimaryButton(saveAndPrintBtn);
        generatedCodeValue.setForeground(ThemeManager.fg());
        generatedCodeValue.setBorder(BrutalistStyle.boxBorder(8, 12));
        decodedSummaryLabel.setForeground(ThemeManager.fg());
    }
}
