package hu.due.rewear.ui;

import com.lowagie.text.PageSize;
import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.AppPaths;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.service.DocumentPageSource;
import hu.due.rewear.service.InvoicePdfExporter;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.print.Book;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive Brutalist A4 Document Preview dialog supporting Delivery Notes (Szállítólevél)
 * and Invoices (Számla) individually or together — with checkboxes to select which document(s)
 * to Print, Save as PDF, or Send by Email.
 */
public final class DocumentPreviewDialog extends JDialog {

    private static final float A4_W = PageSize.A4.getWidth();
    private static final float A4_H = PageSize.A4.getHeight();

    private final AppContext ctx;
    private final List<DocumentPageSource> allDocuments;
    private final String defaultEmail;
    private final String defaultSubject;

    private final List<JCheckBox> docCheckboxes = new ArrayList<>();
    private final JComboBox<DocOption> previewSelector = new JComboBox<>();
    private final JSlider zoomSlider = new JSlider(55, 160, 95);
    private final JLabel zoomValueLabel = new JLabel("95%");
    private final JComboBox<PrinterOption> printerCombo = new JComboBox<>();

    private final JButton prevPageBtn = new JButton("<");
    private final JLabel pageIndicatorLabel = new JLabel();
    private final JButton nextPageBtn = new JButton(">");

    private final A4Canvas canvas = new A4Canvas();

    private DocumentPageSource activePreviewDoc;
    private int currentPage = 0;

    private record DocOption(DocumentPageSource source) {
        @Override
        public String toString() {
            return source.getDisplayTitle();
        }
    }

    private record PrinterOption(PrintService service, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public static void showDocuments(Window owner, AppContext ctx, List<DocumentPageSource> documents,
                                     String defaultRecipientEmail, String defaultEmailSubject) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        DocumentPreviewDialog dialog = new DocumentPreviewDialog(
                owner, ctx, documents, defaultRecipientEmail, defaultEmailSubject);
        dialog.setVisible(true);
    }

    private DocumentPreviewDialog(Window owner, AppContext ctx, List<DocumentPageSource> documents,
                                  String defaultEmail, String defaultSubject) {
        super(owner, ctx.getI18n().t("docpreview.title"), ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        this.allDocuments = List.copyOf(documents);
        this.defaultEmail = defaultEmail != null ? defaultEmail.trim() : "";
        this.defaultSubject = defaultSubject != null ? defaultSubject.trim() : "ReWear WMS";
        this.activePreviewDoc = this.allDocuments.get(0);

        LogoAssets.applyWindowIcon(this);
        setMinimumSize(new Dimension(760, 620));
        setPreferredSize(new Dimension(1040, 820));

        buildUi();
        updatePageControls();

        pack();
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        I18nManager i18n = ctx.getI18n();
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(ThemeManager.bg());
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        // Top Toolbar (WrapLayout so narrow windows expand cleanly)
        JPanel topStack = new JPanel(new BorderLayout(0, 6));
        topStack.setOpaque(false);

        JPanel toolbar = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 5));
        BrutalistStyle.applyBoxCard(toolbar, 6, 8);

        if (allDocuments.size() > 1) {
            JPanel checkGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            checkGroup.setOpaque(false);
            checkGroup.add(new JLabel(i18n.t("docpreview.select_docs")));
            for (DocumentPageSource doc : allDocuments) {
                JCheckBox cb = new JCheckBox(doc.getDisplayTitle(), true);
                cb.setOpaque(false);
                cb.setForeground(ThemeManager.fg());
                docCheckboxes.add(cb);
                checkGroup.add(cb);
            }
            toolbar.add(checkGroup);

            JPanel viewGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            viewGroup.setOpaque(false);
            viewGroup.add(new JLabel(i18n.t("docpreview.viewing")));
            for (DocumentPageSource doc : allDocuments) {
                previewSelector.addItem(new DocOption(doc));
            }
            previewSelector.addActionListener(e -> {
                if (previewSelector.getSelectedItem() instanceof DocOption opt) {
                    activePreviewDoc = opt.source();
                    currentPage = 0;
                    updatePageControls();
                    canvas.repaint();
                }
            });
            viewGroup.add(previewSelector);
            toolbar.add(viewGroup);
        } else {
            JCheckBox hiddenSingle = new JCheckBox(allDocuments.get(0).getDisplayTitle(), true);
            docCheckboxes.add(hiddenSingle);
            JLabel singleTitle = new JLabel(allDocuments.get(0).getDisplayTitle());
            singleTitle.setForeground(ThemeManager.fg());
            toolbar.add(singleTitle);
        }

