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
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * Vector Graphics2D painter rendering a Swiss Brutalist logistics QR sticker
 * consistently across Swing Print Preview, Java PrinterJob, and OpenPDF export.
 * Supports both tall/portrait labels (100x150, 80x100, A6, A4 4-up) and square labels (100x100).
 */
public final class TicketLabelPainter {

    private final QrCodeService qrService;
    private final I18nManager i18n;

    public TicketLabelPainter(QrCodeService qrService, I18nManager i18n) {
        this.qrService = qrService;
        this.i18n = i18n;
    }

    private record SpecItem(String header, String primaryValue, String englishSubValue) {
    }

    public void paintTicket(Graphics2D g2, InventoryPackage pkg, double x, double y, double width, double height) {
        Graphics2D g = (Graphics2D) g2.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            g.translate(x, y);

            double minDim = Math.min(width, height);
            double pad = minDim * 0.042;
            double borderInset = pad * 0.5;
            double innerX = pad;
            double innerW = width - 2 * pad;

            // Pure white background & crisp black outer border
            g.setColor(Color.WHITE);
            g.fill(new Rectangle2D.Double(0, 0, width, height));
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(Math.max(1.2f, (float) (minDim * 0.006))));
            g.draw(new Rectangle2D.Double(borderInset, borderInset, width - pad, height - pad));

            boolean isSquare = (height / width) < 1.15;
            double headerH = isSquare ? height * 0.175 : height * 0.135;
            double headerBottomY = paintHeader(g, pkg, width, borderInset, innerX, innerW, pad, headerH);

            SpecItem[] specs = buildBilingualSpecs(pkg);
            String weightText = formatWeight(pkg.weightKg());
            String unitText = i18n.tForLanguage("en", "common.kg").toUpperCase(Locale.ROOT);

