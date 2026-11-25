package hu.due.rewear.service;

import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CompanyProfile;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.FinancialSummary;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.StockValuation;
import hu.due.rewear.ui.LogoAssets;
import hu.due.rewear.util.BusinessClock;
import hu.due.rewear.util.FormatUtil;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Generates Brutalist Monochrome A4 PDF Financial P&amp;L &amp; Stock Valuation Reports
 * exposing a {@link DocumentPageSource} for live preview, printing, PDF export, and email dispatch.
 * Preserves strictly separated per-currency ledgers (no forced FX conversion).
 */
public final class FinancialReportPdfExporter {

    private static final int ROWS_PER_FIRST_PAGE = 14;
    private static final int ROWS_PER_NEXT_PAGE = 28;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final I18nManager i18n;
    private final Supplier<CompanyProfile> companyProfileSupplier;

    public FinancialReportPdfExporter(I18nManager i18n, Supplier<CompanyProfile> companyProfileSupplier) {
        this.i18n = i18n;
        this.companyProfileSupplier = companyProfileSupplier != null
                ? companyProfileSupplier
                : CompanyProfile::defaults;
    }

    public DocumentPageSource createPageSource(String scopeLabel,
                                               FinancialSummary summary,
                                               StockValuation stockValuation,
                                               List<Invoice> invoices,
                                               Map<String, Integer> decByCur) {
        List<Invoice> safeInvoices = invoices == null ? List.of() : List.copyOf(invoices);
        int totalPages = computePageCount(safeInvoices.size());
        LocalDateTime generatedAt = BusinessClock.now();
        String safeScope = (scopeLabel == null || scopeLabel.isBlank())
                ? i18n.t("common.all").toUpperCase(Locale.ROOT)
                : scopeLabel;

        return new DocumentPageSource() {
            @Override
            public String getDocumentId() {
                return "financial-pnl-" + BusinessClock.today();
            }

            @Override
            public String getDisplayTitle() {
                return i18n.t("invoice.report.pdf_title") + " (" + safeScope + ")";
            }

            @Override
            public String getDefaultFileName() {
                return "rewear-pnl-report-" + BusinessClock.today() + ".pdf";
            }

            @Override
            public int getPageCount() {
                return totalPages;
            }

            @Override
            public void paintPage(Graphics2D g, int pageIndex, float pageWidthPt, float pageHeightPt) {
                int startIdx = pageIndex == 0 ? 0 : ROWS_PER_FIRST_PAGE + (pageIndex - 1) * ROWS_PER_NEXT_PAGE;
                int maxRows = pageIndex == 0 ? ROWS_PER_FIRST_PAGE : ROWS_PER_NEXT_PAGE;
                int endIdx = Math.min(safeInvoices.size(), startIdx + maxRows);
                List<Invoice> slice = startIdx < endIdx ? safeInvoices.subList(startIdx, endIdx) : List.of();
                FinancialReportPdfExporter.this.paintPage(g, pageWidthPt, pageHeightPt, safeScope, generatedAt,
                        summary, stockValuation, slice, decByCur, pageIndex + 1, totalPages);
            }
        };
    }

    public void export(String scopeLabel, FinancialSummary summary, StockValuation stockValuation,
                       List<Invoice> invoices, Map<String, Integer> decByCur, Path targetPdf) throws IOException {
        InvoicePdfExporter.exportCombined(
                List.of(createPageSource(scopeLabel, summary, stockValuation, invoices, decByCur)), targetPdf);
    }

    private static int computePageCount(int rowCount) {
        if (rowCount <= ROWS_PER_FIRST_PAGE) {
            return 1;
        }
        int remaining = rowCount - ROWS_PER_FIRST_PAGE;
        return 1 + (remaining + ROWS_PER_NEXT_PAGE - 1) / ROWS_PER_NEXT_PAGE;
    }

    private void paintPage(Graphics2D g, float pageW, float pageH, String scopeLabel, LocalDateTime generatedAt,
                           FinancialSummary summary, StockValuation valuation, List<Invoice> slice,
                           Map<String, Integer> decByCur, int pageNum, int totalPages) {
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
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        g.drawString(i18n.t("invoice.report.pdf_title").toUpperCase(Locale.ROOT), margin + 46, y + 20);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        CompanyProfile company = companyProfileSupplier.get();
        if (company == null) company = CompanyProfile.defaults();
        g.drawString(FormatUtil.truncate(company.name() + "  //  " + scopeLabel, 58), margin + 46, y + 34);

        String ts = TS_FMT.format(generatedAt);
        int tsW = g.getFontMetrics().stringWidth(ts);
        g.drawString(ts, margin + contentW - tsW - 10, y + 27);
        y += 56;

        if (pageNum == 1) {
            // Section 1: Per-Currency P&L Summary Table (No FX conversion)
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
            g.drawString(i18n.t("invoice.report.pnl_section").toUpperCase(Locale.ROOT), margin, y);
            y += 6;

            float[] pnlCols = {58f, 118f, 118f, 120f, contentW - 414f};
            String[] pnlHeaders = {
                    i18n.t("currency.label").toUpperCase(Locale.ROOT),
                    i18n.t("invoice.kpi.income").toUpperCase(Locale.ROOT),
                    i18n.t("invoice.kpi.expenses").toUpperCase(Locale.ROOT),
                    i18n.t("invoice.kpi.balance").toUpperCase(Locale.ROOT),
                    i18n.t("invoice.kpi.unpaid").toUpperCase(Locale.ROOT)
            };

            g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), 20);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
            float cx = margin;
            for (int i = 0; i < pnlHeaders.length; i++) {
                g.drawString(FormatUtil.truncate(pnlHeaders[i], 18), cx + 4, y + 13);
                cx += pnlCols[i];
            }
            y += 20;

            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
            g.setStroke(new BasicStroke(0.6f));
            for (FinancialSummary.CurrencyTotals t : summary.byCurrency().values()) {
                g.setColor(Color.BLACK);
                g.drawRect(Math.round(margin), Math.round(y), Math.round(contentW), 20);
                String[] cells = {
                        t.currency(),
                        "+" + CurrencyDef.formatAmount(t.totalIncome(), t.currency(), t.decimalPlaces()),
                        "-" + CurrencyDef.formatAmount(t.totalExpenses(), t.currency(), t.decimalPlaces()),
                        CurrencyDef.formatSignedAmount(t.netBalance(), t.currency(), t.decimalPlaces()),
                        CurrencyDef.formatAmount(t.unpaidAmount(), t.currency(), t.decimalPlaces())
                                + " (" + t.unpaidCount() + ")"
                };
                cx = margin;
                for (int i = 0; i < cells.length; i++) {
                    g.drawString(FormatUtil.truncate(cells[i], 20), cx + 4, y + 13);
                    cx += pnlCols[i];
                }
                y += 20;
            }
            y += 14;

