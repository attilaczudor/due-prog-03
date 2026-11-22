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
import hu.due.rewear.service.InvoiceService;
import hu.due.rewear.util.FormatUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Factory for the Outgoing Invoice (revenue) and Incoming Expense creation modals used by {@link InvoicesView}.
 * Supports order auto-fill (with country-based VAT lookup), saved provider auto-fill,
 * and multi-line manual item entry with live Net / VAT / Gross total calculation.
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

    /** Encapsulates the interactive multi-line items editor table + live totals footer. */
    private static final class LineItemsEditor {
        final JPanel panel = new JPanel(new BorderLayout(0, 6));
        final DefaultTableModel model;
        final JTable table;
        final JButton addLineBtn;
        final JButton removeLineBtn;
        final JLabel totalsLabel = new JLabel(" ");
        private boolean locked = false;

        LineItemsEditor(I18nManager i18n, String defaultUnit, Runnable onChange) {
            this.model = new DefaultTableModel(new Object[]{
                    i18n.t("invoice.item.description") + " *",
                    i18n.t("invoice.item.qty") + " *",
                    i18n.t("invoice.item.unit"),
                    i18n.t("invoice.item.unit_price") + " *"
            }, 0) {
                @Override
                public boolean isCellEditable(int row, int col) {
                    return !locked;
                }
            };
            this.table = new JTable(model);
            BrutalistStyle.styleTable(table);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            table.getColumnModel().getColumn(0).setPreferredWidth(230);
            table.getColumnModel().getColumn(1).setPreferredWidth(65);
            table.getColumnModel().getColumn(2).setPreferredWidth(55);
            table.getColumnModel().getColumn(3).setPreferredWidth(95);

            addLineBtn = new JButton(i18n.t("invoice.lines.add").toUpperCase(Locale.ROOT));
            removeLineBtn = new JButton(i18n.t("invoice.lines.remove").toUpperCase(Locale.ROOT));
            BrutalistStyle.styleSecondaryButtons(addLineBtn, removeLineBtn);

            addLineBtn.addActionListener(e -> {
                stopEditing();
                model.addRow(new Object[]{"", "1", defaultUnit, "0"});
                int last = model.getRowCount() - 1;
                table.setRowSelectionInterval(last, last);
                onChange.run();
            });
            removeLineBtn.addActionListener(e -> {
                stopEditing();
                int r = table.getSelectedRow();
                if (r >= 0 && model.getRowCount() > 1) {
                    model.removeRow(r);
                    onChange.run();
                }
            });
            model.addTableModelListener(e -> onChange.run());

            // Seed one starter editable row
            model.addRow(new Object[]{"", "1", defaultUnit, "0"});

            JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            topBar.setOpaque(false);
            topBar.add(addLineBtn);
            topBar.add(removeLineBtn);
            panel.add(topBar, BorderLayout.NORTH);

            JScrollPane sp = new JScrollPane(table);
            sp.setPreferredSize(new Dimension(520, 128));
            panel.add(sp, BorderLayout.CENTER);

            totalsLabel.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
            totalsLabel.setForeground(ThemeManager.fg());
            totalsLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
            panel.add(totalsLabel, BorderLayout.SOUTH);
        }

        void setLocked(boolean lock) {
            stopEditing();
            this.locked = lock;
            addLineBtn.setEnabled(!lock);
            removeLineBtn.setEnabled(!lock);
        }

        void stopEditing() {
            if (table.isEditing() && table.getCellEditor() != null) {
                table.getCellEditor().stopCellEditing();
            }
        }

        void updateTotalsPreview(AppContext ctx, I18nManager i18n, String currencyCode, TaxType tax) {
            int dec = ctx.getFinanceConfigDao().decimalPlacesFor(currencyCode);
            BigDecimal net = BigDecimal.ZERO;
            for (int i = 0; i < model.getRowCount(); i++) {
                try {
                    BigDecimal qty = CurrencyCode.parseAmount(strAt(i, 1));
                    BigDecimal price = CurrencyCode.parseAmount(strAt(i, 3));
                    if (qty.compareTo(BigDecimal.ZERO) > 0 && price.compareTo(BigDecimal.ZERO) >= 0) {
                        net = net.add(CurrencyDef.roundForScale(qty.multiply(price), dec));
                    }
                } catch (Exception ignored) {
                    // Ignore incomplete numbers while typing
                }
            }
            net = CurrencyDef.roundForScale(net, dec);
            BigDecimal vatRate = tax != null ? tax.ratePercent() : InvoiceService.DEFAULT_VAT_RATE;
            BigDecimal vat = CurrencyDef.roundForScale(
                    net.multiply(vatRate).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP), dec);
            BigDecimal gross = CurrencyDef.roundForScale(net.add(vat), dec);
            totalsLabel.setText(
                    i18n.t("invoice.net_amount").toUpperCase(Locale.ROOT) + ": " + CurrencyDef.formatAmount(net, currencyCode, dec)
                            + "   |   " + i18n.t("invoice.vat").toUpperCase(Locale.ROOT) + " (" + FormatUtil.stripZeros(vatRate) + "%): "
                            + CurrencyDef.formatAmount(vat, currencyCode, dec)
                            + "   |   " + i18n.t("invoice.gross_amount").toUpperCase(Locale.ROOT) + ": "
                            + CurrencyDef.formatAmount(gross, currencyCode, dec));
        }

        List<InvoiceService.InvoiceLineInput> parseLines(I18nManager i18n) {
            stopEditing();
            List<InvoiceService.InvoiceLineInput> result = new ArrayList<>();
            for (int i = 0; i < model.getRowCount(); i++) {
                String desc = strAt(i, 0).trim();
                String qtyStr = strAt(i, 1).trim();
                String unit = strAt(i, 2).trim();
                String priceStr = strAt(i, 3).trim();
                if (desc.isEmpty() && (priceStr.isEmpty() || "0".equals(priceStr)) && model.getRowCount() > 1) {
                    continue;
                }
                BigDecimal qty = CurrencyCode.parseAmount(qtyStr);
                BigDecimal price = CurrencyCode.parseAmount(priceStr);
                if (price.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException(i18n.t("validation.prices_non_negative"));
                }
                result.add(new InvoiceService.InvoiceLineInput(desc, qty, unit.isEmpty() ? "db" : unit, price));
            }
            return result;
        }

        private String strAt(int row, int col) {
            Object v = model.getValueAt(row, col);
            return v == null ? "" : v.toString();
        }
    }

    private InvoiceFormDialogs() {
    }

    /** Opens the modal for creating a new Outgoing (revenue) invoice from an order or manually (multi-line). */
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
        JTextField[] autoFields = {partnerF, taxF, addressF, emailF};

        final LineItemsEditor[] editorRef = new LineItemsEditor[1];
        Runnable recalc = () -> {
            if (editorRef[0] == null) return;
            String c = curCombo.getSelectedItem() != null ? curCombo.getSelectedItem().toString() : initCur;
            TaxType t = taxCombo.getSelectedItem() instanceof TaxChoice tc ? tc.tax() : null;
            editorRef[0].updateTotalsPreview(ctx, i18n, c, t);
        };
        LineItemsEditor lineEditor = new LineItemsEditor(i18n, "kg", recalc);
        editorRef[0] = lineEditor;
        curCombo.addActionListener(e -> recalc.run());
        taxCombo.addActionListener(e -> recalc.run());

        // Auto-populate customer, line items, currency, and country VAT when an order is selected
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

                lineEditor.setLocked(true);
                lineEditor.model.setRowCount(0);
                var ordItems = ctx.getOrderItemDao().findByOrderId(ord.id());
                for (var item : ordItems) {
                    lineEditor.model.addRow(new Object[]{
                            item.packageQrCode() + " (" + item.localizedSummary(lang) + ")",
                            FormatUtil.stripZeros(item.weightKg()),
                            "kg",
                            CurrencyDef.roundForScale(item.pricePerKg(), dec).toPlainString()
                    });
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
                recalc.run();
            } else {
                curCombo.setEnabled(true);
                curCombo.setSelectedItem(initCur);
                selectTaxInCombo(taxCombo, ctx.getFinanceConfigDao().resolveDefaultTaxTypeForCountry("HU"));
                partnerF.setText("");
                taxF.setText("");
                addressF.setText("");
                emailF.setText("");
                for (JTextField f : autoFields) {
                    f.setEditable(true);
                    f.setBackground(ThemeManager.bg());
                    f.setForeground(ThemeManager.fg());
                }
                lineEditor.setLocked(false);
                lineEditor.model.setRowCount(0);
                lineEditor.model.addRow(new Object[]{"", "1", "kg", "0"});
                recalc.run();
            }
        });

        JPanel root = new JPanel(new BorderLayout(0, 10));
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
        BrutalistStyle.addFormRow(form, gbc, r, i18n.t("invoice.tax_type") + " *:", taxCombo);

        root.add(form, BorderLayout.NORTH);
        root.add(lineEditor.panel, BorderLayout.CENTER);
        recalc.run();

        if (JOptionPane.showConfirmDialog(parent, root, i18n.t("invoice.new_outgoing"),
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
                List<InvoiceService.InvoiceLineInput> lines = lineEditor.parseLines(i18n);
                created = ctx.getInvoiceService().createMultiLineManualInvoice(
                        InvoiceDirection.OUTGOING, InvoiceCategory.WHOLESALE_SALES, null, null,
                        partnerF.getText(), taxF.getText(), addressF.getText(), emailF.getText(),
                        lines, chosenTax, chosenCur,
                        LocalDate.now(), LocalDate.now().plusDays(14), InvoiceStatus.ISSUED, userId);
            }
            return Optional.of(created);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            return Optional.empty();
        }
    }

    /** Opens the modal for recording a new multi-line Incoming (expense/supplier) invoice. */
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
            if (cat.defaultDirection() == InvoiceDirection.INCOMING && cat != InvoiceCategory.CREDIT_NOTE) {
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

        final LineItemsEditor[] editorRef = new LineItemsEditor[1];
        Runnable recalc = () -> {
            if (editorRef[0] == null) return;
            String c = curCombo.getSelectedItem() != null ? curCombo.getSelectedItem().toString() : initCur;
            TaxType t = taxCombo.getSelectedItem() instanceof TaxChoice tc ? tc.tax() : null;
            editorRef[0].updateTotalsPreview(ctx, i18n, c, t);
        };
        LineItemsEditor lineEditor = new LineItemsEditor(i18n, "db", recalc);
        editorRef[0] = lineEditor;
        curCombo.addActionListener(e -> recalc.run());
        taxCombo.addActionListener(e -> recalc.run());

        // Auto-fill supplier identity, preferred currency, and country VAT rule when picking a saved provider
        supplierPicker.addActionListener(e -> {
            if (supplierPicker.getSelectedItem() instanceof Customer sup) {
                partnerF.setText(sup.name());
                taxF.setText(FormatUtil.safe(sup.taxNumber()));
                addressF.setText(FormatUtil.safe(sup.address()));
                emailF.setText(FormatUtil.safe(sup.email()));
                curCombo.setSelectedItem(sup.effectiveCurrencyCode());
                selectTaxInCombo(taxCombo, ctx.getInvoiceService().resolveDefaultTaxForCustomer(sup));
                recalc.run();
            }
        });

        JComboBox<StatusChoice> initStatusCombo = new JComboBox<>();
        for (InvoiceStatus st : List.of(InvoiceStatus.ISSUED, InvoiceStatus.PAID, InvoiceStatus.DRAFT)) {
            initStatusCombo.addItem(new StatusChoice(st, i18n.t("status.invoice." + st.name())));
        }

        JPanel root = new JPanel(new BorderLayout(0, 10));
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
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("invoice.tax_type") + " *:", taxCombo);
        BrutalistStyle.addFormRow(form, gbc, r, i18n.t("common.status") + ":", initStatusCombo);

        root.add(form, BorderLayout.NORTH);
        root.add(lineEditor.panel, BorderLayout.CENTER);
        recalc.run();

        if (JOptionPane.showConfirmDialog(parent, root, i18n.t("invoice.new_incoming"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return Optional.empty();
        }

        try {
            CategoryChoice catItem = (CategoryChoice) catCombo.getSelectedItem();
            StatusChoice stChoice = (StatusChoice) initStatusCombo.getSelectedItem();
            TaxType chosenTax = taxCombo.getSelectedItem() instanceof TaxChoice tc ? tc.tax() : null;
            String chosenCur = curCombo.getSelectedItem() != null ? curCombo.getSelectedItem().toString() : "EUR";
            Long partnerId = supplierPicker.getSelectedItem() instanceof Customer sup ? sup.id() : null;

            List<InvoiceService.InvoiceLineInput> lines = lineEditor.parseLines(i18n);
            Invoice created = ctx.getInvoiceService().createMultiLineManualInvoice(
                    InvoiceDirection.INCOMING,
                    catItem != null ? catItem.category() : InvoiceCategory.OTHER_EXPENSE,
                    invNoF.getText(),
                    partnerId,
                    partnerF.getText(), taxF.getText(), addressF.getText(), emailF.getText(),
                    lines, chosenTax, chosenCur,
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
