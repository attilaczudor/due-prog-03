package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.PackageFilter;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.ParsedProductCode;
import hu.due.rewear.model.StockMovement;
import hu.due.rewear.model.WarehouseLocation;
import hu.due.rewear.service.LabelPreset;
import hu.due.rewear.service.ScannerService;
import hu.due.rewear.util.FormatUtil;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * QR Scanner Station supporting:
 * 1) Real USB/Bluetooth HID keyboard-wedge scanners (auto-focused input + Enter)
 * 2) QR Image scanning (file chooser + drag-and-drop PNG/JPG via ZXing)
 * 3) Demo Quick-Scan picker for classroom presentations
 * plus instant Relocate, Stocktake Verify, Reprint Ticket, and Add-to-Order actions.
 */
public final class QrScannerView extends JPanel implements NavigableView {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;

    private final JLabel inputPromptLabel = new JLabel();
    private final JTextField scanInputField = new JTextField(34);
    private final JButton lookupBtn = new JButton();
    private final JButton scanImageBtn = new JButton();
    private final JComboBox<String> demoQuickPickCombo = new JComboBox<>();
    private final JButton demoScanBtn = new JButton();
    private final JLabel dropHintLabel = new JLabel();

    private final JLabel statusBanner = new JLabel(" ", SwingConstants.LEFT);

    // Product detail card
    private final JLabel qrThumbLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel codeTitleLabel = new JLabel("—");
    private final JLabel statusPillLabel = new JLabel(" ");

    private final JLabel lblTimestamp = new JLabel();
    private final JLabel lblTarget = new JLabel();
    private final JLabel lblGarment = new JLabel();
    private final JLabel lblQuality = new JLabel();
    private final JLabel lblPackageType = new JLabel();
    private final JLabel lblWeight = new JLabel();
    private final JLabel lblHex = new JLabel();
    private final JLabel lblLocation = new JLabel();
    private final JLabel lblOperator = new JLabel();
    private final JLabel lblNotes = new JLabel();

    private final JLabel valTimestamp = new JLabel("—");
    private final JLabel valTarget = new JLabel("—");
    private final JLabel valGarment = new JLabel("—");
    private final JLabel valQuality = new JLabel("—");
    private final JLabel valPackageType = new JLabel("—");
    private final JLabel valWeight = new JLabel("—");
    private final JLabel valHex = new JLabel("—");
    private final JLabel valLocation = new JLabel("—");
    private final JLabel valOperator = new JLabel("—");
    private final JLabel valNotes = new JLabel("—");

    // Post-scan action buttons
    private final JButton relocateBtn = new JButton();
    private final JButton verifyBtn = new JButton();
    private final JButton reprintBtn = new JButton();
    private final JButton addToOrderBtn = new JButton();

    // Movement history table
    private final JLabel historyTitleLabel = new JLabel();
    private final DefaultTableModel historyModel = BrutalistStyle.nonEditableTableModel();
    private final JTable historyTable = new JTable(historyModel);

    private ScannerService.ScanResult lastResult;

    private JPanel scanBoxPanel;
    private JPanel leftCardPanel;
    private JPanel rightPanelBox;

