package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CurrencyCode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.math.BigDecimal;

/**
 * Modal quick-add dialog invoked by the inline '+' buttons on the Package Intake form,
 * letting users create new Target Groups, Product/Garment Types, Quality Grades, or Package Types
 * directly in the app while enforcing the QR code segment convention.
 */
public final class QuickAddDictionaryDialog extends JDialog {

    public enum DictionaryKind {
        TARGET_GROUP("intake.target_group"),
        GARMENT_TYPE("intake.garment_type"),
        QUALITY_GRADE("intake.quality_grade"),
        PACKAGE_TYPE("intake.package_type");

        private final String labelKey;

        DictionaryKind(String labelKey) {
            this.labelKey = labelKey;
        }

        public String getLabelKey() {
            return labelKey;
        }
    }

    private final AppContext ctx;
    private final DictionaryKind kind;

    private final JTextField codeField = new JTextField(14);
    private final JTextField nameEnField = new JTextField(20);
    private final JTextField nameHuField = new JTextField(20);
    private final JTextField priceEurField = new JTextField("5.00", 12);
    private final JTextField priceHufField = new JTextField("2000", 12);
    private final JTextField tareField = new JTextField("0.50", 12);
    private final JTextField maxWeightField = new JTextField("50", 12);

    private Long createdId;

    private QuickAddDictionaryDialog(Window owner, AppContext ctx, DictionaryKind kind) {
        super(owner, ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        this.kind = kind;

        I18nManager i18n = ctx.getI18n();
        setTitle(i18n.t("intake.quick_add.title", i18n.t(kind.getLabelKey())));
        setResizable(false);
        buildUi();
        BrutalistStyle.installEscapeToClose(this);
        pack();
        setLocationRelativeTo(owner);
    }

    public static Long showDialog(Window owner, AppContext ctx, DictionaryKind kind) {
        QuickAddDictionaryDialog dialog = new QuickAddDictionaryDialog(owner, ctx, kind);
        dialog.setVisible(true);
        return dialog.createdId;
    }

    private void buildUi() {
        I18nManager i18n = ctx.getI18n();
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        addFieldRow(form, gbc, row++, i18n.t("common.code") + " *", codeField);

        gbc.gridx = 1;
        gbc.gridy = row++;
        JLabel hint = new JLabel(i18n.t("dictionaries.code_hint"));
        hint.setFont(hint.getFont().deriveFont(11f));
        form.add(hint, gbc);

        addFieldRow(form, gbc, row++, i18n.t("common.name.en") + " *", nameEnField);
        addFieldRow(form, gbc, row++, i18n.t("common.name.hu") + " *", nameHuField);

        if (kind == DictionaryKind.GARMENT_TYPE || kind == DictionaryKind.QUALITY_GRADE) {
            BigDecimal fx = ctx.getSettingsDao().getEurToHufRate();
            priceHufField.setText(CurrencyCode.HUF.formatPlain(new BigDecimal("5.00").multiply(fx)));
            addFieldRow(form, gbc, row++, i18n.t("currency.price_eur_kg"), priceEurField);
            addFieldRow(form, gbc, row++, i18n.t("currency.price_huf_kg"), priceHufField);
        } else if (kind == DictionaryKind.PACKAGE_TYPE) {
            addFieldRow(form, gbc, row++, i18n.t("dictionaries.tare_weight"), tareField);
            addFieldRow(form, gbc, row++, i18n.t("dictionaries.max_weight"), maxWeightField);
        }

        root.add(form, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton cancelBtn = new JButton(i18n.t("common.cancel"));
        BrutalistStyle.styleSecondaryButton(cancelBtn);
        cancelBtn.addActionListener(e -> dispose());
        JButton saveBtn = new JButton(i18n.t("common.save"));
        BrutalistStyle.stylePrimaryButton(saveBtn);
        saveBtn.addActionListener(e -> saveAndClose());
        buttons.add(cancelBtn);
        buttons.add(saveBtn);
        root.add(buttons, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().setDefaultButton(saveBtn);
    }

    private void addFieldRow(JPanel form, GridBagConstraints gbc, int row, String label, JTextField field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        form.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        form.add(field, gbc);
    }

    private void saveAndClose() {
        I18nManager i18n = ctx.getI18n();
        String code = ctx.getProductCodeService().normalizeCodeSegment(codeField.getText());
        String nameEn = nameEnField.getText().trim();
        String nameHu = nameHuField.getText().trim();

        if (!ctx.getProductCodeService().isValidCodeSegment(code)) {
            JOptionPane.showMessageDialog(this, i18n.t("dictionaries.code_hint"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (nameEn.isEmpty() || nameHu.isEmpty()) {
            JOptionPane.showMessageDialog(this, i18n.t("intake.validation.select_all"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            createdId = switch (kind) {
                case TARGET_GROUP -> ctx.getDictionaryDao().insertTargetGroup(code, nameEn, nameHu);
                case GARMENT_TYPE -> {
                    BigDecimal eur = CurrencyCode.EUR.round(CurrencyCode.parseAmount(priceEurField.getText()));
                    BigDecimal huf = CurrencyCode.HUF.round(CurrencyCode.parseAmount(priceHufField.getText()));
                    if (eur.compareTo(BigDecimal.ZERO) < 0 || huf.compareTo(BigDecimal.ZERO) < 0) {
                        throw new IllegalArgumentException(i18n.t("validation.prices_non_negative"));
                    }
                    yield ctx.getDictionaryDao().insertGarmentType(code, nameEn, nameHu, eur, huf);
                }
                case QUALITY_GRADE -> {
                    BigDecimal eur = CurrencyCode.EUR.round(CurrencyCode.parseAmount(priceEurField.getText()));
                    BigDecimal huf = CurrencyCode.HUF.round(CurrencyCode.parseAmount(priceHufField.getText()));
                    if (eur.compareTo(BigDecimal.ZERO) < 0 || huf.compareTo(BigDecimal.ZERO) < 0) {
                        throw new IllegalArgumentException(i18n.t("validation.prices_non_negative"));
                    }
                    yield ctx.getDictionaryDao().insertQualityGrade(code, nameEn, nameHu, eur, huf);
                }
                case PACKAGE_TYPE -> {
                    BigDecimal tare = new BigDecimal(tareField.getText().trim().replace(',', '.'));
                    BigDecimal maxW = new BigDecimal(maxWeightField.getText().trim().replace(',', '.'));
                    if (tare.compareTo(BigDecimal.ZERO) < 0 || maxW.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new IllegalArgumentException(i18n.t("validation.invalid_package_weights"));
                    }
                    yield ctx.getDictionaryDao().insertPackageType(code, nameEn, nameHu, tare, maxW);
                }
            };
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }
}
