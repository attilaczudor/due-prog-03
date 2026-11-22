package hu.due.rewear.service;

import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CompanyProfile;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.PartnerAgingStatement;
import hu.due.rewear.ui.LogoAssets;
import hu.due.rewear.util.FormatUtil;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Computes strictly separated multi-currency Partner Statements &amp; 30/60/90-day Aging Buckets
 * and renders Brutalist Monochrome A4 PDF Partner Statements via {@link DocumentPageSource}.
 */
public final class PartnerStatementService {

    private static final int ROWS_PER_FIRST_PAGE = 14;
    private static final int ROWS_PER_NEXT_PAGE = 28;

    private final I18nManager i18n;
    private final Supplier<CompanyProfile> companyProfileSupplier;

    public PartnerStatementService(I18nManager i18n, Supplier<CompanyProfile> companyProfileSupplier) {
        this.i18n = i18n;
        this.companyProfileSupplier = companyProfileSupplier != null
                ? companyProfileSupplier
                : CompanyProfile::defaults;
    }

    /**
     * Aggregates all non-cancelled invoices for {@code partner} into strictly separated per-currency
     * aging buckets relative to {@code asOfDate} (Current, 1–30d, 31–60d, 60+d overdue).
     */
    public PartnerAgingStatement computeStatement(Customer partner,
                                                  List<Invoice> partnerInvoices,
                                                  Map<String, Integer> decByCur,
                                                  LocalDate asOfDate) {
        LocalDate ref = asOfDate != null ? asOfDate : LocalDate.now();
        Map<String, MutableAging> acc = new LinkedHashMap<>();

        if (partnerInvoices != null) {
            for (Invoice inv : partnerInvoices) {
                if (inv.status() == InvoiceStatus.CANCELLED) {
                    continue;
                }
                String cur = inv.effectiveCurrencyCode();
                int dec = decByCur != null
                        ? decByCur.getOrDefault(cur, CurrencyDef.defaultDecimalsFor(cur))
                        : CurrencyDef.defaultDecimalsFor(cur);
                MutableAging m = acc.computeIfAbsent(cur, k -> new MutableAging(k, dec));
                BigDecimal amt = CurrencyDef.roundForScale(
                        inv.grossAmount() != null ? inv.grossAmount() : BigDecimal.ZERO, dec);
                m.totalBilled = m.totalBilled.add(amt);

                if (inv.status() == InvoiceStatus.PAID) {
                    m.totalPaid = m.totalPaid.add(amt);
                } else if (inv.status() == InvoiceStatus.ISSUED || inv.status() == InvoiceStatus.OVERDUE) {
                    m.openCount++;
                    m.totalOpen = m.totalOpen.add(amt);
                    long daysPastDue = ChronoUnit.DAYS.between(inv.dueDate(), ref);
                    if (daysPastDue <= 0) {
                        m.currentNotDue = m.currentNotDue.add(amt);
                    } else if (daysPastDue <= 30) {
                        m.overdue1to30 = m.overdue1to30.add(amt);
                    } else if (daysPastDue <= 60) {
                        m.overdue31to60 = m.overdue31to60.add(amt);
                    } else {
                        m.overdue60Plus = m.overdue60Plus.add(amt);
                    }
                }
            }
        }

        Map<String, PartnerAgingStatement.CurrencyAging> result = new LinkedHashMap<>();
        for (MutableAging m : acc.values()) {
            result.put(m.currency, new PartnerAgingStatement.CurrencyAging(
                    m.currency, m.decimals,
                    CurrencyDef.roundForScale(m.totalBilled, m.decimals),
                    CurrencyDef.roundForScale(m.totalPaid, m.decimals),
                    CurrencyDef.roundForScale(m.currentNotDue, m.decimals),
                    CurrencyDef.roundForScale(m.overdue1to30, m.decimals),
                    CurrencyDef.roundForScale(m.overdue31to60, m.decimals),
                    CurrencyDef.roundForScale(m.overdue60Plus, m.decimals),
                    CurrencyDef.roundForScale(m.totalOpen, m.decimals),
                    m.openCount
            ));
        }
        return new PartnerAgingStatement(partner, ref, result);
    }