    public QrScannerView(AppContext ctx) {
        this.ctx = ctx;

        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));

        buildUi();
        wireEvents();
        installImageDropHandler();
        refreshData();
        applyTranslations();
        resetToWaitingState();
    }

    private void buildUi() {
        // Top scan strip + status banner
        JPanel topWrapper = new JPanel();
        topWrapper.setLayout(new BoxLayout(topWrapper, BoxLayout.Y_AXIS));

        scanBoxPanel = new JPanel(new BorderLayout(8, 8));
        JPanel scanBox = scanBoxPanel;
        BrutalistStyle.applyBoxCard(scanBox, 12, 14);

        inputPromptLabel.setFont(inputPromptLabel.getFont().deriveFont(Font.BOLD, 13f));
        scanBox.add(inputPromptLabel, BorderLayout.NORTH);

        JPanel inputRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        scanInputField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 15));
        scanInputField.setPreferredSize(new Dimension(380, 34));
        lookupBtn.setPreferredSize(new Dimension(130, 34));
        scanImageBtn.setPreferredSize(new Dimension(205, 34));
        inputRow.add(scanInputField);
        inputRow.add(lookupBtn);
        inputRow.add(scanImageBtn);
        scanBox.add(inputRow, BorderLayout.CENTER);

        JPanel demoRow = new JPanel(new BorderLayout(8, 0));
        dropHintLabel.setFont(dropHintLabel.getFont().deriveFont(Font.ITALIC, 11.5f));
        demoRow.add(dropHintLabel, BorderLayout.WEST);

        JPanel demoRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        demoQuickPickCombo.setPreferredSize(new Dimension(280, 28));
        demoRight.add(demoQuickPickCombo);
        demoRight.add(demoScanBtn);
        demoRow.add(demoRight, BorderLayout.EAST);
        scanBox.add(demoRow, BorderLayout.SOUTH);

        topWrapper.add(scanBox);
        topWrapper.add(Box.createVerticalStrut(10));

        statusBanner.setOpaque(true);
        statusBanner.setFont(statusBanner.getFont().deriveFont(Font.BOLD, 13.5f));
        statusBanner.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        statusBanner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        statusBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
        scanBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        topWrapper.add(statusBanner);

        add(topWrapper, BorderLayout.NORTH);

        // Center split pane: Left Product Card | Right Actions & Movement History
        leftCardPanel = new JPanel(new BorderLayout(0, 12));
        JPanel leftCard = leftCardPanel;
        BrutalistStyle.applyBoxCard(leftCard, 14, 16);

        JPanel cardTop = new JPanel(new BorderLayout(12, 0));
        qrThumbLabel.setPreferredSize(new Dimension(150, 150));
        qrThumbLabel.setBorder(BorderFactory.createLineBorder(new Color(0xCBD5E1)));
        cardTop.add(qrThumbLabel, BorderLayout.WEST);

        JPanel codeHeaderBox = new JPanel();
        codeHeaderBox.setLayout(new BoxLayout(codeHeaderBox, BoxLayout.Y_AXIS));
        codeTitleLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 15));
        statusPillLabel.setOpaque(true);
        statusPillLabel.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        statusPillLabel.setFont(statusPillLabel.getFont().deriveFont(Font.BOLD, 12f));
        codeHeaderBox.add(Box.createVerticalStrut(16));
        codeHeaderBox.add(codeTitleLabel);
        codeHeaderBox.add(Box.createVerticalStrut(10));
        codeHeaderBox.add(statusPillLabel);
        codeHeaderBox.add(Box.createVerticalGlue());
        cardTop.add(codeHeaderBox, BorderLayout.CENTER);
        leftCard.add(cardTop, BorderLayout.NORTH);

        JPanel detailsGrid = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 4, 5, 10);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int r = 0;
        addDetailRow(detailsGrid, gbc, r++, lblTimestamp, valTimestamp);
        addDetailRow(detailsGrid, gbc, r++, lblTarget, valTarget);
        addDetailRow(detailsGrid, gbc, r++, lblGarment, valGarment);
        addDetailRow(detailsGrid, gbc, r++, lblQuality, valQuality);
        addDetailRow(detailsGrid, gbc, r++, lblPackageType, valPackageType);
        addDetailRow(detailsGrid, gbc, r++, lblWeight, valWeight);
        addDetailRow(detailsGrid, gbc, r++, lblHex, valHex);
        addDetailRow(detailsGrid, gbc, r++, lblLocation, valLocation);
        addDetailRow(detailsGrid, gbc, r++, lblOperator, valOperator);
        leftCard.add(new JScrollPane(detailsGrid), BorderLayout.CENTER);

        // Right panel: Instant actions + movement history
        rightPanelBox = new JPanel(new BorderLayout(0, 12));
        JPanel rightPanel = rightPanelBox;
        BrutalistStyle.applyBoxCard(rightPanel, 14, 16);

        JPanel actionGrid = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 6));
        relocateBtn.setPreferredSize(new Dimension(175, 36));
        verifyBtn.setPreferredSize(new Dimension(175, 36));
        reprintBtn.setPreferredSize(new Dimension(165, 36));
        addToOrderBtn.setPreferredSize(new Dimension(190, 36));
        actionGrid.add(relocateBtn);
        actionGrid.add(verifyBtn);
        actionGrid.add(reprintBtn);
        actionGrid.add(addToOrderBtn);
        rightPanel.add(actionGrid, BorderLayout.NORTH);

        JPanel historyBox = new JPanel(new BorderLayout(0, 6));
        historyTitleLabel.setFont(historyTitleLabel.getFont().deriveFont(Font.BOLD, 13.5f));
        historyBox.add(historyTitleLabel, BorderLayout.NORTH);
        historyTable.setRowHeight(24);
        historyTable.setFillsViewportHeight(true);
        historyBox.add(new JScrollPane(historyTable), BorderLayout.CENTER);
        rightPanel.add(historyBox, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftCard, rightPanel);
        splitPane.setResizeWeight(0.47);
        splitPane.setDividerLocation(510);
        add(splitPane, BorderLayout.CENTER);
    }

    private static void addDetailRow(JPanel grid, GridBagConstraints gbc, int row, JLabel key, JLabel value) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        key.setFont(key.getFont().deriveFont(Font.BOLD, 12.5f));
        grid.add(key, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        value.setFont(value.getFont().deriveFont(Font.PLAIN, 13f));
        grid.add(value, gbc);
    }

    private void wireEvents() {
        scanInputField.addActionListener(e -> triggerTextScan());
        lookupBtn.addActionListener(e -> triggerTextScan());
        scanImageBtn.addActionListener(e -> chooseAndScanImage());
        demoScanBtn.addActionListener(e -> {
            if (demoQuickPickCombo.getSelectedItem() instanceof String code && !code.isBlank()) {
                scanInputField.setText(code);
                triggerTextScan();
            }
        });

        relocateBtn.addActionListener(e -> handleRelocate());
        verifyBtn.addActionListener(e -> handleVerifyStocktake());
        reprintBtn.addActionListener(e -> handleReprint());
        addToOrderBtn.addActionListener(e -> handleAddToOrder());
    }

    private void installImageDropHandler() {
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) {
                        processImageFile(files.get(0));
                        return true;
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(QrScannerView.this, ex.getMessage(),
                            ctx.getI18n().t("common.error"), JOptionPane.ERROR_MESSAGE);
                }
                return false;
            }
        };
        setTransferHandler(handler);
        scanInputField.setTransferHandler(handler);
    }

    private void triggerTextScan() {
        String raw = scanInputField.getText();
        if (raw == null || raw.isBlank()) {
            return;
        }
        ScannerService.ScanResult result = ctx.getScannerService().scanCode(raw);
        displayScanResult(result);
        scanInputField.setText("");
        scanInputField.requestFocusInWindow();
    }

    private void chooseAndScanImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(ctx.getI18n().t("scanner.from_image"));
        chooser.setFileFilter(new FileNameExtensionFilter("Images (*.png, *.jpg, *.jpeg)", "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            processImageFile(chooser.getSelectedFile());
        }
    }

    private void processImageFile(File file) {
        try {
            ScannerService.ScanResult result = ctx.getScannerService().scanImageFile(file.toPath());
            displayScanResult(result);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    ctx.getI18n().t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void displayScanResult(ScannerService.ScanResult result) {
        this.lastResult = result;
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();

        switch (result.outcome()) {
            case FOUND_IN_DB -> {
                Toolkit.getDefaultToolkit().beep();
                setBanner(i18n.t("scanner.status.found") + "  [" + result.rawInput() + "]",
                        new Color(0xDCFCE7), new Color(0x166534));
                InventoryPackage pkg = result.inventoryPackage();
                renderQrThumb(pkg.qrCode());
                codeTitleLabel.setText(pkg.qrCode());
                setStatusPill(pkg.status());

                valTimestamp.setText(pkg.createdAt() != null ? DT_FMT.format(pkg.createdAt()) : "—");
                valTarget.setText(pkg.targetGroupCode() + " — " + pkg.localizedTargetGroup(lang));
                valGarment.setText(pkg.garmentTypeCode() + " — " + pkg.localizedGarmentType(lang));
                valQuality.setText(pkg.qualityGradeCode() + " — " + pkg.localizedQualityGrade(lang));
                valPackageType.setText(pkg.packageTypeCode() + " — " + pkg.localizedPackageType(lang));
                valWeight.setText(FormatUtil.stripZeros(pkg.weightKg()) + " " + i18n.t("common.kg"));
                valHex.setText(pkg.hexSuffix());
                valLocation.setText(pkg.locationCode());
                valOperator.setText(pkg.createdByFullName() + " (" + pkg.createdByUsername() + ")");
                valNotes.setText(pkg.notes() != null && !pkg.notes().isBlank() ? pkg.notes() : "—");

                populateHistoryTable(result.history());
                updateActionButtons(pkg);
            }
            case VALID_FORMAT_NOT_IN_DB -> {
                setBanner(i18n.t("scanner.status.valid_not_found") + "  [" + result.rawInput() + "]",
                        new Color(0xFEF3C7), new Color(0x92400E));
                ParsedProductCode p = result.parsedCode();
                renderQrThumb(p.rawCode());
                codeTitleLabel.setText(p.rawCode());
                statusPillLabel.setText("UNREGISTERED");
                statusPillLabel.setBackground(new Color(0xD97706));
                statusPillLabel.setForeground(Color.WHITE);

                valTimestamp.setText(DT_FMT.format(p.timestamp()));
                valTarget.setText(p.targetGroupCode());
                valGarment.setText(p.garmentTypeCode());
                valQuality.setText(p.qualityGradeCode());
                valPackageType.setText(p.packageTypeCode());
                valWeight.setText(FormatUtil.stripZeros(p.weightKg()) + " " + i18n.t("common.kg"));
                valHex.setText(p.hexSuffix());
                valLocation.setText("—");
                valOperator.setText("—");
                valNotes.setText("—");

                populateHistoryTable(List.of());
                updateActionButtons(null);
            }
            case IMAGE_NO_QR_FOUND -> {
                setBanner(i18n.t("scanner.status.no_qr_in_image") + " (" + result.rawInput() + ")",
                        new Color(0xFEE2E2), new Color(0x991B1B));
                clearDetailCard();
            }
            case INVALID_FORMAT -> {
                setBanner(i18n.t("scanner.status.invalid") + "  [" + result.rawInput() + "]",
                        new Color(0xFEE2E2), new Color(0x991B1B));
                clearDetailCard();
            }
        }
    }

    private void resetToWaitingState() {
        lastResult = null;
        setBanner(ctx.getI18n().t("scanner.status.waiting").toUpperCase(), ThemeManager.surface(), ThemeManager.fg());
        statusBanner.setBorder(BrutalistStyle.boxBorder(8, 12));
        clearDetailCard();
    }

    private void clearDetailCard() {
        qrThumbLabel.setIcon(null);
        codeTitleLabel.setText("—");
        statusPillLabel.setText(" ");
        statusPillLabel.setBackground(new Color(0x94A3B8));
        statusPillLabel.setForeground(Color.WHITE);
        valTimestamp.setText("—");
        valTarget.setText("—");
        valGarment.setText("—");
        valQuality.setText("—");
        valPackageType.setText("—");
        valWeight.setText("—");
        valHex.setText("—");
        valLocation.setText("—");
        valOperator.setText("—");
        valNotes.setText("—");
        populateHistoryTable(List.of());
        updateActionButtons(null);
    }

    private void renderQrThumb(String code) {
        try {
            BufferedImage img = ctx.getQrCodeService().generateQrImage(code, 144);
            qrThumbLabel.setIcon(new ImageIcon(img));
        } catch (Exception ignored) {
            qrThumbLabel.setIcon(null);
        }
    }

    private void setBanner(String text, Color bg, Color fg) {
        statusBanner.setText(text);
        statusBanner.setBackground(bg);
        statusBanner.setForeground(fg);
    }

    private void setStatusPill(PackageStatus status) {
        statusPillLabel.setText(ctx.getI18n().t("status.package." + status.name()).toUpperCase());
        BrutalistStyle.styleBoxedTag(statusPillLabel, status == PackageStatus.IN_STOCK);
    }

    private void updateActionButtons(InventoryPackage pkg) {
        boolean found = pkg != null;
        boolean canRelocate = found && (pkg.status() == PackageStatus.IN_STOCK || pkg.status() == PackageStatus.RESERVED);
        boolean canAddOrder = found && pkg.status() == PackageStatus.IN_STOCK && ctx.getSession().canManageOrders();

        relocateBtn.setEnabled(canRelocate);
        verifyBtn.setEnabled(found);
        reprintBtn.setEnabled(found);
        addToOrderBtn.setEnabled(canAddOrder);
    }

    private void populateHistoryTable(List<StockMovement> history) {
        I18nManager i18n = ctx.getI18n();
        historyModel.setRowCount(0);
        for (StockMovement m : history) {
            String fromTo = (m.fromLocationCode() != null ? m.fromLocationCode() : "—")
                    + " -> " + (m.toLocationCode() != null ? m.toLocationCode() : "—");
            historyModel.addRow(new Object[]{
                    m.createdAt() != null ? DT_FMT.format(m.createdAt()) : "",
                    i18n.t("movement." + m.movementType().name()),
                    fromTo,
                    m.userFullName()
            });
        }
    }

    private void handleRelocate() {
        if (lastResult == null || lastResult.inventoryPackage() == null) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        InventoryPackage pkg = lastResult.inventoryPackage();
        List<WarehouseLocation> locations = ctx.getLocationDao().findAll(true);
        Object[] options = locations.stream()
                .map(l -> l.code() + " (" + FormatUtil.stripZeros(l.usedWeightKg()) + "/" + FormatUtil.stripZeros(l.maxCapacityKg()) + " kg)")
                .toArray();

        Object selected = JOptionPane.showInputDialog(
                this,
                i18n.t("scanner.relocate.prompt"),
                i18n.t("scanner.action.relocate"),
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options.length > 0 ? options[0] : null
        );
        if (selected == null) {
            return;
        }
        String chosenCode = selected.toString().split(" ")[0];
        WarehouseLocation targetLoc = locations.stream()
                .filter(l -> l.code().equals(chosenCode))
                .findFirst()
                .orElse(null);
        if (targetLoc == null) {
            return;
        }
        try {
            ctx.getInventoryService().relocatePackage(
                    pkg.id(), targetLoc.id(), "Relocated via QR Scanner",
                    ctx.getSession().requireCurrentUser().id()
            );
            displayScanResult(ctx.getScannerService().scanCode(pkg.qrCode()));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleVerifyStocktake() {
        if (lastResult == null || lastResult.inventoryPackage() == null) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        InventoryPackage pkg = lastResult.inventoryPackage();
        try {
            ctx.getInventoryService().verifyStocktake(
                    pkg.id(), "Verified at QR Scanner Station",
                    ctx.getSession().requireCurrentUser().id()
            );
            displayScanResult(ctx.getScannerService().scanCode(pkg.qrCode()));
            JOptionPane.showMessageDialog(this, i18n.t("scanner.verify.done"),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleReprint() {
        if (lastResult == null || lastResult.inventoryPackage() == null) {
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        PrintPreviewDialog.showPreview(owner, ctx, List.of(lastResult.inventoryPackage()), LabelPreset.THERMAL_100X150);
    }

    private void handleAddToOrder() {
        if (lastResult == null || lastResult.inventoryPackage() == null) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        InventoryPackage pkg = lastResult.inventoryPackage();
        List<Order> openOrders = ctx.getOrderDao().findOpenOrders();
        if (openOrders.isEmpty()) {
            JOptionPane.showMessageDialog(this, i18n.t("scanner.order.none_open"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        Object[] options = openOrders.stream()
                .map(o -> o.orderNumber() + " — " + o.customerName() + " [" + i18n.t("status.order." + o.status().name()) + "]")
                .toArray();
        Object chosen = JOptionPane.showInputDialog(
                this,
                i18n.t("scanner.order.select"),
                i18n.t("scanner.action.add_to_order"),
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[0]
        );
        if (chosen == null) {
            return;
        }
        String orderNum = chosen.toString().split(" ")[0];
        Order order = openOrders.stream()
                .filter(o -> o.orderNumber().equals(orderNum))
                .findFirst()
                .orElse(null);
        if (order == null) {
            return;
        }
        try {
            ctx.getOrderService().addPackageToOrder(order.id(), pkg.id(), null, ctx.getSession().requireCurrentUser().id());
            displayScanResult(ctx.getScannerService().scanCode(pkg.qrCode()));
            JOptionPane.showMessageDialog(this, i18n.t("scanner.order.added", order.orderNumber()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_SCANNER;
    }

    @Override
    public String getTitleI18nKey() {
        return "scanner.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        demoQuickPickCombo.removeAllItems();
        List<InventoryPackage> packages = ctx.getPackageDao().findByFilter(PackageFilter.empty());
        for (InventoryPackage pkg : packages) {
            demoQuickPickCombo.addItem(pkg.qrCode());
        }
        if (lastResult != null && lastResult.inventoryPackage() != null) {
            displayScanResult(ctx.getScannerService().scanCode(lastResult.inventoryPackage().qrCode()));
        }
        SwingUtilities.invokeLater(scanInputField::requestFocusInWindow);
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        inputPromptLabel.setText(i18n.t("scanner.input_label"));
        lookupBtn.setText(i18n.t("scanner.scan_button"));
        scanImageBtn.setText(i18n.t("scanner.from_image"));
        demoScanBtn.setText(i18n.t("scanner.demo_pick"));
        dropHintLabel.setText(i18n.t("scanner.drop_hint"));

        lblTimestamp.setText(i18n.t("scanner.field.timestamp") + ":");
        lblTarget.setText(i18n.t("intake.target_group") + ":");
        lblGarment.setText(i18n.t("intake.garment_type") + ":");
        lblQuality.setText(i18n.t("intake.quality_grade") + ":");
        lblPackageType.setText(i18n.t("intake.package_type") + ":");
        lblWeight.setText(i18n.t("intake.weight_kg") + ":");
        lblHex.setText(i18n.t("scanner.field.hex") + ":");
        lblLocation.setText(i18n.t("intake.location") + ":");
        lblOperator.setText(i18n.t("scanner.field.operator") + ":");
        lblNotes.setText(i18n.t("common.notes") + ":");

        relocateBtn.setText(i18n.t("scanner.action.relocate"));
        verifyBtn.setText(i18n.t("scanner.action.verify"));
        reprintBtn.setText(i18n.t("scanner.action.reprint"));
        addToOrderBtn.setText(i18n.t("scanner.action.add_to_order"));

        historyTitleLabel.setText(i18n.t("scanner.history"));
        historyModel.setColumnIdentifiers(new Object[]{
                i18n.t("common.created_at"),
                i18n.t("common.actions"),
                i18n.t("intake.location"),
                i18n.t("scanner.field.operator")
        });

        if (lastResult != null) {
            displayScanResult(lastResult);
        } else {
            resetToWaitingState();
        }
        applyTheme();
    }

    @Override
    public void applyTheme() {
        if (scanBoxPanel != null) BrutalistStyle.applyBoxCard(scanBoxPanel, 12, 14);
        if (leftCardPanel != null) BrutalistStyle.applyBoxCard(leftCardPanel, 14, 16);
        if (rightPanelBox != null) BrutalistStyle.applyBoxCard(rightPanelBox, 14, 16);
        BrutalistStyle.stylePrimaryButton(lookupBtn);
        BrutalistStyle.styleSecondaryButtons(
                scanImageBtn, demoScanBtn,
                relocateBtn, verifyBtn, reprintBtn, addToOrderBtn);
        BrutalistStyle.styleTable(historyTable);
        qrThumbLabel.setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));
        if (lastResult == null) {
            setBanner(ctx.getI18n().t("scanner.status.waiting").toUpperCase(), ThemeManager.surface(), ThemeManager.fg());
            statusBanner.setBorder(BrutalistStyle.boxBorder(8, 12));
        } else if (lastResult.inventoryPackage() != null) {
            setStatusPill(lastResult.inventoryPackage().status());
        }
    }
}
