package hu.due.rewear.service;

import hu.due.rewear.model.InventoryPackage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.util.List;
import java.util.Objects;

/**
 * {@link Printable} implementation that renders one or more QR tickets onto pages
 * according to the chosen {@link LabelPreset} (single thermal ticket or A4 4-up sheet).
 */
public final class TicketPrintable implements Printable {

    private final List<InventoryPackage> packages;
    private final LabelPreset preset;
    private final TicketLabelPainter painter;

    public TicketPrintable(List<InventoryPackage> packages, LabelPreset preset, TicketLabelPainter painter) {
        this.packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
        this.preset = Objects.requireNonNull(preset, "preset");
        this.painter = Objects.requireNonNull(painter, "painter");
    }

    public int getPageCount() {
        if (packages.isEmpty()) {
            return 0;
        }
        int perPage = preset.getLabelsPerPage();
        return (packages.size() + perPage - 1) / perPage;
    }

    public PageFormat createPageFormat() {
        PageFormat pf = new PageFormat();
        Paper paper = new Paper();
        double w = preset.widthPoints();
        double h = preset.heightPoints();
        paper.setSize(w, h);
        double margin = preset == LabelPreset.A4_4UP ? 18.0 : 4.0;
        paper.setImageableArea(margin, margin, w - 2 * margin, h - 2 * margin);
        pf.setPaper(paper);
        pf.setOrientation(PageFormat.PORTRAIT);
        return pf;
    }

    /**
     * Paints the requested page at exact point coordinates {@code (0, 0, pageWidthPt, pageHeightPt)}.
     * Shared by Preview canvas, PrinterJob, and PDF exporter.
     */
    public void paintPage(Graphics2D g2, int pageIndex, double pageWidthPt, double pageHeightPt) {
        int perPage = preset.getLabelsPerPage();
        int start = pageIndex * perPage;
        if (start >= packages.size()) {
            return;
        }

        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, (int) Math.ceil(pageWidthPt), (int) Math.ceil(pageHeightPt));

        if (preset == LabelPreset.A4_4UP) {
            double outerMargin = 18.0;
            double gap = 12.0;
            double cellW = (pageWidthPt - 2 * outerMargin - gap) / 2.0;
            double cellH = (pageHeightPt - 2 * outerMargin - gap) / 2.0;

            // Subtle dashed cut guides
            Graphics2D guides = (Graphics2D) g2.create();
            guides.setColor(new Color(0xCBD5E1));
            guides.setStroke(new BasicStroke(0.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10.0f, new float[]{4f, 4f}, 0f));
            double midX = pageWidthPt / 2.0;
            double midY = pageHeightPt / 2.0;
            guides.drawLine((int) midX, (int) outerMargin, (int) midX, (int) (pageHeightPt - outerMargin));
            guides.drawLine((int) outerMargin, (int) midY, (int) (pageWidthPt - outerMargin), (int) midY);
            guides.dispose();

            for (int slot = 0; slot < 4; slot++) {
                int idx = start + slot;
                if (idx >= packages.size()) {
                    break;
                }
                int col = slot % 2;
                int row = slot / 2;
                double cx = outerMargin + col * (cellW + gap);
                double cy = outerMargin + row * (cellH + gap);
                painter.paintTicket(g2, packages.get(idx), cx, cy, cellW, cellH);
            }
        } else {
            double margin = 4.0;
            painter.paintTicket(g2, packages.get(start), margin, margin,
                    pageWidthPt - 2 * margin, pageHeightPt - 2 * margin);
        }
    }

    @Override
    public int print(Graphics graphics, PageFormat pageFormat, int pageIndex) {
        if (pageIndex < 0 || pageIndex >= getPageCount()) {
            return NO_SUCH_PAGE;
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            double targetW = preset.widthPoints();
            double targetH = preset.heightPoints();
            double scaleX = pageFormat.getImageableWidth() / targetW;
            double scaleY = pageFormat.getImageableHeight() / targetH;
            double scale = Math.min(scaleX, scaleY);

            double offsetX = pageFormat.getImageableX() + (pageFormat.getImageableWidth() - targetW * scale) / 2.0;
            double offsetY = pageFormat.getImageableY() + (pageFormat.getImageableHeight() - targetH * scale) / 2.0;

            g2.translate(offsetX, offsetY);
            g2.scale(scale, scale);
            paintPage(g2, pageIndex, targetW, targetH);
            return PAGE_EXISTS;
        } finally {
            g2.dispose();
        }
    }
}
