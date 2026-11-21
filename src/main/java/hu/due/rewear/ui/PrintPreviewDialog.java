package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.service.LabelPreset;
import hu.due.rewear.service.TicketPrintable;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.print.Book;
import java.awt.print.PageFormat;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Interactive Ticket / Label Print Preview dialog supporting Thermal (100x150, 80x100), A6, and A4 4-up formats,
 * zoom controls, page navigation, system printer selection, Java PrinterJob printing, PDF export, and QR PNG export.
 */
public final class PrintPreviewDialog extends JDialog {

    private final AppContext ctx;
    private final List<InventoryPackage> packages;

    private final JComboBox<PresetOption> presetCombo = new JComboBox<>();
    private final JSlider zoomSlider = new JSlider(50, 200, 115);
    private final JLabel zoomValueLabel = new JLabel("115%");
    private final JSpinner copiesSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 50, 1));
    private final JComboBox<PrinterOption> printerCombo = new JComboBox<>();

    private final JButton prevPageBtn = new JButton("<");
    private final JLabel pageIndicatorLabel = new JLabel();
    private final JButton nextPageBtn = new JButton(">");

    private final PreviewCanvas previewCanvas = new PreviewCanvas();

    private LabelPreset activePreset;
    private TicketPrintable printable;
    private int currentPage = 0;

    private record PresetOption(LabelPreset preset, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record PrinterOption(PrintService service, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public PrintPreviewDialog(Window owner, AppContext ctx, List<InventoryPackage> packages, LabelPreset initialPreset) {
        super(owner, ctx.getI18n().t("print.title"), ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        this.packages = List.copyOf(packages);
        this.activePreset = initialPreset != null ? initialPreset : LabelPreset.THERMAL_100X150;

        setMinimumSize(new Dimension(680, 560));
        setPreferredSize(new Dimension(1020, 780));

        buildUi();
        rebuildPrintable();

        pack();
        setLocationRelativeTo(owner);
    }

    public static void showPreview(Window owner, AppContext ctx, List<InventoryPackage> packages, LabelPreset preset) {
        if (packages == null || packages.isEmpty()) {
            return;
        }
        PrintPreviewDialog dialog = new PrintPreviewDialog(owner, ctx, packages, preset);
        dialog.setVisible(true);
    }

    private void buildUi() {
        I18nManager i18n = ctx.getI18n();
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        // Top toolbar (WrapLayout with grouped label+control pairs so narrowing never clips controls)
        JPanel toolbar = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 6));
        BrutalistStyle.applyBoxCard(toolbar, 6, 8);

        JPanel presetGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        presetGroup.setOpaque(false);
        presetGroup.add(new JLabel(i18n.t("print.preset") + ":"));
        for (LabelPreset preset : LabelPreset.values()) {
            PresetOption opt = new PresetOption(preset, i18n.t(preset.getI18nKey()));
            presetCombo.addItem(opt);
            if (preset == activePreset) {
                presetCombo.setSelectedItem(opt);
            }
        }
        presetCombo.addActionListener(e -> {
            if (presetCombo.getSelectedItem() instanceof PresetOption opt) {
                activePreset = opt.preset();
                currentPage = 0;
                rebuildPrintable();
            }
        });
        presetGroup.add(presetCombo);
        toolbar.add(presetGroup);

        JPanel zoomGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        zoomGroup.setOpaque(false);
        zoomGroup.add(new JLabel(i18n.t("print.zoom") + ":"));
        zoomSlider.setPreferredSize(new Dimension(110, 24));
        zoomSlider.addChangeListener(e -> {
            zoomValueLabel.setText(zoomSlider.getValue() + "%");
            previewCanvas.updatePreferredSize();
            previewCanvas.revalidate();
            previewCanvas.repaint();
        });
        zoomGroup.add(zoomSlider);
        zoomGroup.add(zoomValueLabel);
        toolbar.add(zoomGroup);

        JPanel copiesGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        copiesGroup.setOpaque(false);
        copiesGroup.add(new JLabel(i18n.t("print.copies") + ":"));
        copiesSpinner.setPreferredSize(new Dimension(58, 26));
        copiesGroup.add(copiesSpinner);
        toolbar.add(copiesGroup);

        JPanel printerGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        printerGroup.setOpaque(false);
        printerGroup.add(new JLabel(i18n.t("print.printer") + ":"));
        populatePrinters();
        printerCombo.setPreferredSize(new Dimension(210, 26));
        printerGroup.add(printerCombo);
        toolbar.add(printerGroup);

        root.add(toolbar, BorderLayout.NORTH);

        // Center scrollable paper canvas
        JScrollPane scrollPane = new JScrollPane(previewCanvas);
        scrollPane.getViewport().setBackground(new Color(0x64748B));
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        root.add(scrollPane, BorderLayout.CENTER);

        // Bottom bar: pagination + wrapping export/print buttons
        JPanel bottomBar = new JPanel(new BorderLayout(8, 0));

        JPanel pager = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        prevPageBtn.addActionListener(e -> {
            if (currentPage > 0) {
                currentPage--;
                updatePageControls();
                previewCanvas.repaint();
            }
        });
        nextPageBtn.addActionListener(e -> {
            if (currentPage < printable.getPageCount() - 1) {
                currentPage++;
                updatePageControls();
                previewCanvas.repaint();
            }
        });
        pager.add(prevPageBtn);
        pager.add(pageIndicatorLabel);
        pager.add(nextPageBtn);
        bottomBar.add(pager, BorderLayout.WEST);

        JPanel actions = new JPanel(new WrapLayout(FlowLayout.RIGHT, 8, 4));
        JButton savePngBtn = new JButton(i18n.t("print.button.png"));
        savePngBtn.addActionListener(e -> exportQrPng());

        JButton savePdfBtn = new JButton(i18n.t("print.button.pdf"));
        savePdfBtn.addActionListener(e -> exportLabelPdf());

        JButton printBtn = new JButton(i18n.t("print.button.print"));
        printBtn.addActionListener(e -> sendToPrinter());

        JButton closeBtn = new JButton(i18n.t("common.close"));
        closeBtn.addActionListener(e -> dispose());

        actions.add(savePngBtn);
        actions.add(savePdfBtn);
        actions.add(printBtn);
        actions.add(closeBtn);
        bottomBar.add(actions, BorderLayout.CENTER);

        root.add(bottomBar, BorderLayout.SOUTH);
        root.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                toolbar.revalidate();
                actions.revalidate();
                bottomBar.revalidate();
                root.revalidate();
            }
        });
        setContentPane(root);
    }

    private void populatePrinters() {
        printerCombo.removeAllItems();
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        PrintService defaultSvc = PrintServiceLookup.lookupDefaultPrintService();
        if (services == null || services.length == 0) {
            printerCombo.addItem(new PrinterOption(null, ctx.getI18n().t("print.no_printers")));
            return;
        }
        PrinterOption selected = null;
        for (PrintService svc : services) {
            PrinterOption opt = new PrinterOption(svc, svc.getName());
            printerCombo.addItem(opt);
            if (defaultSvc != null && defaultSvc.equals(svc)) {
                selected = opt;
            }
        }
        if (selected != null) {
            printerCombo.setSelectedItem(selected);
        }
    }

    private void rebuildPrintable() {
        printable = new TicketPrintable(packages, activePreset, ctx.getTicketLabelPainter());
        if (currentPage >= printable.getPageCount()) {
            currentPage = Math.max(0, printable.getPageCount() - 1);
        }
        updatePageControls();
        previewCanvas.updatePreferredSize();
        previewCanvas.revalidate();
        previewCanvas.repaint();
    }

    private void updatePageControls() {
        int total = Math.max(1, printable.getPageCount());
        pageIndicatorLabel.setText(ctx.getI18n().t("print.page_of", currentPage + 1, total));
        prevPageBtn.setEnabled(currentPage > 0);
        nextPageBtn.setEnabled(currentPage < total - 1);
    }

    private void sendToPrinter() {
        I18nManager i18n = ctx.getI18n();
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("ReWear WMS Label — " + packages.get(0).qrCode());
        job.setCopies((Integer) copiesSpinner.getValue());

        if (printerCombo.getSelectedItem() instanceof PrinterOption opt && opt.service() != null) {
            try {
                job.setPrintService(opt.service());
            } catch (PrinterException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        PageFormat pf = job.validatePage(printable.createPageFormat());
        Book book = new Book();
        book.append(printable, pf, printable.getPageCount());
        job.setPageable(book);

        if (job.printDialog()) {
            try {
                job.print();
                JOptionPane.showMessageDialog(this, i18n.t("print.success"),
                        i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            } catch (PrinterException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void exportLabelPdf() {
        I18nManager i18n = ctx.getI18n();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n.t("print.button.pdf"));
        chooser.setFileFilter(new FileNameExtensionFilter("PDF Documents (*.pdf)", "pdf"));
        String defaultName = packages.size() == 1
                ? "label-" + packages.get(0).qrCode() + ".pdf"
                : "labels-batch-" + packages.size() + ".pdf";
        chooser.setSelectedFile(new File(defaultName));

        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            Path target = file.getName().toLowerCase().endsWith(".pdf")
                    ? file.toPath()
                    : Path.of(file.getAbsolutePath() + ".pdf");
            try {
                ctx.getLabelPdfExporter().exportToPdf(packages, activePreset, target);
                JOptionPane.showMessageDialog(this, i18n.t("print.exported", target.toAbsolutePath()),
                        i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void exportQrPng() {
        I18nManager i18n = ctx.getI18n();
        int idx = Math.min(currentPage * activePreset.getLabelsPerPage(), packages.size() - 1);
        InventoryPackage pkg = packages.get(idx);

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n.t("print.button.png"));
        chooser.setFileFilter(new FileNameExtensionFilter("PNG Image (*.png)", "png"));
        chooser.setSelectedFile(new File("qr-" + pkg.qrCode() + ".png"));

        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            Path target = file.getName().toLowerCase().endsWith(".png")
                    ? file.toPath()
                    : Path.of(file.getAbsolutePath() + ".png");
            try {
                ctx.getQrCodeService().saveQrPng(pkg.qrCode(), 512, target);
                JOptionPane.showMessageDialog(this, i18n.t("print.exported", target.toAbsolutePath()),
                        i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private final class PreviewCanvas extends JPanel {
        private static final int DESK_MARGIN = 28;

        PreviewCanvas() {
            setBackground(new Color(0x64748B));
        }

        private double scaleFactor() {
            // Convert 72 pt/in -> ~96 px/in * user zoom %
            return (96.0 / 72.0) * (zoomSlider.getValue() / 100.0);
        }

        void updatePreferredSize() {
            double s = scaleFactor();
            int w = (int) Math.ceil(activePreset.widthPoints() * s) + DESK_MARGIN * 2;
            int h = (int) Math.ceil(activePreset.heightPoints() * s) + DESK_MARGIN * 2;
            setPreferredSize(new Dimension(w, h));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (printable == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double s = scaleFactor();
                double pageW = activePreset.widthPoints();
                double pageH = activePreset.heightPoints();
                int drawW = (int) Math.round(pageW * s);
                int drawH = (int) Math.round(pageH * s);

                int offsetX = Math.max(DESK_MARGIN, (getWidth() - drawW) / 2);
                int offsetY = Math.max(DESK_MARGIN, (getHeight() - drawH) / 2);

                // Paper drop shadow
                g2.setColor(new Color(0, 0, 0, 75));
                g2.fillRect(offsetX + 5, offsetY + 5, drawW, drawH);

                // Page content
                g2.translate(offsetX, offsetY);
                g2.scale(s, s);
                printable.paintPage(g2, currentPage, pageW, pageH);
            } finally {
                g2.dispose();
            }
        }
    }
}
