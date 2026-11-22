package hu.due.rewear.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.PdfWriter;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CompanyProfile;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceItem;
import hu.due.rewear.ui.LogoAssets;
import hu.due.rewear.util.FormatUtil;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Generates Brutalist Monochrome A4 PDF Invoices (Outgoing & Incoming) using OpenPDF
 * and exposes a reusable {@link DocumentPageSource} for live preview, printing, and multi-document PDF export.
 */
public final class InvoicePdfExporter {

    private static final double EXPORT_DPI = 250.0;
    private static final int ROWS_PER_FIRST_PAGE = 16;
    private static final int ROWS_PER_NEXT_PAGE = 26;

    private final I18nManager i18n;
    private final QrCodeService qrService;
    private final Supplier<CompanyProfile> companyProfileSupplier;

    public InvoicePdfExporter(I18nManager i18n, QrCodeService qrService) {
        this(i18n, qrService, CompanyProfile::defaults);
    }

    public InvoicePdfExporter(I18nManager i18n, QrCodeService qrService,
                              Supplier<CompanyProfile> companyProfileSupplier) {
        this.i18n = i18n;
        this.qrService = qrService;
        this.companyProfileSupplier = companyProfileSupplier != null
                ? companyProfileSupplier
                : CompanyProfile::defaults;
    }

    public DocumentPageSource createPageSource(Invoice invoice, List<InvoiceItem> items) {
        List<InvoiceItem> safeItems = items == null ? List.of() : List.copyOf(items);
        int totalPages = computePageCount(safeItems.size());

        return new DocumentPageSource() {
            @Override
            public String getDocumentId() {
                return "invoice-" + invoice.invoiceNumber();
            }

            @Override
            public String getDisplayTitle() {
                return i18n.t("invoice.pdf.title") + " — " + invoice.invoiceNumber();
            }

            @Override
            public String getDefaultFileName() {
                return "invoice-" + invoice.invoiceNumber() + ".pdf";
            }

            @Override
            public int getPageCount() {
                return totalPages;
            }

            @Override
            public void paintPage(Graphics2D g, int pageIndex, float pageWidthPt, float pageHeightPt) {
                int startIdx = pageIndex == 0 ? 0 : ROWS_PER_FIRST_PAGE + (pageIndex - 1) * ROWS_PER_NEXT_PAGE;
                int maxRows = pageIndex == 0 ? ROWS_PER_FIRST_PAGE : ROWS_PER_NEXT_PAGE;
                int endIdx = Math.min(safeItems.size(), startIdx + maxRows);
                List<InvoiceItem> slice = startIdx < endIdx ? safeItems.subList(startIdx, endIdx) : List.of();
                boolean isLast = (pageIndex == totalPages - 1);
                InvoicePdfExporter.this.paintPage(g, pageWidthPt, pageHeightPt, invoice, slice, startIdx,
                        pageIndex + 1, totalPages, isLast);
            }
        };
    }

    public void export(Invoice invoice, List<InvoiceItem> items, Path targetPdf) throws IOException {
        exportCombined(List.of(createPageSource(invoice, items)), targetPdf);
    }

    /**
     * Exports one or more {@link DocumentPageSource} documents (e.g. Delivery Note + Invoice combined)
     * into a single crisp 250 DPI A4 PDF file.
     */
    public static void exportCombined(List<DocumentPageSource> sources, Path targetPdf) throws IOException {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("At least one document source is required.");
        }
        if (targetPdf.getParent() != null) {
            Files.createDirectories(targetPdf.getParent());
        }

        float pageW = PageSize.A4.getWidth();
        float pageH = PageSize.A4.getHeight();
        double scale = EXPORT_DPI / 72.0;
        int imgW = (int) Math.round(pageW * scale);
        int imgH = (int) Math.round(pageH * scale);

