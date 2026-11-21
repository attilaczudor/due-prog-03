package hu.due.rewear.service;

import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.ui.LogoAssets;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * Vector Graphics2D painter rendering a realistic logistics QR ticket label
 * consistently across Swing Print Preview, Java PrinterJob, and OpenPDF export.
 */
public final class TicketLabelPainter {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final QrCodeService qrService;
    private final I18nManager i18n;

    public TicketLabelPainter(QrCodeService qrService, I18nManager i18n) {
        this.qrService = qrService;
        this.i18n = i18n;
    }

    public void paintTicket(Graphics2D g2, InventoryPackage pkg, double x, double y, double width, double height) {
        Graphics2D g = (Graphics2D) g2.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            g.translate(x, y);
            String lang = i18n.getActiveLanguage();

            double pad = Math.min(width, height) * 0.045;
            double innerX = pad;
            double innerW = width - 2 * pad;

            // Background & Outer Border
            g.setColor(Color.WHITE);
            g.fill(new Rectangle2D.Double(0, 0, width, height));
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(Math.max(1.2f, (float) (width * 0.006))));
            g.draw(new Rectangle2D.Double(pad * 0.5, pad * 0.5, width - pad, height - pad));

            // Swiss Brutalist Header Band: Crisp ReWear Logo on left + Inverted Location Badge on right
            double headerH = height * 0.145;
            double cursorY = pad * 0.5;
            double headerBottomY = cursorY + headerH;

            BufferedImage logo = LogoAssets.logoImage(false);
            if (logo != null) {
                double logoSize = headerH * 0.84;
                double logoX = innerX + pad * 0.25;
                double logoY = cursorY + (headerH - logoSize) / 2.0;
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(logo, (int) Math.round(logoX), (int) Math.round(logoY),
                        (int) Math.round(logoSize), (int) Math.round(logoSize), null);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            }

            // Inverted black location badge on the right of the header
            String locBadge = "LOC: " + pkg.locationCode();
            Font locFont = new Font(Font.MONOSPACED, Font.BOLD, Math.max(8, (int) (headerH * 0.30)));
            g.setFont(locFont);
            FontMetrics fmLoc = g.getFontMetrics();
            double badgePadX = pad * 0.55;
            double badgeW = fmLoc.stringWidth(locBadge) + 2 * badgePadX;
            double badgeH = fmLoc.getHeight() + pad * 0.45;
            double badgeX = width - innerX - badgeW - pad * 0.2;
            double badgeY = cursorY + (headerH - badgeH) / 2.0;

            g.setColor(Color.BLACK);
            g.fill(new Rectangle2D.Double(badgeX, badgeY, badgeW, badgeH));
            g.setColor(Color.WHITE);
            g.drawString(locBadge, (float) (badgeX + badgePadX),
                    (float) (badgeY + (badgeH + fmLoc.getAscent() - fmLoc.getDescent()) / 2.0));