        JPanel zoomGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        zoomGroup.setOpaque(false);
        zoomGroup.add(new JLabel(i18n.t("print.zoom") + ":"));
        zoomSlider.setPreferredSize(new Dimension(110, 24));
        zoomSlider.addChangeListener(e -> {
            zoomValueLabel.setText(zoomSlider.getValue() + "%");
            canvas.updatePreferredSize();
            canvas.revalidate();
            canvas.repaint();
        });
        zoomGroup.add(zoomSlider);
        zoomGroup.add(zoomValueLabel);
        toolbar.add(zoomGroup);

        JPanel printerGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        printerGroup.setOpaque(false);
        printerGroup.add(new JLabel(i18n.t("print.printer") + ":"));
        populatePrinters();
        printerCombo.setPreferredSize(new Dimension(210, 26));
        printerGroup.add(printerCombo);
        toolbar.add(printerGroup);

        topStack.add(toolbar, BorderLayout.CENTER);
        root.add(topStack, BorderLayout.NORTH);

        // Center Scrollable A4 Paper Preview
        canvas.updatePreferredSize();
        JScrollPane scrollPane = new JScrollPane(canvas);
        scrollPane.getViewport().setBackground(new Color(0x64748B));
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        root.add(scrollPane, BorderLayout.CENTER);

        // Bottom Bar: Pagination + Email / Save PDF / Print / Close
        JPanel bottomBar = new JPanel(new BorderLayout(8, 0));
        bottomBar.setOpaque(false);

        JPanel pager = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        pager.setOpaque(false);
        prevPageBtn.addActionListener(e -> {
            if (currentPage > 0) {
                currentPage--;
                updatePageControls();
                canvas.repaint();
            }
        });
        nextPageBtn.addActionListener(e -> {
            if (activePreviewDoc != null && currentPage < activePreviewDoc.getPageCount() - 1) {
                currentPage++;
                updatePageControls();
                canvas.repaint();
            }
        });
        BrutalistStyle.styleSecondaryButtons(prevPageBtn, nextPageBtn);
        pager.add(prevPageBtn);
        pager.add(pageIndicatorLabel);
        pager.add(nextPageBtn);
        bottomBar.add(pager, BorderLayout.WEST);

        JPanel actions = new JPanel(new WrapLayout(FlowLayout.RIGHT, 8, 4));
        actions.setOpaque(false);

        JButton emailBtn = new JButton(i18n.t("docpreview.send_email"));
        BrutalistStyle.styleSecondaryButton(emailBtn);
        emailBtn.addActionListener(e -> handleSendEmail());

        JButton savePdfBtn = new JButton(i18n.t("docpreview.save_pdf"));
        BrutalistStyle.styleSecondaryButton(savePdfBtn);
        savePdfBtn.addActionListener(e -> handleSavePdf());

        JButton printBtn = new JButton(i18n.t("docpreview.print"));
        BrutalistStyle.stylePrimaryButton(printBtn);
        printBtn.addActionListener(e -> handlePrint());

        JButton closeBtn = new JButton(i18n.t("common.close"));
        BrutalistStyle.styleSecondaryButton(closeBtn);
        closeBtn.addActionListener(e -> dispose());

        actions.add(emailBtn);
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

