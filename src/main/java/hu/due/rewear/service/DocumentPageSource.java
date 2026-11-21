package hu.due.rewear.service;

import java.awt.Graphics2D;

/**
 * Abstraction for an A4 vector document (Delivery Note or Invoice) that can be
 * rendered identically in Swing preview, Java PrinterJob, and OpenPDF export.
 */
public interface DocumentPageSource {

    String getDocumentId();

    String getDisplayTitle();

    String getDefaultFileName();

    int getPageCount();

    void paintPage(Graphics2D g, int pageIndex, float pageWidthPt, float pageHeightPt);
}
