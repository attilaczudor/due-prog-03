package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.TaxType;
import hu.due.rewear.util.FormatUtil;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Factory for the Outgoing Invoice (revenue) and Incoming Expense creation modals used by {@link InvoicesView}.
 * Supports order auto-fill (with country-based VAT lookup), saved provider auto-fill, and manual entry.
 */
final class InvoiceFormDialogs {

    private record TaxChoice(TaxType tax, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record CategoryChoice(InvoiceCategory category, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record StatusChoice(InvoiceStatus status, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private InvoiceFormDialogs() {
    }

    /** Opens the modal for creating a new Outgoing (revenue) invoice from an order or manually. */
    static Optional<Invoice> showNewOutgoingDialog(Component parent, AppContext ctx, String defaultCurrency) {
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();

        List<Order> orders = ctx.getOrderDao().findAll();
        JComboBox<Object> orderPicker = new JComboBox<>();
        orderPicker.addItem(i18n.t("invoice.manual_entry"));
        for (Order o : orders) {
            orderPicker.addItem(o);
        }

        String initCur = defaultCurrency != null ? defaultCurrency : "HUF";
        JComboBox<String> curCombo = buildCurrencyCombo(ctx, initCur);
        JComboBox<TaxChoice> taxCombo = buildTaxTypeCombo(ctx, lang);
        selectTaxInCombo(taxCombo, ctx.getFinanceConfigDao().resolveDefaultTaxTypeForCountry("HU"));

        JTextField partnerF = new JTextField(24);
        JTextField taxF = new JTextField(24);
        JTextField addressF = new JTextField(24);
        JTextField emailF = new JTextField(24);
        JTextField descF = new JTextField(24);
        JTextField qtyF = new JTextField("1", 10);
        JTextField unitF = new JTextField("kg", 8);
        JTextField priceF = new JTextField("0", 10);
        JLabel priceLabel = new JLabel(i18n.t("invoice.item.unit_price") + " (" + curCombo.getSelectedItem() + ") *:");

        curCombo.addActionListener(e -> priceLabel.setText(
                i18n.t("invoice.item.unit_price") + " (" + curCombo.getSelectedItem() + ") *:"));

        JTextField[] autoFields = {partnerF, taxF, addressF, emailF, descF, qtyF, unitF, priceF};

        // Auto-populate customer, line summary, weight, average unit price, currency, and country VAT when an order is selected
        orderPicker.addActionListener(e -> {
            if (orderPicker.getSelectedItem() instanceof Order ord) {
                String ordCur = ord.effectiveCurrencyCode();
                int dec = ctx.getFinanceConfigDao().decimalPlacesFor(ordCur);
                curCombo.setSelectedItem(ordCur);
                curCombo.setEnabled(false);

                Customer cust = ctx.getCustomerDao().findById(ord.customerId()).orElse(null);
                partnerF.setText(cust != null ? cust.name() : ord.customerName());
                taxF.setText(cust != null ? FormatUtil.safe(cust.taxNumber()) : "");
                addressF.setText(cust != null ? FormatUtil.safe(cust.address()) : "");
                emailF.setText(cust != null ? FormatUtil.safe(cust.email()) : "");

                var ordItems = ctx.getOrderItemDao().findByOrderId(ord.id());
                if (ordItems.size() == 1) {
                    var item = ordItems.get(0);
                    descF.setText(item.packageQrCode() + " (" + item.localizedSummary(lang) + ")");
                } else {
                    descF.setText(ord.orderNumber() + " — " + ordItems.size() + " "
                            + ("hu".equalsIgnoreCase(lang) ? "csomag" : "package(s)")
                            + " (" + CurrencyDef.formatAmount(ord.totalPrice(), ordCur, dec) + ")");
                }

                BigDecimal weight = ord.totalWeightKg() != null ? ord.totalWeightKg() : BigDecimal.ZERO;
                qtyF.setText(FormatUtil.stripZeros(weight));
                unitF.setText("kg");

                if (weight.compareTo(BigDecimal.ZERO) > 0 && ord.totalPrice() != null) {
                    BigDecimal avgPerKg = ord.totalPrice().divide(weight, dec, RoundingMode.HALF_UP);
                    priceF.setText(CurrencyDef.roundForScale(avgPerKg, dec).toPlainString());
                } else {
                    priceF.setText(CurrencyDef.roundForScale(BigDecimal.ZERO, dec).toPlainString());
                }

                TaxType autoTax = ctx.getInvoiceDao().findByOrderId(ord.id())
                        .flatMap(inv -> ctx.getFinanceConfigDao().findTaxTypeByCode(inv.effectiveTaxTypeCode()))
                        .orElseGet(() -> ctx.getInvoiceService().resolveDefaultTaxForCustomer(cust));
                selectTaxInCombo(taxCombo, autoTax);

                for (JTextField f : autoFields) {
                    f.setEditable(false);
                    f.setBackground(ThemeManager.surface());
                    f.setForeground(ThemeManager.fg());
                }
            } else {
                curCombo.setEnabled(true);
                curCombo.setSelectedItem(initCur);
                selectTaxInCombo(taxCombo, ctx.getFinanceConfigDao().resolveDefaultTaxTypeForCountry("HU"));
                partnerF.setText("");
                taxF.setText("");
                addressF.setText("");
                emailF.setText("");
                descF.setText("");
                qtyF.setText("1");
                unitF.setText("kg");
                priceF.setText("0");
                for (JTextField f : autoFields) {
                    f.setEditable(true);
                    f.setBackground(ThemeManager.bg());
                    f.setForeground(ThemeManager.fg());
                }
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.from_order") + ":", orderPicker);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("currency.label") + " *:", curCombo);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.partner") + " *:", partnerF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.tax_number") + ":", taxF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.address") + ":", addressF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.email") + ":", emailF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.item.description") + " *:", descF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.item.qty") + " *:", qtyF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.item.unit") + ":", unitF);
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0; form.add(priceLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1; form.add(priceF, gbc);
        r++;
        BrutalistStyle.addFormRow(form, gbc, r, i18n.t("invoice.tax_type") + " *:", taxCombo);

        if (JOptionPane.showConfirmDialog(parent, form, i18n.t("invoice.new_outgoing"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return Optional.empty();
        }

        try {
            TaxType chosenTax = taxCombo.getSelectedItem() instanceof TaxChoice tc ? tc.tax() : null;
            long userId = ctx.getSession().requireCurrentUser().id();
            Invoice created;
            if (orderPicker.getSelectedItem() instanceof Order chosenOrder) {
                created = ctx.getInvoiceService().ensureInvoiceForOrder(chosenOrder.id(), chosenTax, userId);
            } else {
                String chosenCur = curCombo.getSelectedItem() != null ? curCombo.getSelectedItem().toString() : "EUR";
                BigDecimal qty = CurrencyCode.parseAmount(qtyF.getText());
                BigDecimal unitPrice = CurrencyCode.parseAmount(priceF.getText());
                created = ctx.getInvoiceService().createManualInvoice(
                        InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                        partnerF.getText(), taxF.getText(), addressF.getText(), emailF.getText(),
                        descF.getText(), qty, unitF.getText(), unitPrice, chosenTax, chosenCur,
                        LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.ISSUED, userId);
            }
            return Optional.of(created);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            return Optional.empty();
        }
    }

    /** Opens the modal for recording a new Incoming (expense/supplier) invoice. */
    static Optional<Invoice> showNewIncomingDialog(Component parent, AppContext ctx, String defaultCurrency) {
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();

        List<Customer> suppliers = ctx.getCustomerDao().findSuppliers(true);
        JComboBox<Object> supplierPicker = new JComboBox<>();
        supplierPicker.addItem(i18n.t("invoice.manual_entry"));
        for (Customer sup : suppliers) {
            supplierPicker.addItem(sup);
        }

        JComboBox<CategoryChoice> catCombo = new JComboBox<>();
        for (InvoiceCategory cat : InvoiceCategory.values()) {
            if (cat.defaultDirection() == InvoiceDirection.INCOMING) {
                catCombo.addItem(new CategoryChoice(cat, i18n.t("invoice.category." + cat.name())));
            }
        }
        String initCur = defaultCurrency != null ? defaultCurrency : "HUF";
        JComboBox<String> curCombo = buildCurrencyCombo(ctx, initCur);
        JComboBox<TaxChoice> taxCombo = buildTaxTypeCombo(ctx, lang);
        selectTaxInCombo(taxCombo, ctx.getFinanceConfigDao().resolveDefaultTaxTypeForCountry("HU"));

        JTextField invNoF = new JTextField(24);
        JTextField partnerF = new JTextField(24);
        JTextField taxF = new JTextField(24);
        JTextField addressF = new JTextField(24);
        JTextField emailF = new JTextField(24);
        JTextField descF = new JTextField(24);
        JTextField qtyF = new JTextField("1", 10);
        JTextField unitF = new JTextField("db", 8);
        JTextField priceF = new JTextField("0", 10);
        JLabel priceLabel = new JLabel(i18n.t("invoice.item.unit_price") + " (" + curCombo.getSelectedItem() + ") *:");
        curCombo.addActionListener(e -> priceLabel.setText(
                i18n.t("invoice.item.unit_price") + " (" + curCombo.getSelectedItem() + ") *:"));

        // Auto-fill supplier identity, preferred currency, and country VAT rule when picking a saved provider
        supplierPicker.addActionListener(e -> {
            if (supplierPicker.getSelectedItem() instanceof Customer sup) {
                partnerF.setText(sup.name());
                taxF.setText(FormatUtil.safe(sup.taxNumber()));
                addressF.setText(FormatUtil.safe(sup.address()));
                emailF.setText(FormatUtil.safe(sup.email()));
                curCombo.setSelectedItem(sup.effectiveCurrencyCode());
                selectTaxInCombo(taxCombo, ctx.getInvoiceService().resolveDefaultTaxForCustomer(sup));
            }
        });

        JComboBox<StatusChoice> initStatusCombo = new JComboBox<>();
        for (InvoiceStatus st : List.of(InvoiceStatus.ISSUED, InvoiceStatus.PAID, InvoiceStatus.DRAFT)) {
            initStatusCombo.addItem(new StatusChoice(st, i18n.t("status.invoice." + st.name())));
        }

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.from_supplier") + ":", supplierPicker);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.category") + " *:", catCombo);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("currency.label") + " *:", curCombo);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.number_optional") + ":", invNoF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.partner") + " *:", partnerF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.tax_number") + ":", taxF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.address") + ":", addressF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.email") + ":", emailF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.item.description") + " *:", descF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.item.qty") + " *:", qtyF);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.item.unit") + ":", unitF);
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0; form.add(priceLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1; form.add(priceF, gbc);
        r++;
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.tax_type") + " *:", taxCombo);
        BrutalistStyle.addFormRow(form, gbc, r, i18n.t("common.status") + ":", initStatusCombo);

        if (JOptionPane.showConfirmDialog(parent, form, i18n.t("invoice.new_incoming"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return Optional.empty();
        }

        try {
            CategoryChoice catItem = (CategoryChoice) catCombo.getSelectedItem();
            StatusChoice stChoice = (StatusChoice) initStatusCombo.getSelectedItem();
            TaxType chosenTax = taxCombo.getSelectedItem() instanceof TaxChoice tc ? tc.tax() : null;
            String chosenCur = curCombo.getSelectedItem() != null ? curCombo.getSelectedItem().toString() : "EUR";
            Long partnerId = supplierPicker.getSelectedItem() instanceof Customer sup ? sup.id() : null;

            BigDecimal qty = CurrencyCode.parseAmount(qtyF.getText());
            BigDecimal price = CurrencyCode.parseAmount(priceF.getText());
            Invoice created = ctx.getInvoiceService().createManualInvoice(
                    InvoiceDirection.INCOMING,
                    catItem != null ? catItem.category() : InvoiceCategory.OTHER_EXPENSE,
                    invNoF.getText(),
                    partnerId,
                    partnerF.getText(), taxF.getText(), addressF.getText(), emailF.getText(),
                    descF.getText(), qty, unitF.getText(), price, chosenTax, chosenCur,
                    LocalDate.now(), LocalDate.now().plusDays(14),
                    stChoice != null ? stChoice.status() : InvoiceStatus.ISSUED,
                    ctx.getSession().requireCurrentUser().id());
            return Optional.of(created);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            return Optional.empty();
        }
    }

    private static JComboBox<TaxChoice> buildTaxTypeCombo(AppContext ctx, String lang) {
        JComboBox<TaxChoice> combo = new JComboBox<>();
        for (TaxType t : ctx.getFinanceConfigDao().findTaxTypes(true)) {
            combo.addItem(new TaxChoice(t, t.displayLabel(lang)));
        }
        return combo;
    }

    private static void selectTaxInCombo(JComboBox<TaxChoice> combo, TaxType target) {
        if (target == null) {
            return;
        }
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).tax().code().equalsIgnoreCase(target.code())) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    private static JComboBox<String> buildCurrencyCombo(AppContext ctx, String preferred) {
        JComboBox<String> combo = new JComboBox<>();
        for (CurrencyDef c : ctx.getFinanceConfigDao().findCurrencies(true)) {
            combo.addItem(c.code());
        }
        if (preferred != null) {
            combo.setSelectedItem(preferred);
        }
        return combo;
    }
}
