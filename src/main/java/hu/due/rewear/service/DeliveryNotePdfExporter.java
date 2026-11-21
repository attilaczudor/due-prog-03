package hu.due.rewear.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.PdfWriter;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import hu.due.rewear.ui.LogoAssets;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates clean, brutalist monochrome A4 PDF Delivery Notes / Packing Lists using OpenPDF.
 * Renders at 250 DPI via Java2D so all Hungarian UTF-8 characters and QR codes print crisply on any OS.
 */
public final class DeliveryNotePdfExporter {

    private static final double EXPORT_DPI = 250.0;
    private static final int ROWS_PER_FIRST_PAGE = 18;
    private static final int ROWS_PER_NEXT_PAGE = 28;
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final I18nManager i18n;
    private final QrCodeService qrService;

    public DeliveryNotePdfExporter(I18nManager i18n, QrCodeService qrService) {
        this.i18n = i18n;
        this.qrService = qrService;
    }

    public void export(Order order, Customer customer, List<OrderItem> items, Path targetPdf) throws IOException {
        if (order == null) {
            throw new IllegalArgumentException("Order is required");
        }
        if (targetPdf.getParent() != null) {
            Files.createDirectories(targetPdf.getParent());
        }

        float pageW = PageSize.A4.getWidth();
        float pageH = PageSize.A4.getHeight();
        double scale = EXPORT_DPI / 72.0;
        int imgW = (int) Math.round(pageW * scale);
        int imgH = (int) Math.round(pageH * scale);

        int totalItems = items == null ? 0 : items.size();
        int totalPages = computePageCount(totalItems);

        Document document = new Document(PageSize.A4, 0, 0, 0, 0);
        try (OutputStream out = Files.newOutputStream(targetPdf)) {
            PdfWriter.getInstance(document, out);
            document.open();
            try {
                int cursor = 0;
                for (int page = 0; page < totalPages; page++) {
                    if (page > 0) {
                        document.newPage();
                    }
                    int count = page == 0
                            ? Math.min(ROWS_PER_FIRST_PAGE, totalItems - cursor)
                            : Math.min(ROWS_PER_NEXT_PAGE, totalItems - cursor);
                    List<OrderItem> slice = count > 0 ? items.subList(cursor, cursor + count) : List.of();
                    boolean isLastPage = (page == totalPages - 1);

                    BufferedImage raster = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2 = raster.createGraphics();
                    try {
                        g2.scale(scale, scale);
                        paintPage(g2, pageW, pageH, order, customer, slice, cursor, page + 1, totalPages, isLastPage);
                    } finally {
                        g2.dispose();
                    }

                    Image pdfImg = Image.getInstance(raster, null);
                    pdfImg.setAbsolutePosition(0, 0);
                    pdfImg.scaleAbsolute(pageW, pageH);
                    document.add(pdfImg);

                    cursor += count;
                }
            } finally {
                if (document.isOpen()) {
                    document.close();
                }
            }
        } catch (DocumentException e) {
            throw new IOException("Failed to export delivery note PDF: " + e.getMessage(), e);
        }
    }

    private static int computePageCount(int totalItems) {
        if (totalItems <= ROWS_PER_FIRST_PAGE) {
            return 1;
        }
        int remaining = totalItems - ROWS_PER_FIRST_PAGE;
        return 1 + (remaining + ROWS_PER_NEXT_PAGE - 1) / ROWS_PER_NEXT_PAGE;
    }