    public DocumentPageSource createPageSource(PartnerAgingStatement statement,
                                               List<Invoice> partnerInvoices,
                                               Map<String, Integer> decByCur) {
        List<Invoice> safeInvoices = partnerInvoices == null ? List.of() : List.copyOf(partnerInvoices);
        int totalPages = computePageCount(safeInvoices.size());
        String partnerSafeFile = statement.partner().name().replaceAll("[^A-Za-z0-9_-]", "_");

        return new DocumentPageSource() {
            @Override
            public String getDocumentId() {
                return "partner-statement-" + statement.partner().id() + "-" + statement.asOfDate();
            }

            @Override
            public String getDisplayTitle() {
                return i18n.t("partners.statement.pdf_title") + " — " + statement.partner().name();
            }

            @Override
            public String getDefaultFileName() {
                return "statement-" + partnerSafeFile + "-" + statement.asOfDate() + ".pdf";
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
                PartnerStatementService.this.paintPage(g, pageWidthPt, pageHeightPt, statement, slice, decByCur,
                        pageIndex + 1, totalPages);
            }
        };
    }

    public void export(PartnerAgingStatement statement, List<Invoice> partnerInvoices,
                       Map<String, Integer> decByCur, Path targetPdf) throws IOException {
        InvoicePdfExporter.exportCombined(List.of(createPageSource(statement, partnerInvoices, decByCur)), targetPdf);
    }

    private static int computePageCount(int rowCount) {
        if (rowCount <= ROWS_PER_FIRST_PAGE) {
            return 1;
        }
        int remaining = rowCount - ROWS_PER_FIRST_PAGE;
        return 1 + (remaining + ROWS_PER_NEXT_PAGE - 1) / ROWS_PER_NEXT_PAGE;
    }

    private void paintPage(Graphics2D g, float pageW, float pageH, PartnerAgingStatement st,
                           List<Invoice> slice, Map<String, Integer> decByCur, int pageNum, int totalPages) {
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
        g.drawString(i18n.t("partners.statement.pdf_title").toUpperCase(Locale.ROOT), margin + 46, y + 20);
        CompanyProfile company = companyProfileSupplier.get();
        if (company == null) company = CompanyProfile.defaults();
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        g.drawString(FormatUtil.truncate(company.name() + "  //  " + st.partner().name(), 58), margin + 46, y + 34);

        String dateStr = st.asOfDate().toString();
        int dw = g.getFontMetrics().stringWidth(dateStr);
        g.drawString(dateStr, margin + contentW - dw - 10, y + 27);
        y += 56;

        if (pageNum == 1) {
            // Partner Box
            Customer p = st.partner();
            g.setColor(new Color(0xF6F6F6));
            g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), 44);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(Math.round(margin), Math.round(y), Math.round(contentW), 44);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
            g.drawString(FormatUtil.truncate(p.name(), 45) + "  [" + p.effectiveCountryCode() + " / " + p.effectiveCurrencyCode() + "]",
                    margin + 8, y + 16);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
            g.drawString("TAX: " + FormatUtil.safe(p.taxNumber()) + "   |   EMAIL: " + FormatUtil.safe(p.email())
                    + "   |   " + FormatUtil.truncate(FormatUtil.safe(p.address()), 38), margin + 8, y + 33);
            y += 56;

            // Multi-Currency Aging Buckets Table
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
            g.drawString(i18n.t("partners.statement.aging_section").toUpperCase(Locale.ROOT), margin, y);
            y += 6;

