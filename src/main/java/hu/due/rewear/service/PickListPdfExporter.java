package hu.due.rewear.service;

import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.service.PickListService.OrderProgress;
import hu.due.rewear.service.PickListService.PickLine;
import hu.due.rewear.service.PickListService.PickList;
import hu.due.rewear.ui.LogoAssets;
import hu.due.rewear.util.BusinessClock;
import hu.due.rewear.util.FormatUtil;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * A4 pick list in the same monochrome style as the delivery note: what to collect, in walking order, with
 * a tick box per package and the progress so far. Already staged packages are listed last and struck out so
 * a half-finished list can be reprinted without confusing the driver.
 */
public final class PickListPdfExporter {

    private static final int ROWS_FIRST_PAGE = 20;
    private static final int ROWS_NEXT_PAGE = 32;
    private static final float ROW_H = 22f;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final I18nManager i18n;

    public PickListPdfExporter(I18nManager i18n) {
        this.i18n = i18n;
    }

    public DocumentPageSource createPageSource(PickList list) {
        LocalDateTime printedAt = BusinessClock.now();
        int pages = pageCount(list.total());
        return new DocumentPageSource() {
            @Override public String getDocumentId() {
                return "pick-list-" + FILE_STAMP.format(printedAt);
            }

            @Override public String getDisplayTitle() {
                return i18n.t("picklist.title") + " — " + STAMP.format(printedAt);
            }

            @Override public String getDefaultFileName() {
                return "pick-list-" + FILE_STAMP.format(printedAt) + ".pdf";
            }

            @Override public int getPageCount() {
                return pages;
            }

            @Override public void paintPage(Graphics2D g, int pageIndex, float pageWidthPt, float pageHeightPt) {
                int start = pageIndex == 0 ? 0 : ROWS_FIRST_PAGE + (pageIndex - 1) * ROWS_NEXT_PAGE;
                int rows = pageIndex == 0 ? ROWS_FIRST_PAGE : ROWS_NEXT_PAGE;
                int end = Math.min(list.total(), start + rows);
                List<PickLine> slice = start < end ? list.lines().subList(start, end) : List.of();
                PickListPdfExporter.this.paint(g, pageWidthPt, pageHeightPt, list, slice, start,
                        pageIndex + 1, pages, printedAt);
            }
        };
    }

    public void export(PickList list, Path targetPdf) throws IOException {
        InvoicePdfExporter.exportCombined(List.of(createPageSource(list)), targetPdf);
    }

    static int pageCount(int lines) {
        if (lines <= ROWS_FIRST_PAGE) {
            return 1;
        }
        return 1 + (lines - ROWS_FIRST_PAGE + ROWS_NEXT_PAGE - 1) / ROWS_NEXT_PAGE;
    }