    private List<DocumentPageSource> getSelectedDocuments() {
        List<DocumentPageSource> selected = new ArrayList<>();
        for (int i = 0; i < allDocuments.size(); i++) {
            if (i < docCheckboxes.size() && docCheckboxes.get(i).isSelected()) {
                selected.add(allDocuments.get(i));
            }
        }
        return selected;
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

    private void updatePageControls() {
        int total = activePreviewDoc != null ? Math.max(1, activePreviewDoc.getPageCount()) : 1;
        pageIndicatorLabel.setText(ctx.getI18n().t("print.page_of", currentPage + 1, total));
        prevPageBtn.setEnabled(currentPage > 0);
        nextPageBtn.setEnabled(currentPage < total - 1);
    }

    private void handleSavePdf() {
        I18nManager i18n = ctx.getI18n();
        List<DocumentPageSource> selected = getSelectedDocuments();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, i18n.t("docpreview.none_selected"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        String defaultName = selected.size() == 1
                ? selected.get(0).getDefaultFileName()
                : "rewear-documents-" + selected.get(0).getDocumentId() + ".pdf";

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n.t("docpreview.save_pdf"));
        chooser.setFileFilter(new FileNameExtensionFilter("PDF Documents (*.pdf)", "pdf"));
        chooser.setSelectedFile(new File(defaultName));

        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            Path target = file.getName().toLowerCase().endsWith(".pdf")
                    ? file.toPath() : Path.of(file.getAbsolutePath() + ".pdf");
            try {
                InvoicePdfExporter.exportCombined(selected, target);
                JOptionPane.showMessageDialog(this, i18n.t("print.exported", target.toAbsolutePath()),
                        i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(),
                        i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void handlePrint() {
        I18nManager i18n = ctx.getI18n();
        List<DocumentPageSource> selected = getSelectedDocuments();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, i18n.t("docpreview.none_selected"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Flatten all selected document pages into a single Printable Book
        record PageRef(DocumentPageSource source, int pageIndex) {}
        List<PageRef> pages = new ArrayList<>();
        for (DocumentPageSource src : selected) {
            for (int p = 0; p < src.getPageCount(); p++) {
                pages.add(new PageRef(src, p));
            }
        }

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("ReWear WMS — " + defaultSubject);
        if (printerCombo.getSelectedItem() instanceof PrinterOption opt && opt.service() != null) {
            try {
                job.setPrintService(opt.service());
            } catch (PrinterException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(),
                        i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        PageFormat pf = new PageFormat();
        Paper paper = new Paper();
        paper.setSize(A4_W, A4_H);
        paper.setImageableArea(12, 12, A4_W - 24, A4_H - 24);
        pf.setPaper(paper);
        pf.setOrientation(PageFormat.PORTRAIT);
        pf = job.validatePage(pf);

        Printable printable = (graphics, pageFormat, pageIndex) -> {
            if (pageIndex < 0 || pageIndex >= pages.size()) {
                return Printable.NO_SUCH_PAGE;
            }
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                double sx = pageFormat.getImageableWidth() / A4_W;
                double sy = pageFormat.getImageableHeight() / A4_H;
                double scale = Math.min(sx, sy);
                double ox = pageFormat.getImageableX() + (pageFormat.getImageableWidth() - A4_W * scale) / 2.0;
                double oy = pageFormat.getImageableY() + (pageFormat.getImageableHeight() - A4_H * scale) / 2.0;
                g2.translate(ox, oy);
                g2.scale(scale, scale);
                PageRef ref = pages.get(pageIndex);
                ref.source().paintPage(g2, ref.pageIndex(), A4_W, A4_H);
                return Printable.PAGE_EXISTS;
            } finally {
                g2.dispose();
            }
        };

        Book book = new Book();
        book.append(printable, pf, pages.size());
        job.setPageable(book);

        if (job.printDialog()) {
            try {
                job.print();
                JOptionPane.showMessageDialog(this, i18n.t("print.success"),
                        i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            } catch (PrinterException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(),
                        i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void handleSendEmail() {
        I18nManager i18n = ctx.getI18n();
        List<DocumentPageSource> selected = getSelectedDocuments();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, i18n.t("docpreview.none_selected"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Export each selected document cleanly into ./exports/ next to the app
        Path exportDir = AppPaths.resolveAppBaseDir().resolve("exports");
        List<Path> exportedFiles = new ArrayList<>();
        try {
            Files.createDirectories(exportDir);
            for (DocumentPageSource src : selected) {
                Path out = exportDir.resolve(src.getDefaultFileName());
                InvoicePdfExporter.exportCombined(List.of(src), out);
                exportedFiles.add(out);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            return;
        }

        JTextField toField = new JTextField(defaultEmail, 28);
        JTextField subjectField = new JTextField(defaultSubject, 28);
        StringBuilder defaultBody = new StringBuilder();
        defaultBody.append("ReWear WMS — ").append(defaultSubject).append("\n\n");
        for (DocumentPageSource src : selected) {
            defaultBody.append("- ").append(src.getDisplayTitle()).append("\n");
        }
        JTextArea bodyArea = new JTextArea(defaultBody.toString(), 5, 28);
        bodyArea.setLineWrap(true);
        bodyArea.setWrapStyleWord(true);

        StringBuilder fileList = new StringBuilder();
        for (Path p : exportedFiles) {
            fileList.append(p.getFileName()).append("  ");
        }

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 4, 5, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        form.add(new JLabel(i18n.t("docpreview.email.to") + ":"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(toField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        form.add(new JLabel(i18n.t("docpreview.email.subject") + ":"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(subjectField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0; gbc.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel(i18n.t("docpreview.email.body") + ":"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(new JScrollPane(bodyArea), gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        form.add(new JLabel(i18n.t("docpreview.email.attachments") + " " + fileList), gbc);

        int res = JOptionPane.showConfirmDialog(this, form, i18n.t("docpreview.email.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.MAIL)) {
                    String to = encodeMailto(toField.getText().trim());
                    String subj = encodeMailto(subjectField.getText().trim());
                    String body = encodeMailto(bodyArea.getText().trim());
                    URI mailto = new URI("mailto:" + to + "?subject=" + subj + "&body=" + body);
                    desktop.mail(mailto);
                }
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(exportDir.toFile());
                }
            }
            JOptionPane.showMessageDialog(this,
                    i18n.t("docpreview.email.opened", exportDir.toAbsolutePath()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    i18n.t("docpreview.email.opened", exportDir.toAbsolutePath()) + "\n(" + ex.getMessage() + ")",
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static String encodeMailto(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private final class A4Canvas extends JPanel {
        private static final int DESK_MARGIN = 26;

        A4Canvas() {
            setBackground(new Color(0x64748B));
        }

        private double scaleFactor() {
            return (96.0 / 72.0) * (zoomSlider.getValue() / 100.0);
        }

        void updatePreferredSize() {
            double s = scaleFactor();
            int w = (int) Math.ceil(A4_W * s) + DESK_MARGIN * 2;
            int h = (int) Math.ceil(A4_H * s) + DESK_MARGIN * 2;
            setPreferredSize(new Dimension(w, h));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (activePreviewDoc == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double s = scaleFactor();
                int drawW = (int) Math.round(A4_W * s);
                int drawH = (int) Math.round(A4_H * s);
                int offsetX = Math.max(DESK_MARGIN, (getWidth() - drawW) / 2);
                int offsetY = Math.max(DESK_MARGIN, (getHeight() - drawH) / 2);

                g2.setColor(new Color(0, 0, 0, 75));
                g2.fillRect(offsetX + 5, offsetY + 5, drawW, drawH);

                g2.translate(offsetX, offsetY);
                g2.scale(s, s);
                activePreviewDoc.paintPage(g2, currentPage, A4_W, A4_H);
            } finally {
                g2.dispose();
            }
        }
    }
}