            if (isSquare) {
                paintSquareBody(g, pkg, specs, weightText, unitText, width, height, pad, innerX, innerW, headerBottomY);
            } else {
                paintPortraitBody(g, pkg, specs, weightText, unitText, width, height, pad, innerX, innerW, headerBottomY);
            }
        } finally {
            g.dispose();
        }
    }

    private double paintHeader(Graphics2D g, InventoryPackage pkg, double width, double borderInset,
                               double innerX, double innerW, double pad, double headerH) {
        double cursorY = borderInset;
        double headerBottomY = cursorY + headerH;

        // Left: Crisp Vector ReWear SVG Logo (macOS rounded corners)
        double logoSize = headerH * 0.84;
        double logoX = innerX + pad * 0.15;
        double logoY = cursorY + (headerH - logoSize) / 2.0;
        LogoAssets.paintLogo(g, logoX, logoY, logoSize, false);

        // Right: Inverted solid black combined product code badge (e.g. B-DRS-CR-A)
        String combinedCode = pkg.targetGroupCode() + "-" + pkg.garmentTypeCode() + "-"
                + pkg.qualityGradeCode() + "-" + pkg.packageTypeCode();
        double maxBadgeW = innerW - logoSize - pad * 0.75;
        double badgePadX = pad * 0.55;
        float fontSize = (float) Math.max(9.0, headerH * 0.34);
        Font badgeFont = new Font(Font.MONOSPACED, Font.BOLD, Math.round(fontSize)).deriveFont(fontSize);
        g.setFont(badgeFont);
        while (g.getFontMetrics().stringWidth(combinedCode) + 2 * badgePadX > maxBadgeW && fontSize > 7f) {
            fontSize -= 0.75f;
            badgeFont = badgeFont.deriveFont(fontSize);
            g.setFont(badgeFont);
        }
        FontMetrics fmBadge = g.getFontMetrics();
        double badgeW = fmBadge.stringWidth(combinedCode) + 2 * badgePadX;
        double badgeH = Math.min(headerH * 0.76, fmBadge.getHeight() + pad * 0.45);
        double badgeX = width - innerX - badgeW - pad * 0.1;
        double badgeY = cursorY + (headerH - badgeH) / 2.0;

        g.setColor(Color.BLACK);
        g.fill(new Rectangle2D.Double(badgeX, badgeY, badgeW, badgeH));
        g.setColor(Color.WHITE);
        g.drawString(combinedCode, (float) (badgeX + badgePadX),
                (float) (badgeY + (badgeH + fmBadge.getAscent() - fmBadge.getDescent()) / 2.0));

        // Heavy rule under header
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(Math.max(1.5f, (float) (width * 0.0065))));
        g.drawLine((int) Math.round(borderInset), (int) Math.round(headerBottomY),
                (int) Math.round(width - borderInset), (int) Math.round(headerBottomY));

        return headerBottomY;
    }

    private void paintPortraitBody(Graphics2D g, InventoryPackage pkg, SpecItem[] specs,
                                   String weightText, String unitText,
                                   double width, double height, double pad, double innerX, double innerW,
                                   double startY) {
        double cursorY = startY + pad * 0.45;

        // Centered QR code
        double qrSize = Math.min(innerW * 0.62, height * 0.35);
        BufferedImage qrImg = qrService.generateQrImage(pkg.qrCode(), Math.max(260, (int) (qrSize * 3)));
        double qrX = (width - qrSize) / 2.0;
        g.drawImage(qrImg, (int) Math.round(qrX), (int) Math.round(cursorY),
                (int) Math.round(qrSize), (int) Math.round(qrSize), null);

        cursorY += qrSize + pad * 0.28;

        // Full Monospace QR Code String (auto-fit)
        cursorY = paintFullCodeLine(g, pkg.qrCode(), width, innerW, pad, cursorY);

        // Crisp divider
        g.setStroke(new BasicStroke(0.9f));
        g.setColor(Color.BLACK);
        g.drawLine((int) Math.round(innerX), (int) Math.round(cursorY),
                (int) Math.round(width - innerX), (int) Math.round(cursorY));
        cursorY += pad * 0.45;

        // Bottom Split: Left compact bilingual descriptions | Right HUGE bold weight (no box)
        double bottomAreaH = Math.max(40, height - cursorY - pad * 0.85);
        double weightColW = innerW * 0.44;
        double specColW = innerW - weightColW - pad * 0.4;

        double rowH = bottomAreaH / specs.length;
        for (int i = 0; i < specs.length; i++) {
            double ry = cursorY + i * rowH;
            paintSpecCell(g, specs[i], innerX, ry, specColW, rowH, false);
        }

        double weightX = innerX + specColW + pad * 0.4;
        paintBigWeight(g, weightText, unitText, weightX, cursorY, weightColW, bottomAreaH);
    }

    private void paintSquareBody(Graphics2D g, InventoryPackage pkg, SpecItem[] specs,
                                 String weightText, String unitText,
                                 double width, double height, double pad, double innerX, double innerW,
                                 double startY) {
        double contentBottom = height - pad * 0.75;
        double remainingH = contentBottom - startY;

        // Middle Band: Left QR Code + Right Giant Bold Weight (no box)
        double midPadTop = pad * 0.32;
        double midH = remainingH * 0.50;
        double midY = startY + midPadTop;

        double qrColW = innerW * 0.48;
        double qrSize = Math.min(qrColW * 0.96, midH * 0.96);
        BufferedImage qrImg = qrService.generateQrImage(pkg.qrCode(), Math.max(260, (int) (qrSize * 3)));
        double qrX = innerX + (qrColW - qrSize) / 2.0;
        double qrY = midY + (midH - qrSize) / 2.0;
        g.drawImage(qrImg, (int) Math.round(qrX), (int) Math.round(qrY),
                (int) Math.round(qrSize), (int) Math.round(qrSize), null);

        double weightColX = innerX + qrColW + pad * 0.25;
        double weightColW = innerW - qrColW - pad * 0.25;
        paintBigWeight(g, weightText, unitText, weightColX, midY, weightColW, midH);

        // Full-width QR Code String Strip
        double codeStripTop = midY + midH + pad * 0.22;
        g.setStroke(new BasicStroke(0.85f));
        g.setColor(Color.BLACK);
        g.drawLine((int) Math.round(innerX), (int) Math.round(codeStripTop),
                (int) Math.round(width - innerX), (int) Math.round(codeStripTop));

        double afterCodeY = paintFullCodeLine(g, pkg.qrCode(), width, innerW, pad * 0.6, codeStripTop + pad * 0.2);

        g.setStroke(new BasicStroke(0.85f));
        g.setColor(Color.BLACK);
        g.drawLine((int) Math.round(innerX), (int) Math.round(afterCodeY),
                (int) Math.round(width - innerX), (int) Math.round(afterCodeY));

        // Bottom 2x2 Compact Bilingual Specification Grid
        double gridTop = afterCodeY + pad * 0.26;
        double gridH = Math.max(28, contentBottom - gridTop);
        double colGap = pad * 0.55;
        double colW = (innerW - colGap) / 2.0;
        double rowH = gridH / 2.0;

        // Subtle vertical divider between the two spec columns
        g.setStroke(new BasicStroke(0.55f));
        g.setColor(new Color(0xCBD5E1));
        double midColX = innerX + colW + colGap / 2.0;
        g.drawLine((int) Math.round(midColX), (int) Math.round(gridTop),
                (int) Math.round(midColX), (int) Math.round(contentBottom - pad * 0.1));

        for (int i = 0; i < specs.length; i++) {
            int col = i % 2;
            int row = i / 2;
            double cellX = innerX + col * (colW + colGap);
            double cellY = gridTop + row * rowH;
            paintSpecCell(g, specs[i], cellX, cellY, colW, rowH, true);
        }
    }

    private double paintFullCodeLine(Graphics2D g, String qrCode, double width, double innerW,
                                     double pad, double topY) {
        g.setColor(Color.BLACK);
        float codeFontSize = (float) Math.max(6.5, width * 0.039);
        Font codeFont = new Font(Font.MONOSPACED, Font.BOLD, Math.round(codeFontSize)).deriveFont(codeFontSize);
        g.setFont(codeFont);
        while (g.getFontMetrics().stringWidth(qrCode) > innerW - pad * 0.3 && codeFontSize > 5.5f) {
            codeFontSize -= 0.5f;
            codeFont = codeFont.deriveFont(codeFontSize);
            g.setFont(codeFont);
        }
        FontMetrics fmCode = g.getFontMetrics();
        double codeX = (width - fmCode.stringWidth(qrCode)) / 2.0;
        g.drawString(qrCode, (float) codeX, (float) (topY + fmCode.getAscent()));
        return topY + fmCode.getHeight() + pad * 0.25;
    }

    private void paintSpecCell(Graphics2D g, SpecItem spec, double x, double y, double w, double h, boolean compact) {
        boolean hasSub = spec.englishSubValue() != null && !spec.englishSubValue().isBlank();

        float headerSize = (float) Math.max(4.8, Math.min(compact ? 6.2 : 6.8, h * (compact ? 0.19 : 0.18)));
        float valueSize = (float) Math.max(6.0, Math.min(compact ? 8.2 : 9.4, h * (compact ? 0.27 : 0.26)));
        float subSize = (float) Math.max(5.2, Math.min(compact ? 6.8 : 7.6, h * (compact ? 0.21 : 0.20)));

        Font headerFont = new Font(Font.SANS_SERIF, Font.BOLD, 8).deriveFont(headerSize);
        Font valueFont = new Font(Font.SANS_SERIF, Font.BOLD, 10).deriveFont(valueSize);
        Font subFont = new Font(Font.SANS_SERIF, Font.PLAIN, 8).deriveFont(subSize);

        FontMetrics fmH = g.getFontMetrics(headerFont);
        FontMetrics fmV = g.getFontMetrics(valueFont);
        FontMetrics fmS = g.getFontMetrics(subFont);

        double lineGap = Math.max(0.6, h * 0.03);
        double totalTextH = fmH.getHeight() + lineGap + fmV.getHeight()
                + (hasSub ? lineGap * 0.7 + fmS.getHeight() : 0);
        double startY = y + Math.max(0, (h - totalTextH) / 2.0);

        g.setFont(headerFont);
        g.setColor(new Color(0x475569));
        g.drawString(truncateToWidth(spec.header(), fmH, w),
                (float) x, (float) (startY + fmH.getAscent()));

        double valY = startY + fmH.getHeight() + lineGap;
        g.setFont(valueFont);
        g.setColor(Color.BLACK);
        g.drawString(truncateToWidth(spec.primaryValue(), fmV, w),
                (float) x, (float) (valY + fmV.getAscent()));

        if (hasSub) {
            double subY = valY + fmV.getHeight() + lineGap * 0.7;
            g.setFont(subFont);
            g.setColor(new Color(0x334155));
            g.drawString(truncateToWidth(spec.englishSubValue(), fmS, w),
                    (float) x, (float) (subY + fmS.getAscent()));
        }
    }

    /**
     * Paints an unboxed, giant, ultra-bold weight figure with bold "KG" centered cleanly directly beneath it,
     * using exact GlyphVector visual bounds for crisp optical centering.
     */
    private void paintBigWeight(Graphics2D g, String weightText, String unitText,
                                double x, double y, double w, double h) {
        FontRenderContext frc = g.getFontRenderContext();
        double maxNumW = w * 0.94;
        double maxNumH = h * 0.64;

        float numSize = (float) Math.min(w * 0.92, h * 0.78);
        Font numFont = new Font(Font.SANS_SERIF, Font.BOLD, 48).deriveFont(numSize);
        Rectangle2D numBounds = visualBounds(numFont, frc, weightText);
        while ((numBounds.getWidth() > maxNumW || numBounds.getHeight() > maxNumH) && numSize > 14f) {
            numSize -= 1.0f;
            numFont = numFont.deriveFont(numSize);
            numBounds = visualBounds(numFont, frc, weightText);
        }

        float unitSize = (float) Math.max(10.0, Math.min(numSize * 0.34, h * 0.20));
        Font unitFont = new Font(Font.SANS_SERIF, Font.BOLD, 16).deriveFont(unitSize);
        Rectangle2D unitBounds = visualBounds(unitFont, frc, unitText);
        while (unitBounds.getWidth() > w * 0.70 && unitSize > 8f) {
            unitSize -= 0.75f;
            unitFont = unitFont.deriveFont(unitSize);
            unitBounds = visualBounds(unitFont, frc, unitText);
        }

        double gap = Math.max(3.0, numBounds.getHeight() * 0.14);
        double totalVisualH = numBounds.getHeight() + gap + unitBounds.getHeight();
        double topY = y + (h - totalVisualH) / 2.0;

        g.setColor(Color.BLACK);

        // Draw big weight digits optically centered
        double numDrawX = x + (w - numBounds.getWidth()) / 2.0 - numBounds.getX();
        double numDrawY = topY - numBounds.getY();
        g.setFont(numFont);
        g.drawString(weightText, (float) numDrawX, (float) numDrawY);

        // Draw KG directly underneath
        double unitTopY = topY + numBounds.getHeight() + gap;
        double unitDrawX = x + (w - unitBounds.getWidth()) / 2.0 - unitBounds.getX();
        double unitDrawY = unitTopY - unitBounds.getY();
        g.setFont(unitFont);
        g.drawString(unitText, (float) unitDrawX, (float) unitDrawY);
    }

    private static Rectangle2D visualBounds(Font font, FontRenderContext frc, String text) {
        GlyphVector gv = font.createGlyphVector(frc, text);
        return gv.getVisualBounds();
    }

    private SpecItem[] buildBilingualSpecs(InventoryPackage pkg) {
        String lang = i18n.getActiveLanguage();
        boolean isEnglish = "en".equalsIgnoreCase(lang);
        return new SpecItem[]{
                buildSpec("ticket.label.target", pkg.targetGroupCode(),
                        pkg.localizedTargetGroup(lang), pkg.targetGroupNameEn(), isEnglish),
                buildSpec("ticket.label.type", pkg.garmentTypeCode(),
                        pkg.localizedGarmentType(lang), pkg.garmentTypeNameEn(), isEnglish),
                buildSpec("ticket.label.quality", pkg.qualityGradeCode(),
                        pkg.localizedQualityGrade(lang), pkg.qualityGradeNameEn(), isEnglish),
                buildSpec("ticket.label.pack", pkg.packageTypeCode(),
                        pkg.localizedPackageType(lang), pkg.packageTypeNameEn(), isEnglish)
        };
    }

    private SpecItem buildSpec(String labelKey, String code, String localizedName, String englishName, boolean isEnglish) {
        String locLabel = i18n.t(labelKey).toUpperCase(Locale.ROOT);
        String enLabel = i18n.tForLanguage("en", labelKey).toUpperCase(Locale.ROOT);
        String header = (isEnglish || locLabel.equalsIgnoreCase(enLabel))
                ? enLabel
                : locLabel + " / " + enLabel;

        String primary = code + " — " + (localizedName != null && !localizedName.isBlank() ? localizedName : englishName);
        String englishSub = (!isEnglish && englishName != null && !englishName.isBlank()
                && !englishName.equalsIgnoreCase(localizedName))
                ? englishName
                : null;
        return new SpecItem(header, primary, englishSub);
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