            // Heavy 2pt bottom border under sticker header
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(Math.max(1.5f, (float) (width * 0.007))));
            g.drawLine((int) Math.round(pad * 0.5), (int) Math.round(headerBottomY),
                    (int) Math.round(width - pad * 0.5), (int) Math.round(headerBottomY));

            cursorY = headerBottomY + pad * 0.5;

            // QR Code centered
            double qrSize = Math.min(innerW * 0.60, height * 0.33);
            BufferedImage qrImg = qrService.generateQrImage(pkg.qrCode(), Math.max(240, (int) (qrSize * 3)));
            double qrX = (width - qrSize) / 2.0;
            g.drawImage(qrImg, (int) Math.round(qrX), (int) Math.round(cursorY),
                    (int) Math.round(qrSize), (int) Math.round(qrSize), null);

            cursorY += qrSize + pad * 0.35;

            // Full Monospace Code String (auto-fit width)
            g.setColor(Color.BLACK);
            int codeFontSize = Math.max(7, (int) (width * 0.042));
            Font codeFont = new Font(Font.MONOSPACED, Font.BOLD, codeFontSize);
            g.setFont(codeFont);
            while (g.getFontMetrics().stringWidth(pkg.qrCode()) > innerW - pad * 0.5 && codeFontSize > 6) {
                codeFontSize--;
                codeFont = new Font(Font.MONOSPACED, Font.BOLD, codeFontSize);
                g.setFont(codeFont);
            }
            FontMetrics fmCode = g.getFontMetrics();
            double codeX = (width - fmCode.stringWidth(pkg.qrCode())) / 2.0;
            g.drawString(pkg.qrCode(), (float) codeX, (float) (cursorY + fmCode.getAscent()));
            cursorY += fmCode.getHeight() + pad * 0.35;

            // Divider line
            g.setStroke(new BasicStroke(0.8f));
            g.setColor(new Color(0x94A3B8));
            g.drawLine((int) innerX, (int) cursorY, (int) (width - innerX), (int) cursorY);
            cursorY += pad * 0.45;

            // Specification Rows + Big Weight Box side-by-side
            double footerH = height * 0.11;
            double specBlockH = Math.max(30, height - cursorY - footerH - pad * 0.6);
            double weightBoxW = innerW * 0.33;
            double specColW = innerW - weightBoxW - pad * 0.45;

            String[][] rows = new String[][]{
                    {i18n.t("intake.target_group"), pkg.targetGroupCode() + " — " + pkg.localizedTargetGroup(lang)},
                    {i18n.t("intake.garment_type"), pkg.garmentTypeCode() + " — " + pkg.localizedGarmentType(lang)},
                    {i18n.t("intake.quality_grade"), pkg.qualityGradeCode() + " — " + pkg.localizedQualityGrade(lang)},
                    {i18n.t("intake.package_type"), pkg.packageTypeCode() + " — " + pkg.localizedPackageType(lang)}
            };

            double rowH = specBlockH / rows.length;
            Font labelFont = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(6, (int) (rowH * 0.30)));
            Font valueFont = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(7, (int) (rowH * 0.42)));

            for (int i = 0; i < rows.length; i++) {
                double ry = cursorY + i * rowH;
                g.setColor(new Color(0x475569));
                g.setFont(labelFont);
                g.drawString(truncateToWidth(rows[i][0], g.getFontMetrics(), specColW),
                        (float) innerX, (float) (ry + g.getFontMetrics().getAscent()));

                g.setColor(Color.BLACK);
                g.setFont(valueFont);
                g.drawString(truncateToWidth(rows[i][1], g.getFontMetrics(), specColW),
                        (float) innerX, (float) (ry + rowH * 0.44 + g.getFontMetrics().getAscent()));
            }

            // Bold Weight Box on the right
            double wbX = innerX + specColW + pad * 0.45;
            double wbY = cursorY;
            double wbH = specBlockH * 0.92;
            g.setColor(new Color(0xF1F5F9));
            g.fill(new Rectangle2D.Double(wbX, wbY, weightBoxW, wbH));
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new Rectangle2D.Double(wbX, wbY, weightBoxW, wbH));

            String weightText = formatWeight(pkg.weightKg());
            Font wtFont = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, (int) (weightBoxW * 0.36)));
            g.setFont(wtFont);
            FontMetrics fmWt = g.getFontMetrics();
            g.drawString(weightText, (float) (wbX + (weightBoxW - fmWt.stringWidth(weightText)) / 2.0),
                    (float) (wbY + wbH * 0.48 + fmWt.getAscent() * 0.35));

            Font unitFont = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, (int) (weightBoxW * 0.19)));
            g.setFont(unitFont);
            FontMetrics fmUnit = g.getFontMetrics();
            String unit = i18n.t("common.kg").toUpperCase();
            g.drawString(unit, (float) (wbX + (weightBoxW - fmUnit.stringWidth(unit)) / 2.0),
                    (float) (wbY + wbH * 0.78));

            // Footer Metadata
            double footerY = height - footerH - pad * 0.4;
            g.setStroke(new BasicStroke(0.7f));
            g.setColor(new Color(0x94A3B8));
            g.drawLine((int) innerX, (int) footerY, (int) (width - innerX), (int) footerY);

            Font footFont = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(6, (int) (footerH * 0.28)));
            g.setFont(footFont);
            g.setColor(new Color(0x334155));
            FontMetrics fmFoot = g.getFontMetrics();

            String tsText = pkg.createdAt() != null ? TS_FMT.format(pkg.createdAt()) : "";
            String opText = pkg.createdByFullName() != null ? pkg.createdByFullName() : pkg.createdByUsername();
            g.drawString(tsText, (float) innerX, (float) (footerY + pad * 0.25 + fmFoot.getAscent()));
            String rightFoot = truncateToWidth(opText, fmFoot, innerW * 0.55);
            g.drawString(rightFoot, (float) (width - innerX - fmFoot.stringWidth(rightFoot)),
                    (float) (footerY + pad * 0.25 + fmFoot.getAscent()));

            if (pkg.notes() != null && !pkg.notes().isBlank()) {
                String noteText = truncateToWidth(pkg.notes(), fmFoot, innerW);
                g.drawString(noteText, (float) innerX, (float) (footerY + pad * 0.35 + fmFoot.getAscent() + fmFoot.getHeight()));
            }
        } finally {
            g.dispose();
        }
    }

    private static String formatWeight(BigDecimal weight) {
        if (weight == null) {
            return "0";
        }
        BigDecimal stripped = weight.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        return stripped.toPlainString();
    }

    private static String truncateToWidth(String text, FontMetrics fm, double maxWidth) {
        if (text == null) {
            return "";
        }
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int len = text.length();
        while (len > 1 && fm.stringWidth(text.substring(0, len) + ellipsis) > maxWidth) {
            len--;
        }
        return text.substring(0, Math.max(0, len)) + ellipsis;
    }
}