    private void paint(Graphics2D g, float pageW, float pageH, PickList list, List<PickLine> slice,
                       int startIndex, int pageNum, int pages, LocalDateTime printedAt) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, Math.round(pageW), Math.round(pageH));

        float margin = 36f;
        float contentW = pageW - 2 * margin;
        float y = margin;

        g.setColor(Color.BLACK);
        g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), 40);
        LogoAssets.paintLogo(g, margin + 6, y + 4, 32, true);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        g.drawString(i18n.t("picklist.title"), margin + 46, y + 25);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        String stamp = STAMP.format(printedAt);
        g.drawString(stamp, margin + contentW - g.getFontMetrics().stringWidth(stamp) - 10, y + 25);
        y += 50;

        if (pageNum == 1) {
            y = paintSummary(g, margin, y, contentW, list);
        }

        float[] colX = {margin, margin + 26, margin + 110, margin + 290, margin + 345, margin + 420};
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1.5f));
        g.drawLine(Math.round(margin), Math.round(y), Math.round(margin + contentW), Math.round(y));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
        g.drawString("#", colX[0] + 3, y + 14);
        g.drawString(i18n.t("intake.location").toUpperCase(Locale.ROOT), colX[1] + 2, y + 14);
        g.drawString(i18n.t("intake.generated_code").toUpperCase(Locale.ROOT), colX[2] + 2, y + 14);
        g.drawString("KG", colX[3] + 2, y + 14);
        g.drawString(i18n.t("orders.order_number").toUpperCase(Locale.ROOT), colX[4] + 2, y + 14);
        g.drawString(i18n.t("orders.customer").toUpperCase(Locale.ROOT), colX[5] + 2, y + 14);
        y += ROW_H;
        g.drawLine(Math.round(margin), Math.round(y), Math.round(margin + contentW), Math.round(y));

        g.setStroke(new BasicStroke(0.5f));
        for (int i = 0; i < slice.size(); i++) {
            PickLine line = slice.get(i);
            float textY = y + 14;
            Color ink = line.picked() ? new Color(0x888888) : Color.BLACK;
            g.setColor(ink);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
            g.drawString(Integer.toString(startIndex + i + 1), colX[0] + 3, textY);
            // Tick box: empty to collect, crossed once the package is at the dock.
            g.drawRect(Math.round(colX[1] + 2), Math.round(y + 5), 10, 10);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
            if (line.picked()) {
                g.drawLine(Math.round(colX[1] + 2), Math.round(y + 5), Math.round(colX[1] + 12), Math.round(y + 15));
                g.drawLine(Math.round(colX[1] + 2), Math.round(y + 15), Math.round(colX[1] + 12), Math.round(y + 5));
            }
            g.drawString(truncate(line.locationCode(), g.getFontMetrics(), 62), colX[1] + 18, textY);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(truncate(line.qrCode(), fm, 176), colX[2] + 2, textY);
            g.drawString(FormatUtil.stripZeros(line.weightKg()), colX[3] + 2, textY);
            g.drawString(truncate(line.orderNumber(), fm, 70), colX[4] + 2, textY);
            String customer = line.customerName() + (line.dockCode().isEmpty() ? "" : " → " + line.dockCode());
            g.drawString(truncate(customer, fm, contentW - 420 - 4), colX[5] + 2, textY);
            if (line.picked()) {
                float strikeY = y + 11;
                g.drawLine(Math.round(colX[2]), Math.round(strikeY), Math.round(colX[4] - 6), Math.round(strikeY));
            }
            y += ROW_H;
            g.setColor(new Color(0xCCCCCC));
            g.drawLine(Math.round(margin), Math.round(y), Math.round(margin + contentW), Math.round(y));
        }

        if (list.isEmpty() && pageNum == 1) {
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            g.drawString(i18n.t("picklist.empty"), margin + 4, y + 20);
        }

        g.setColor(new Color(0x555555));
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        String pageStr = i18n.t("print.page_of", pageNum, pages);
        g.drawString("REWEAR WMS — " + i18n.t("picklist.title"), margin, pageH - 22);
        g.drawString(pageStr, margin + contentW - g.getFontMetrics().stringWidth(pageStr), pageH - 22);
    }

    private float paintSummary(Graphics2D g, float margin, float y, float contentW, PickList list) {
        List<OrderProgress> orders = list.byOrder();
        int shown = Math.min(orders.size(), 5);
        float boxH = 52f + shown * 12f;
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(Math.round(margin), Math.round(y), Math.round(contentW), Math.round(boxH));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        g.drawString(i18n.t("picklist.collected", Integer.toString(list.picked()), Integer.toString(list.total()),
                Integer.toString(list.percentDone())), margin + 10, y + 20);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
        g.drawString(i18n.t("picklist.remaining_weight", FormatUtil.stripZeros(list.remainingWeightKg())),
                margin + 10, y + 36);
        float oy = y + 50;
        for (int i = 0; i < shown; i++) {
            OrderProgress o = orders.get(i);
            g.drawString(truncate((o.complete() ? "[x] " : "[ ] ") + o.orderNumber() + "  " + o.customerName()
                    + "  " + o.picked() + "/" + o.total(), g.getFontMetrics(), contentW - 20), margin + 10, oy);
            oy += 12;
        }
        if (orders.size() > shown) {
            g.drawString("+" + (orders.size() - shown), margin + 10, oy);
        }
        return y + boxH + 16;
    }

    private static String truncate(String text, FontMetrics fm, float maxW) {
        if (text == null) {
            return "";
        }
        if (fm.stringWidth(text) <= maxW) {
            return text;
        }
        int len = text.length();
        while (len > 1 && fm.stringWidth(text.substring(0, len) + "..") > maxW) {
            len--;
        }
        return text.substring(0, Math.max(0, len)) + "..";
    }
}