    private void paintPage(Graphics2D g, float pageW, float pageH,
                           Order order, Customer customer, List<OrderItem> slice,
                           int startIndex, int pageNum, int totalPages, boolean isLastPage) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, Math.round(pageW), Math.round(pageH));

        String lang = i18n.getActiveLanguage();
        float margin = 36f;
        float contentW = pageW - 2 * margin;
        float y = margin;

        // Top Brutalist Header Bar with crisp ReWear logo
        g.setColor(Color.BLACK);
        g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), 40);
        BufferedImage logoWhite = LogoAssets.logoImage(true);
        float titleStartX = margin + 10;
        if (logoWhite != null) {
            g.drawImage(logoWhite, Math.round(margin + 6), Math.round(y + 4), 32, 32, null);
            titleStartX = margin + 46;
        }
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        g.drawString(i18n.t("orders.delivery_note.title"), titleStartX, y + 25);

        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        String ordTag = order.orderNumber();
        int ordW = g.getFontMetrics().stringWidth(ordTag);
        g.drawString(ordTag, margin + contentW - ordW - 10, y + 25);
        y += 50;

        if (pageNum == 1) {
            // Two-column Metadata + Order QR Box
            float boxH = 96f;
            float qrBoxW = 92f;
            float leftW = (contentW - qrBoxW - 16) * 0.52f;
            float midW = (contentW - qrBoxW - 16) * 0.48f;

            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(Math.round(margin), Math.round(y), Math.round(leftW), Math.round(boxH));
            g.drawRect(Math.round(margin + leftW + 8), Math.round(y), Math.round(midW), Math.round(boxH));
            g.drawRect(Math.round(margin + leftW + midW + 16), Math.round(y), Math.round(qrBoxW), Math.round(boxH));

            // Customer box
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
            g.drawString(i18n.t("orders.customer").toUpperCase(), margin + 8, y + 14);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
            String custName = customer != null ? customer.name() : order.customerName();
            g.drawString(truncate(custName, g.getFontMetrics(), leftW - 16), margin + 8, y + 30);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
            if (customer != null) {
                g.drawString(truncate(safe(customer.address()), g.getFontMetrics(), leftW - 16), margin + 8, y + 45);
                g.drawString(truncate(safe(customer.contactName()) + "  " + safe(customer.phone()),
                        g.getFontMetrics(), leftW - 16), margin + 8, y + 60);
                g.drawString(truncate("TAX: " + safe(customer.taxNumber()),
                        g.getFontMetrics(), leftW - 16), margin + 8, y + 75);
                g.drawString(truncate(safe(customer.email()), g.getFontMetrics(), leftW - 16), margin + 8, y + 88);
            }

            // Order metadata box
            float mx = margin + leftW + 16;
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
            g.drawString(i18n.t("orders.order_number").toUpperCase() + ": " + order.orderNumber(), mx, y + 16);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
            g.drawString(i18n.t("common.status") + ": " + i18n.t("status.order." + order.status().name()), mx, y + 34);
            g.drawString(i18n.t("common.created_at") + ": " + (order.createdAt() != null ? DT_FMT.format(order.createdAt()) : "-"), mx, y + 49);
            g.drawString("Shipped: " + (order.shippedAt() != null ? DT_FMT.format(order.shippedAt()) : "-"), mx, y + 64);
            g.drawString(truncate("By: " + safe(order.createdByFullName()), g.getFontMetrics(), midW - 16), mx, y + 79);

            // Order QR Code
            BufferedImage qrImg = qrService.generateQrImage(order.orderNumber(), 240);
            int qx = Math.round(margin + leftW + midW + 22);
            int qy = Math.round(y + 8);
            g.drawImage(qrImg, qx, qy, 80, 80, null);

            y += boxH + 16;
        }

        // Itemized Table Header
        float[] colX = new float[]{
                margin,
                margin + 24,
                margin + 205,
                margin + 375,
                margin + 425,
                margin + 472
        };
        float rowH = 22f;

        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1.5f));
        g.drawLine(Math.round(margin), Math.round(y), Math.round(margin + contentW), Math.round(y));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
        g.drawString("#", colX[0] + 3, y + 14);
        g.drawString("QR CODE", colX[1] + 2, y + 14);
        g.drawString("DESCRIPTION", colX[2] + 2, y + 14);
        g.drawString("KG", colX[3] + 2, y + 14);
        g.drawString("EUR/KG", colX[4] + 2, y + 14);
        g.drawString("TOTAL", colX[5] + 2, y + 14);
        y += rowH;
        g.drawLine(Math.round(margin), Math.round(y), Math.round(margin + contentW), Math.round(y));

        // Item Rows
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        g.setStroke(new BasicStroke(0.5f));
        for (int i = 0; i < slice.size(); i++) {
            OrderItem item = slice.get(i);
            float textY = y + 14;
            g.setColor(Color.BLACK);
            g.drawString(Integer.toString(startIndex + i + 1), colX[0] + 3, textY);
            g.drawString(truncate(item.packageQrCode(), g.getFontMetrics(), 176), colX[1] + 2, textY);
            g.drawString(truncate(item.localizedSummary(lang), g.getFontMetrics(), 165), colX[2] + 2, textY);
            g.drawString(stripZeros(item.weightKg()), colX[3] + 2, textY);
            g.drawString(formatMoney(item.pricePerKg()), colX[4] + 2, textY);
            g.drawString(formatMoney(item.lineTotal()), colX[5] + 2, textY);

            y += rowH;
            g.setColor(new Color(0xCCCCCC));
            g.drawLine(Math.round(margin), Math.round(y), Math.round(margin + contentW), Math.round(y));
        }

        // Totals & Signatures on last page
        if (isLastPage) {
            y += 14;
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1.5f));
            float sumBoxW = 225f;
            float sumBoxX = margin + contentW - sumBoxW;
            g.drawRect(Math.round(sumBoxX), Math.round(y), Math.round(sumBoxW), 48);

            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
            g.drawString(i18n.t("orders.total_weight") + ": " + stripZeros(order.totalWeightKg()) + " kg",
                    sumBoxX + 10, y + 19);
            g.drawString(i18n.t("orders.total_price") + ": " + formatMoney(order.totalPrice()) + " EUR",
                    sumBoxX + 10, y + 38);

            // Signature lines near bottom
            float sigY = pageH - 82;
            g.setStroke(new BasicStroke(1f));
            g.drawLine(Math.round(margin), Math.round(sigY), Math.round(margin + 210), Math.round(sigY));
            g.drawLine(Math.round(margin + contentW - 210), Math.round(sigY), Math.round(margin + contentW), Math.round(sigY));

            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
            g.drawString(i18n.t("orders.delivery_note.signature_sender"), margin, sigY + 14);
            g.drawString(i18n.t("orders.delivery_note.signature_receiver"), margin + contentW - 210, sigY + 14);
        }

        // Footer page number
        g.setColor(new Color(0x555555));
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        String pageStr = i18n.t("print.page_of", pageNum, totalPages);
        g.drawString("REWEAR WMS — " + order.orderNumber(), margin, pageH - 22);
        g.drawString(pageStr, margin + contentW - g.getFontMetrics().stringWidth(pageStr), pageH - 22);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String stripZeros(BigDecimal val) {
        if (val == null) return "0";
        BigDecimal s = val.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static String formatMoney(BigDecimal val) {
        return val == null ? "0.00" : val.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String truncate(String text, FontMetrics fm, float maxW) {
        if (text == null) return "";
        if (fm.stringWidth(text) <= maxW) return text;
        int len = text.length();
        while (len > 1 && fm.stringWidth(text.substring(0, len) + "..") > maxW) {
            len--;
        }
        return text.substring(0, Math.max(0, len)) + "..";
    }
}