        Document document = new Document(PageSize.A4, 0, 0, 0, 0);
        try (OutputStream out = Files.newOutputStream(targetPdf)) {
            PdfWriter.getInstance(document, out);
            document.open();
            try {
                boolean firstPage = true;
                for (DocumentPageSource src : sources) {
                    int pages = src.getPageCount();
                    for (int p = 0; p < pages; p++) {
                        if (!firstPage) {
                            document.newPage();
                        }
                        firstPage = false;

                        BufferedImage raster = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g2 = raster.createGraphics();
                        try {
                            g2.scale(scale, scale);
                            src.paintPage(g2, p, pageW, pageH);
                        } finally {
                            g2.dispose();
                        }

                        Image pdfImg = Image.getInstance(raster, null);
                        pdfImg.setAbsolutePosition(0, 0);
                        pdfImg.scaleAbsolute(pageW, pageH);
                        document.add(pdfImg);
                    }
                }
            } finally {
                if (document.isOpen()) {
                    document.close();
                }
            }
        } catch (DocumentException e) {
            throw new IOException("Failed to export combined A4 PDF: " + e.getMessage(), e);
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
                           Invoice inv, List<InvoiceItem> slice,
                           int startIndex, int pageNum, int totalPages, boolean isLastPage) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, Math.round(pageW), Math.round(pageH));

        float margin = 36f;
        float contentW = pageW - 2 * margin;
        float y = margin;

        // Top Brutalist Header Bar
        g.setColor(Color.BLACK);
        g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), 44);
        LogoAssets.paintLogo(g, margin + 6, y + 6, 32, true);

        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        boolean isCreditNote = inv.category() == InvoiceCategory.CREDIT_NOTE;
        String title = isCreditNote
                ? i18n.t("invoice.credit_note.pdf_title").toUpperCase(Locale.ROOT)
                : i18n.t("invoice.pdf.title").toUpperCase(Locale.ROOT)
                        + " // " + i18n.t("invoice.direction." + inv.direction().name()).toUpperCase(Locale.ROOT);
        g.drawString(title, margin + 46, y + 27);

        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        int numW = g.getFontMetrics().stringWidth(inv.invoiceNumber());
        g.drawString(inv.invoiceNumber(), margin + contentW - numW - 10, y + 27);
        y += 56;

        if (pageNum == 1) {
            float qrSize = 78f;
            float colW = (contentW - qrSize - 24f) / 2f;
            float boxH = 104f;

            boolean isOutgoing = inv.direction() == InvoiceDirection.OUTGOING;
            String leftHeader = i18n.t("invoice.pdf.seller").toUpperCase(Locale.ROOT);
            String rightHeader = i18n.t("invoice.pdf.buyer").toUpperCase(Locale.ROOT);

            // Seller box
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(Math.round(margin), Math.round(y), Math.round(colW), Math.round(boxH));
            String invCur = inv.effectiveCurrencyCode();
            if (isOutgoing) {
                paintCompanyBlock(g, leftHeader, invCur, margin + 8, y + 14);
            } else {
                paintPartnerBlock(g, leftHeader, inv, margin + 8, y + 14);
            }

            // Buyer box
            float rx = margin + colW + 10f;
            g.drawRect(Math.round(rx), Math.round(y), Math.round(colW), Math.round(boxH));
            if (isOutgoing) {
                paintPartnerBlock(g, rightHeader, inv, rx + 8, y + 14);
            } else {
                paintCompanyBlock(g, rightHeader, invCur, rx + 8, y + 14);
            }

            // Right QR code
            float qx = margin + contentW - qrSize;
            BufferedImage qr = qrService.generateQrImage(inv.invoiceNumber(), 220);
            g.drawImage(qr, Math.round(qx), Math.round(y), Math.round(qrSize), Math.round(qrSize), null);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 7));
            int capW = g.getFontMetrics().stringWidth(inv.invoiceNumber());
            g.drawString(inv.invoiceNumber(), qx + (qrSize - capW) / 2f, y + qrSize + 12);

            y += boxH + 12;

            // Metadata Strip (Dates, Status, Category, Order Ref)
            float metaH = 38f;
            g.setColor(new Color(0xF6F6F6));
            g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), Math.round(metaH));
            g.setColor(Color.BLACK);
            g.drawRect(Math.round(margin), Math.round(y), Math.round(contentW), Math.round(metaH));

            float col4 = contentW / 4f;
            paintMetaCell(g, i18n.t("invoice.issue_date").toUpperCase(Locale.ROOT), inv.issueDate().toString(), margin + 8, y + 13);
            paintMetaCell(g, i18n.t("invoice.due_date").toUpperCase(Locale.ROOT), inv.dueDate().toString(), margin + col4 + 8, y + 13);
            paintMetaCell(g, i18n.t("common.status").toUpperCase(Locale.ROOT),
                    i18n.t("status.invoice." + inv.status().name()).toUpperCase(Locale.ROOT), margin + col4 * 2 + 8, y + 13);
            String refVal = inv.orderNumber() != null
                    ? inv.orderNumber()
                    : i18n.t("invoice.category." + inv.category().name());
            paintMetaCell(g, (inv.orderNumber() != null ? i18n.t("orders.order_number") : i18n.t("invoice.category")).toUpperCase(Locale.ROOT),
                    refVal, margin + col4 * 3 + 8, y + 13);

            y += metaH + 14;
        }

        // Line Items Table
        float[] colWidths = {28f, 250f, 55f, 42f, 70f, contentW - (28f + 250f + 55f + 42f + 70f)};
        String[] headers = {
                "#",
                i18n.t("invoice.item.description").toUpperCase(Locale.ROOT),
                i18n.t("invoice.item.qty").toUpperCase(Locale.ROOT),
                i18n.t("invoice.item.unit").toUpperCase(Locale.ROOT),
                i18n.t("invoice.item.unit_price").toUpperCase(Locale.ROOT),
                i18n.t("invoice.item.line_net").toUpperCase(Locale.ROOT)
        };

        float rowH = 21f;
        g.setColor(Color.BLACK);
        g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), Math.round(rowH));
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
        float cx = margin;
        for (int i = 0; i < headers.length; i++) {
            g.drawString(headers[i], cx + 4, y + 14);
            cx += colWidths[i];
        }
        y += rowH;

        String cur = inv.effectiveCurrencyCode();
        int decimals = inv.currencyDecimals();
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        for (int idx = 0; idx < slice.size(); idx++) {
            InvoiceItem item = slice.get(idx);
            if (idx % 2 == 1) {
                g.setColor(new Color(0xF6F6F6));
                g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), Math.round(rowH));
            }
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(0.5f));
            g.drawLine(Math.round(margin), Math.round(y + rowH), Math.round(margin + contentW), Math.round(y + rowH));

            String[] cells = {
                    Integer.toString(startIndex + idx + 1),
                    FormatUtil.truncate(item.description(), 46),
                    FormatUtil.stripZeros(item.quantity()),
                    item.unit(),
                    CurrencyDef.formatNumeric(item.unitPrice(), decimals),
                    CurrencyDef.formatNumeric(item.lineNet(), decimals)
            };
            cx = margin;
            for (int c = 0; c < cells.length; c++) {
                g.drawString(cells[c], cx + 4, y + 14);
                cx += colWidths[c];
            }
            y += rowH;
        }

        if (isLastPage) {
            y += 16;
            float sumW = 260f;
            float sx = margin + contentW - sumW;
            float sumH = 74f;
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(Math.round(sx), Math.round(y), Math.round(sumW), Math.round(sumH));

            // Tax exemption note for 0% international/reverse-charge invoices
            if (inv.vatRate() != null && inv.vatRate().compareTo(BigDecimal.ZERO) == 0) {
                g.setColor(new Color(0x444444));
                g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
                g.drawString("TAX CODE: " + inv.effectiveTaxTypeCode() + " (0% VAT)", margin, y + 18);
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
                g.drawString("Reverse charge / VAT-exempt supply", margin, y + 32);
                g.setColor(Color.BLACK);
            }

            String signPrefix = isCreditNote ? "-" : "";
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
            drawSummaryRow(g, i18n.t("invoice.net_amount").toUpperCase(Locale.ROOT) + ":",
                    signPrefix + CurrencyDef.formatAmount(inv.netAmount(), cur, decimals), sx + 10, sx + sumW - 10, y + 18);
            drawSummaryRow(g, i18n.t("invoice.vat").toUpperCase(Locale.ROOT)
                            + " (" + inv.effectiveTaxTypeCode() + " " + FormatUtil.stripZeros(inv.vatRate()) + "%):",
                    signPrefix + CurrencyDef.formatAmount(inv.vatAmount(), cur, decimals), sx + 10, sx + sumW - 10, y + 36);

            g.fillRect(Math.round(sx), Math.round(y + 46), Math.round(sumW), 28);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
            drawSummaryRow(g, i18n.t("invoice.gross_amount").toUpperCase(Locale.ROOT) + ":",
                    signPrefix + CurrencyDef.formatAmount(inv.grossAmount(), cur, decimals), sx + 10, sx + sumW - 10, y + 64);

            // Signature lines
            float sigY = Math.min(pageH - 70f, y + sumH + 54f);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(0.8f));
            g.drawLine(Math.round(margin), Math.round(sigY), Math.round(margin + 190), Math.round(sigY));
            g.drawLine(Math.round(margin + contentW - 190), Math.round(sigY), Math.round(margin + contentW), Math.round(sigY));
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
            g.drawString(i18n.t("invoice.pdf.issued_by").toUpperCase(Locale.ROOT), margin, sigY + 12);
            g.drawString(i18n.t("invoice.pdf.received_by").toUpperCase(Locale.ROOT), margin + contentW - 190, sigY + 12);
        }

        // Footer
        g.setColor(new Color(0x555555));
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        String footer = "REWEAR WMS  //  " + inv.invoiceNumber() + "  //  "
                + i18n.t("print.page_of", pageNum, totalPages);
        g.drawString(footer, margin, pageH - 22f);
    }

    private void paintCompanyBlock(Graphics2D g, String header, String currencyCode, float x, float y) {
        CompanyProfile company = companyProfileSupplier.get();
        if (company == null) {
            company = CompanyProfile.defaults();
        }
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
        g.setColor(new Color(0x555555));
        g.drawString(header, x, y);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
        g.drawString(FormatUtil.truncate(company.name(), 28), x, y + 16);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        g.drawString(FormatUtil.truncate(company.address(), 34), x, y + 32);
        g.drawString("TAX: " + FormatUtil.truncate(company.taxNumber(), 28), x, y + 46);
        g.drawString("EMAIL: " + FormatUtil.truncate(company.email(), 28), x, y + 60);
        g.drawString("IBAN (" + currencyCode + "): " + FormatUtil.truncate(company.ibanForCurrency(currencyCode), 28), x, y + 74);
    }

    private void paintPartnerBlock(Graphics2D g, String header, Invoice inv, float x, float y) {
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
        g.setColor(new Color(0x555555));
        g.drawString(header, x, y);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
        g.drawString(FormatUtil.truncate(inv.partnerName(), 28), x, y + 16);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        if (inv.partnerAddress() != null && !inv.partnerAddress().isBlank()) {
            g.drawString(FormatUtil.truncate(inv.partnerAddress(), 34), x, y + 32);
        }
        if (inv.partnerTaxNumber() != null && !inv.partnerTaxNumber().isBlank()) {
            g.drawString("TAX: " + inv.partnerTaxNumber(), x, y + 46);
        }
        if (inv.partnerEmail() != null && !inv.partnerEmail().isBlank()) {
            g.drawString("EMAIL: " + FormatUtil.truncate(inv.partnerEmail(), 28), x, y + 60);
        }
    }

    private static void paintMetaCell(Graphics2D g, String label, String value, float x, float y) {
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 7));
        g.setColor(new Color(0x555555));
        g.drawString(label, x, y);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
        g.setColor(Color.BLACK);
        g.drawString(FormatUtil.truncate(value, 20), x, y + 15);
    }

    private static void drawSummaryRow(Graphics2D g, String label, String value, float leftX, float rightX, float y) {
        g.drawString(label, leftX, y);
        int vw = g.getFontMetrics().stringWidth(value);
        g.drawString(value, rightX - vw, y);
    }
}