            float[] ageCols = {48f, 92f, 92f, 92f, 92f, contentW - 416f};
            String[] ageHdrs = {
                    i18n.t("currency.label").toUpperCase(Locale.ROOT),
                    i18n.t("partners.statement.current").toUpperCase(Locale.ROOT),
                    "1–30 D",
                    "31–60 D",
                    "60+ D",
                    i18n.t("partners.statement.total_open").toUpperCase(Locale.ROOT)
            };
            g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), 20);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
            float cx = margin;
            for (int i = 0; i < ageHdrs.length; i++) {
                g.drawString(FormatUtil.truncate(ageHdrs[i], 14), cx + 4, y + 13);
                cx += ageCols[i];
            }
            y += 20;

            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
            g.setStroke(new BasicStroke(0.6f));
            if (st.byCurrency().isEmpty()) {
                g.setColor(Color.BLACK);
                g.drawRect(Math.round(margin), Math.round(y), Math.round(contentW), 20);
                g.drawString("—", margin + 8, y + 13);
                y += 20;
            } else {
                for (PartnerAgingStatement.CurrencyAging a : st.byCurrency().values()) {
                    g.setColor(Color.BLACK);
                    g.drawRect(Math.round(margin), Math.round(y), Math.round(contentW), 20);
                    String[] cells = {
                            a.currency(),
                            CurrencyDef.formatAmount(a.currentNotDue(), a.currency(), a.decimalPlaces()),
                            CurrencyDef.formatAmount(a.overdue1to30(), a.currency(), a.decimalPlaces()),
                            CurrencyDef.formatAmount(a.overdue31to60(), a.currency(), a.decimalPlaces()),
                            CurrencyDef.formatAmount(a.overdue60Plus(), a.currency(), a.decimalPlaces()),
                            CurrencyDef.formatAmount(a.totalOpen(), a.currency(), a.decimalPlaces()) + " (" + a.openInvoiceCount() + ")"
                    };
                    cx = margin;
                    for (int i = 0; i < cells.length; i++) {
                        g.drawString(FormatUtil.truncate(cells[i], 16), cx + 4, y + 13);
                        cx += ageCols[i];
                    }
                    y += 20;
                }
            }
            y += 14;
        }

        // Invoice History Table
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
        g.drawString(i18n.t("invoice.tab.list").toUpperCase(Locale.ROOT), margin, y);
        y += 6;

        float[] cols = {104f, 64f, 66f, 66f, 70f, contentW - 370f};
        String[] hdrs = {
                i18n.t("invoice.number").toUpperCase(Locale.ROOT),
                i18n.t("invoice.direction").toUpperCase(Locale.ROOT),
                i18n.t("invoice.issue_date").toUpperCase(Locale.ROOT),
                i18n.t("invoice.due_date").toUpperCase(Locale.ROOT),
                i18n.t("common.status").toUpperCase(Locale.ROOT),
                i18n.t("invoice.gross_amount").toUpperCase(Locale.ROOT)
        };
        g.fillRect(Math.round(margin), Math.round(y), Math.round(contentW), 20);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
        float cx = margin;
        for (int i = 0; i < hdrs.length; i++) {
            g.drawString(FormatUtil.truncate(hdrs[i], 16), cx + 4, y + 13);
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
                    FormatUtil.truncate(inv.invoiceNumber(), 18),
                    i18n.t("invoice.direction.short." + inv.direction().name()),
                    inv.issueDate().toString(),
                    inv.dueDate().toString(),
                    FormatUtil.truncate(i18n.t("status.invoice." + inv.status().name()).toUpperCase(Locale.ROOT), 12),
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
        String footer = "REWEAR WMS  //  " + st.partner().name()
                + "  //  " + i18n.t("print.page_of", pageNum, totalPages);
        g.drawString(footer, margin, pageH - 22f);
    }

    private static final class MutableAging {
        final String currency;
        final int decimals;
        BigDecimal totalBilled = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal currentNotDue = BigDecimal.ZERO;
        BigDecimal overdue1to30 = BigDecimal.ZERO;
        BigDecimal overdue31to60 = BigDecimal.ZERO;
        BigDecimal overdue60Plus = BigDecimal.ZERO;
        BigDecimal totalOpen = BigDecimal.ZERO;
        int openCount = 0;

        MutableAging(String currency, int decimals) {
            this.currency = currency;
            this.decimals = decimals;
        }
    }
}