            // Section 2: Live Warehouse Inventory Valuation Box
            StockValuation val = valuation != null ? valuation : StockValuation.zero();
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
            g.drawString(i18n.t("dashboard.valuation.title").toUpperCase(Locale.ROOT), margin, y);
            y += 6;
            g.setColor(new Color(0xF6F6F6));
            g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), 38);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(Math.round(margin), Math.round(y), Math.round(contentW), 38);

            float qW = contentW / 4f;
            paintStatCell(g, i18n.t("dashboard.kpi.bales").toUpperCase(Locale.ROOT),
                    Integer.toString(val.packageCount()), margin + 8, y + 13);
            paintStatCell(g, i18n.t("dashboard.kpi.weight").toUpperCase(Locale.ROOT),
                    FormatUtil.stripZeros(val.totalWeightKg()) + " kg", margin + qW + 8, y + 13);
            paintStatCell(g, "EUR",
                    CurrencyDef.formatAmount(val.totalEur(), "EUR", 2), margin + qW * 2 + 8, y + 13);
            paintStatCell(g, "HUF",
                    CurrencyDef.formatAmount(val.totalHuf(), "HUF", 0), margin + qW * 3 + 8, y + 13);
            y += 52;
        }

        // Section 3: Ledger Entries Table
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
        g.drawString(i18n.t("invoice.tab.list").toUpperCase(Locale.ROOT), margin, y);
        y += 6;

        float[] cols = {96f, 58f, 134f, 64f, 54f, contentW - 406f};
        String[] hdrs = {
                i18n.t("invoice.number").toUpperCase(Locale.ROOT),
                i18n.t("invoice.direction").toUpperCase(Locale.ROOT),
                i18n.t("invoice.partner").toUpperCase(Locale.ROOT),
                i18n.t("invoice.issue_date").toUpperCase(Locale.ROOT),
                i18n.t("common.status").toUpperCase(Locale.ROOT),
                i18n.t("invoice.gross_amount").toUpperCase(Locale.ROOT)
        };
        g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), 20);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
        float cx = margin;
        for (int i = 0; i < hdrs.length; i++) {
            g.drawString(FormatUtil.truncate(hdrs[i], 18), cx + 4, y + 13);
            cx += cols[i];
        }
        y += 20;

        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        g.setStroke(new BasicStroke(0.5f));
        for (int i = 0; i < slice.size(); i++) {
            Invoice inv = slice.get(i);
            if (i % 2 == 1) {
                g.setColor(new Color(0xFAFAFA));
                g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), 19);
            }
            g.setColor(Color.BLACK);
            g.drawLine(Math.round(margin), Math.round(y + 19), Math.round(margin + contentW), Math.round(y + 19));

            String cur = inv.effectiveCurrencyCode();
            int dec = decByCur != null ? decByCur.getOrDefault(cur, CurrencyDef.defaultDecimalsFor(cur))
                    : CurrencyDef.defaultDecimalsFor(cur);
            String[] cells = {
                    FormatUtil.truncate(inv.invoiceNumber(), 16),
                    i18n.t("invoice.direction.short." + inv.direction().name()),
                    FormatUtil.truncate(inv.partnerName(), 22),
                    inv.issueDate().toString(),
                    FormatUtil.truncate(i18n.t("status.invoice." + inv.status().name()).toUpperCase(Locale.ROOT), 10),
                    CurrencyDef.formatAmount(inv.grossAmount(), cur, dec)
            };
            cx = margin;
            for (int c = 0; c < cells.length; c++) {
                g.drawString(cells[c], cx + 4, y + 13);
                cx += cols[c];
            }
            y += 19;
        }

        // Footer
        g.setColor(new Color(0x555555));
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        String footer = "REWEAR WMS  //  " + i18n.t("invoice.report.pdf_title").toUpperCase(Locale.ROOT)
                + "  //  " + i18n.t("print.page_of", pageNum, totalPages);
        g.drawString(footer, margin, pageH - 22f);
    }

    private static void paintStatCell(Graphics2D g, String label, String value, float x, float y) {
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 7));
        g.setColor(new Color(0x555555));
        g.drawString(FormatUtil.truncate(label, 20), x, y);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
        g.setColor(Color.BLACK);
        g.drawString(FormatUtil.truncate(value, 20), x, y + 15);
    }
}
