package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.GarmentType;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.PackageType;
import hu.due.rewear.model.QualityGrade;
import hu.due.rewear.model.StockMovement;
import hu.due.rewear.model.TargetGroup;
import hu.due.rewear.model.WarehouseLocation;
import hu.due.rewear.service.LabelPreset;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
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
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Brutalist popup modal opened by double-clicking any product row in Inventory or Locations.
 * Allows editing package details with live QR product code recalculation, movement history view,
 * and optional immediate Ticket Print Preview.
 */
public final class EditPackageDialog extends JDialog {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;
    private final InventoryPackage original;

    private final JComboBox<ComboEntry<TargetGroup>> targetCombo = new JComboBox<>();
    private final JComboBox<ComboEntry<GarmentType>> garmentCombo = new JComboBox<>();
    private final JComboBox<ComboEntry<QualityGrade>> qualityCombo = new JComboBox<>();
    private final JComboBox<ComboEntry<PackageType>> packageTypeCombo = new JComboBox<>();
    private final JComboBox<ComboEntry<WarehouseLocation>> locationCombo = new JComboBox<>();
    private final JComboBox<ComboEntry<PackageStatus>> statusCombo = new JComboBox<>();
    private final JTextField weightField = new JTextField(12);
    private final JTextArea notesArea = new JTextArea(3, 24);

    private final JLabel qrImageLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel liveCodeLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel codeChangedHint = new JLabel(" ", SwingConstants.CENTER);

    private final DefaultTableModel historyModel = BrutalistStyle.nonEditableTableModel();
    private final JTable historyTable = new JTable(historyModel);

    private InventoryPackage updatedPackage;
    private boolean loading = false;

