package hu.due.rewear.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfWriter;
import hu.due.rewear.model.InventoryPackage;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports one or more QR warehouse tickets to an exact-size PDF using OpenPDF,
 * rendering each page at 300 DPI so preview, printed tickets, and PDFs match 1:1
 * with full Hungarian/UTF-8 character fidelity.
 */
public final class LabelPdfExporter {

    private static final double EXPORT_DPI = 300.0;

    private final TicketLabelPainter painter;

    public LabelPdfExporter(TicketLabelPainter painter) {
        this.painter = painter;
    }

    public void exportToPdf(List<InventoryPackage> packages, LabelPreset preset, Path targetPdf) throws IOException {
        if (packages == null || packages.isEmpty()) {
            throw new IllegalArgumentException("At least one package is required for label PDF export.");
        }
        if (targetPdf.getParent() != null) {
            Files.createDirectories(targetPdf.getParent());
        }

        TicketPrintable printable = new TicketPrintable(packages, preset, painter);
        float widthPt = (float) preset.widthPoints();
        float heightPt = (float) preset.heightPoints();
        Rectangle pageSize = new Rectangle(widthPt, heightPt);

        double scale = EXPORT_DPI / 72.0;
        int imgW = (int) Math.round(widthPt * scale);
        int imgH = (int) Math.round(heightPt * scale);

        Document document = new Document(pageSize, 0, 0, 0, 0);
        try (OutputStream out = Files.newOutputStream(targetPdf)) {
            PdfWriter.getInstance(document, out);
            document.open();
            try {
                int pageCount = printable.getPageCount();
                for (int page = 0; page < pageCount; page++) {
                    if (page > 0) {
                        document.newPage();
                    }
                    BufferedImage raster = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2 = raster.createGraphics();
                    try {
                        g2.scale(scale, scale);
                        printable.paintPage(g2, page, widthPt, heightPt);
                    } finally {
                        g2.dispose();
                    }

                    Image pdfImg = Image.getInstance(raster, null);
                    pdfImg.setAbsolutePosition(0, 0);
                    pdfImg.scaleAbsolute(widthPt, heightPt);
                    document.add(pdfImg);
                }
            } finally {
                if (document.isOpen()) {
                    document.close();
                }
            }
        } catch (DocumentException e) {
            throw new IOException("Failed to export label PDF: " + e.getMessage(), e);
        }
    }
}