    private record ComboEntry<T>(T value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private EditPackageDialog(Window owner, AppContext ctx, InventoryPackage pkg) {
        super(owner, ctx.getI18n().t("inventory.edit_title") + " — " + pkg.qrCode(), ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        this.original = pkg;

        LogoAssets.applyWindowIcon(this);
        setMinimumSize(new Dimension(880, 620));
        setPreferredSize(new Dimension(920, 660));

        buildUi();
        populateFields();
        wireLivePreview();
        updateLiveQr();

        pack();
        setLocationRelativeTo(owner);
    }

    public static InventoryPackage showDialog(Window owner, AppContext ctx, InventoryPackage pkg) {
        if (pkg == null) {
            return null;
        }
        EditPackageDialog dialog = new EditPackageDialog(owner, ctx, pkg);
        dialog.setVisible(true);
        return dialog.updatedPackage;
    }

    private void buildUi() {
        I18nManager i18n = ctx.getI18n();
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        // Top section: Left edit form + Right live QR preview card
        JPanel topSplit = new JPanel(new BorderLayout(14, 0));

        JPanel form = new JPanel(new GridBagLayout());
        BrutalistStyle.applyBoxCard(form, 12, 14);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        int r = 0;
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("intake.target_group") + ":", targetCombo);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("intake.garment_type") + ":", garmentCombo);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("intake.quality_grade") + ":", qualityCombo);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("intake.package_type") + ":", packageTypeCombo);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("intake.weight_kg") + ":", weightField);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("intake.location") + ":", locationCombo);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("common.status") + ":", statusCombo);

        topSplit.add(form, BorderLayout.CENTER);

        // Right QR Preview Card
        JPanel previewCard = new JPanel();
        previewCard.setLayout(new BoxLayout(previewCard, BoxLayout.Y_AXIS));
        previewCard.setPreferredSize(new Dimension(330, 320));
        BrutalistStyle.applyBoxCard(previewCard, 14, 14);

        JLabel previewHdr = new JLabel(i18n.t("intake.live_preview").toUpperCase());
        previewHdr.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        previewHdr.setAlignmentX(Component.CENTER_ALIGNMENT);

        qrImageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        qrImageLabel.setPreferredSize(new Dimension(180, 180));
        qrImageLabel.setMinimumSize(new Dimension(180, 180));
        qrImageLabel.setMaximumSize(new Dimension(180, 180));

        liveCodeLabel.setFont(ThemeManager.monoFont(Font.BOLD, 12.5f));
        liveCodeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        liveCodeLabel.setBorder(BrutalistStyle.boxBorder(6, 8));

        codeChangedHint.setFont(ThemeManager.monoFont(Font.PLAIN, 10.5f));
        codeChangedHint.setForeground(ThemeManager.muted());
        codeChangedHint.setAlignmentX(Component.CENTER_ALIGNMENT);

        previewCard.add(previewHdr);
        previewCard.add(Box.createVerticalStrut(10));
        previewCard.add(qrImageLabel);
        previewCard.add(Box.createVerticalStrut(10));
        previewCard.add(liveCodeLabel);
        previewCard.add(Box.createVerticalStrut(8));
        previewCard.add(codeChangedHint);
        previewCard.add(Box.createVerticalGlue());

        topSplit.add(previewCard, BorderLayout.EAST);
        root.add(topSplit, BorderLayout.NORTH);

        // Middle: Movement History
        JPanel historyPanel = new JPanel(new BorderLayout(0, 6));
        JLabel histTitle = new JLabel(i18n.t("scanner.history").toUpperCase());
        histTitle.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        historyPanel.add(histTitle, BorderLayout.NORTH);

        historyModel.setColumnIdentifiers(new Object[]{
                i18n.t("common.created_at").toUpperCase(),
                i18n.t("common.actions").toUpperCase(),
                i18n.t("intake.location").toUpperCase(),
                i18n.t("scanner.field.operator").toUpperCase()
        });
        for (StockMovement m : ctx.getMovementDao().findByPackageId(original.id())) {
            String fromTo = (m.fromLocationCode() != null ? m.fromLocationCode() : "—")
                    + " -> " + (m.toLocationCode() != null ? m.toLocationCode() : "—");
            historyModel.addRow(new Object[]{
                    m.createdAt() != null ? DT_FMT.format(m.createdAt()) : "",
                    i18n.t("movement." + m.movementType().name()).toUpperCase(),
                    fromTo,
                    m.userFullName()
            });
        }
        BrutalistStyle.styleTable(historyTable);
        historyPanel.add(new JScrollPane(historyTable), BorderLayout.CENTER);
        root.add(historyPanel, BorderLayout.CENTER);

        // Bottom buttons
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        JButton reprintCurrentBtn = new JButton(i18n.t("scanner.action.reprint").toUpperCase());
        BrutalistStyle.styleSecondaryButton(reprintCurrentBtn);
        reprintCurrentBtn.addActionListener(e ->
                PrintPreviewDialog.showPreview(this, ctx, List.of(original), LabelPreset.THERMAL_100X150));

        JButton saveAndPrintBtn = new JButton(i18n.t("intake.save_and_print").toUpperCase());
        BrutalistStyle.stylePrimaryButton(saveAndPrintBtn);
        saveAndPrintBtn.addActionListener(e -> saveChanges(true));

        JButton saveBtn = new JButton(i18n.t("common.save").toUpperCase());
        BrutalistStyle.styleSecondaryButton(saveBtn);
        saveBtn.addActionListener(e -> saveChanges(false));

        JButton cancelBtn = new JButton(i18n.t("common.cancel").toUpperCase());
        BrutalistStyle.styleSecondaryButton(cancelBtn);
        cancelBtn.addActionListener(e -> dispose());

        buttons.add(reprintCurrentBtn);
        buttons.add(saveAndPrintBtn);
        buttons.add(saveBtn);
        buttons.add(cancelBtn);
        root.add(buttons, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().setDefaultButton(saveBtn);
    }

    private void populateFields() {
        loading = true;
        try {
            I18nManager i18n = ctx.getI18n();
            String lang = i18n.getActiveLanguage();

            for (TargetGroup item : ctx.getDictionaryDao().findTargetGroups(false)) {
                if (item.active() || item.id() == original.targetGroupId()) {
                    ComboEntry<TargetGroup> entry = new ComboEntry<>(item, item.displayLabel(lang));
                    targetCombo.addItem(entry);
                    if (item.id() == original.targetGroupId()) targetCombo.setSelectedItem(entry);
                }
            }
            for (GarmentType item : ctx.getDictionaryDao().findGarmentTypes(false)) {
                if (item.active() || item.id() == original.garmentTypeId()) {
                    ComboEntry<GarmentType> entry = new ComboEntry<>(item, item.displayLabel(lang));
                    garmentCombo.addItem(entry);
                    if (item.id() == original.garmentTypeId()) garmentCombo.setSelectedItem(entry);
                }
            }
            for (QualityGrade item : ctx.getDictionaryDao().findQualityGrades(false)) {
                if (item.active() || item.id() == original.qualityGradeId()) {
                    ComboEntry<QualityGrade> entry = new ComboEntry<>(item, item.displayLabel(lang));
                    qualityCombo.addItem(entry);
                    if (item.id() == original.qualityGradeId()) qualityCombo.setSelectedItem(entry);
                }
            }
            for (PackageType item : ctx.getDictionaryDao().findPackageTypes(false)) {
                if (item.active() || item.id() == original.packageTypeId()) {
                    ComboEntry<PackageType> entry = new ComboEntry<>(item, item.displayLabel(lang));
                    packageTypeCombo.addItem(entry);
                    if (item.id() == original.packageTypeId()) packageTypeCombo.setSelectedItem(entry);
                }
            }
            for (WarehouseLocation loc : ctx.getLocationDao().findAll(false)) {
                if (loc.active() || loc.id() == original.locationId()) {
                    ComboEntry<WarehouseLocation> entry = new ComboEntry<>(loc, loc.code());
                    locationCombo.addItem(entry);
                    if (loc.id() == original.locationId()) locationCombo.setSelectedItem(entry);
                }
            }
            for (PackageStatus st : PackageStatus.values()) {
                ComboEntry<PackageStatus> entry = new ComboEntry<>(st, i18n.t("status.package." + st.name()));
                statusCombo.addItem(entry);
                if (st == original.status()) statusCombo.setSelectedItem(entry);
            }
            statusCombo.setEnabled(ctx.getSession().isManagerOrAdmin());

            BigDecimal w = original.weightKg().stripTrailingZeros();
            weightField.setText((w.scale() < 0 ? w.setScale(0) : w).toPlainString());
        } finally {
            loading = false;
        }
    }

    private void wireLivePreview() {
        targetCombo.addActionListener(e -> updateLiveQr());
        garmentCombo.addActionListener(e -> updateLiveQr());
        qualityCombo.addActionListener(e -> updateLiveQr());
        packageTypeCombo.addActionListener(e -> updateLiveQr());
        weightField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateLiveQr(); }
            @Override public void removeUpdate(DocumentEvent e) { updateLiveQr(); }
            @Override public void changedUpdate(DocumentEvent e) { updateLiveQr(); }
        });
    }

    private void updateLiveQr() {
        if (loading) return;
        TargetGroup tg = selectedValue(targetCombo);
        GarmentType gt = selectedValue(garmentCombo);
        QualityGrade qg = selectedValue(qualityCombo);
        PackageType pt = selectedValue(packageTypeCombo);
        BigDecimal weight = parsePositiveWeight();

        if (tg == null || gt == null || qg == null || pt == null || weight == null) {
            liveCodeLabel.setText("—");
            qrImageLabel.setIcon(null);
            codeChangedHint.setText(" ");
            return;
        }
        try {
            LocalDateTime ts = original.createdAt() != null ? original.createdAt() : LocalDateTime.now();
            String code = ctx.getProductCodeService().buildCode(
                    ts, tg.code(), gt.code(), qg.code(), pt.code(), weight, original.hexSuffix());
            liveCodeLabel.setText(code);
            BufferedImage qr = ctx.getQrCodeService().generateQrImage(code, 175);
            qrImageLabel.setIcon(new ImageIcon(qr));

            if (!code.equalsIgnoreCase(original.qrCode())) {
                codeChangedHint.setText("<html><center>" + ctx.getI18n().t("inventory.edit.code_changed") + "</center></html>");
            } else {
                codeChangedHint.setText(" ");
            }
        } catch (Exception ex) {
            liveCodeLabel.setText("—");
            qrImageLabel.setIcon(null);
            codeChangedHint.setText(ex.getMessage());
        }
    }

    private void saveChanges(boolean openPrintPreview) {
        I18nManager i18n = ctx.getI18n();
        TargetGroup tg = selectedValue(targetCombo);
        GarmentType gt = selectedValue(garmentCombo);
        QualityGrade qg = selectedValue(qualityCombo);
        PackageType pt = selectedValue(packageTypeCombo);
        WarehouseLocation loc = selectedValue(locationCombo);
        PackageStatus st = selectedValue(statusCombo);
        BigDecimal weight = parsePositiveWeight();

        if (tg == null || gt == null || qg == null || pt == null || loc == null || st == null) {
            JOptionPane.showMessageDialog(this, i18n.t("intake.validation.select_all"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (weight == null) {
            JOptionPane.showMessageDialog(this, i18n.t("intake.validation.weight"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            updatedPackage = ctx.getInventoryService().updatePackageDetails(
                    original.id(), tg, gt, qg, pt, loc, weight, st, null,
                    ctx.getSession().requireCurrentUser().id()
            );
            if (openPrintPreview) {
                PrintPreviewDialog.showPreview(this, ctx, List.of(updatedPackage), LabelPreset.THERMAL_100X150);
            }
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private BigDecimal parsePositiveWeight() {
        String raw = weightField.getText();
        if (raw == null || raw.isBlank()) return null;
        try {
            BigDecimal val = new BigDecimal(raw.trim().replace(',', '.'));
            return (val.compareTo(BigDecimal.ZERO) > 0 && val.scale() <= 2) ? val : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T selectedValue(JComboBox<ComboEntry<T>> combo) {
        Object o = combo.getSelectedItem();
        return o instanceof ComboEntry<?> e ? ((ComboEntry<T>) e).value() : null;
    }
}
