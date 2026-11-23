package hu.due.rewear.ui;

import hu.due.rewear.config.ThemeManager;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * Pure-vector monochrome SVG-style icons for the sidebar navigation items and collapse/expand controls.
 * Icons automatically inherit the host button's foreground color so they invert cleanly on active states
 * and adapt instantaneously to Light and Dark modes.
 */
public final class SidebarIcons {

    @FunctionalInterface
    private interface GlyphPainter {
        void paint(Graphics2D g);
    }

    private SidebarIcons() {
    }

    public static Icon forView(String viewKey, int size) {
        return switch (viewKey) {
            case MainFrame.VIEW_DASHBOARD -> makeIcon(size, SidebarIcons::paintDashboard);
            case MainFrame.VIEW_TODO -> makeIcon(size, SidebarIcons::paintTodo);
            case MainFrame.VIEW_INTAKE -> makeIcon(size, SidebarIcons::paintIntake);
            case MainFrame.VIEW_TRANSPORT -> makeIcon(size, SidebarIcons::paintTransport);
            case MainFrame.VIEW_SCANNER -> makeIcon(size, SidebarIcons::paintScanner);
            case MainFrame.VIEW_INVENTORY -> makeIcon(size, SidebarIcons::paintInventory);
            case MainFrame.VIEW_LOCATIONS -> makeIcon(size, SidebarIcons::paintLocations);
            case MainFrame.VIEW_DICTIONARIES -> makeIcon(size, SidebarIcons::paintDictionaries);
            case MainFrame.VIEW_CUSTOMERS -> makeIcon(size, SidebarIcons::paintCustomers);
            case MainFrame.VIEW_ORDERS -> makeIcon(size, SidebarIcons::paintOrders);
            case MainFrame.VIEW_INVOICES -> makeIcon(size, SidebarIcons::paintInvoices);
            case MainFrame.VIEW_EMAIL -> makeIcon(size, SidebarIcons::paintEmail);
            case MainFrame.VIEW_NOTIFICATIONS -> makeIcon(size, SidebarIcons::paintNotifications);
            case MainFrame.VIEW_MESSAGES -> makeIcon(size, SidebarIcons::paintMessages);
            case MainFrame.VIEW_ADMIN -> makeIcon(size, SidebarIcons::paintAdmin);
            default -> makeIcon(size, SidebarIcons::paintDashboard);
        };
    }

    public static Icon arrowLeft(int size) {
        return makeIcon(size, g -> {
            Path2D p = new Path2D.Double();
            p.moveTo(15, 5);
            p.lineTo(8, 12);
            p.lineTo(15, 19);
            g.draw(p);
            g.draw(new Line2D.Double(9, 12, 20, 12));
            g.draw(new Line2D.Double(4.5, 5, 4.5, 19));
        });
    }

    public static Icon arrowRight(int size) {
        return makeIcon(size, g -> {
            Path2D p = new Path2D.Double();
            p.moveTo(9, 5);
            p.lineTo(16, 12);
            p.lineTo(9, 19);
            g.draw(p);
            g.draw(new Line2D.Double(4, 12, 15, 12));
            g.draw(new Line2D.Double(19.5, 5, 19.5, 19));
        });
    }

    private static Icon makeIcon(int size, GlyphPainter painter) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                    Color color = c != null ? c.getForeground() : ThemeManager.fg();
                    g2.setColor(color);
                    g2.translate(x, y);
                    double s = size / 24.0;
                    g2.scale(s, s);
                    g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
                    painter.paint(g2);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    // 2x2 dashboard grid
    private static void paintDashboard(Graphics2D g) {
        g.draw(new Rectangle2D.Double(3, 3, 7.5, 7.5));
        g.draw(new Rectangle2D.Double(13.5, 3, 7.5, 7.5));
        g.draw(new Rectangle2D.Double(3, 13.5, 7.5, 7.5));
        g.draw(new Rectangle2D.Double(13.5, 13.5, 7.5, 7.5));
    }

    // Package box with plus sign
    private static void paintIntake(Graphics2D g) {
        g.draw(new Rectangle2D.Double(3, 6, 14, 14));
        g.draw(new Line2D.Double(3, 10.5, 17, 10.5));
        g.draw(new Line2D.Double(10, 6, 10, 10.5));
        // Plus badge in top-right
        g.draw(new Line2D.Double(18.5, 3, 18.5, 9));
        g.draw(new Line2D.Double(15.5, 6, 21.5, 6));
    }

    // QR scanner viewfinder brackets + scan beam
    private static void paintScanner(Graphics2D g) {
        // Top-left corner
        Path2D p = new Path2D.Double();
        p.moveTo(3, 8); p.lineTo(3, 3); p.lineTo(8, 3);
        // Top-right corner
        p.moveTo(16, 3); p.lineTo(21, 3); p.lineTo(21, 8);
        // Bottom-left corner
        p.moveTo(3, 16); p.lineTo(3, 21); p.lineTo(8, 21);
        // Bottom-right corner
        p.moveTo(16, 21); p.lineTo(21, 21); p.lineTo(21, 16);
        g.draw(p);
        // Horizontal laser scan line
        g.draw(new Line2D.Double(5, 12, 19, 12));
    }

    // Stacked inventory layers / rows
    private static void paintInventory(Graphics2D g) {
        g.draw(new Rectangle2D.Double(3, 4, 18, 4.5));
        g.draw(new Rectangle2D.Double(3, 10, 18, 4.5));
        g.draw(new Rectangle2D.Double(3, 16, 18, 4.5));
    }

    // Warehouse shelves / rack bays
    private static void paintLocations(Graphics2D g) {
        g.draw(new Line2D.Double(3, 3, 3, 21));
        g.draw(new Line2D.Double(21, 3, 21, 21));
        g.draw(new Line2D.Double(3, 9, 21, 9));
        g.draw(new Line2D.Double(3, 15.5, 21, 15.5));
        g.draw(new Rectangle2D.Double(6, 4.5, 5, 4.5));
        g.draw(new Rectangle2D.Double(13, 11, 5, 4.5));
    }

    // Code tag / dictionary label
    private static void paintDictionaries(Graphics2D g) {
        Path2D tag = new Path2D.Double();
        tag.moveTo(3, 11.5);
        tag.lineTo(11.5, 3);
        tag.lineTo(20.5, 3);
        tag.lineTo(20.5, 12);
        tag.lineTo(12, 20.5);
        tag.closePath();
        g.draw(tag);
        g.fill(new Ellipse2D.Double(15, 6.2, 2.6, 2.6));
    }

    // Customer / person icon
    private static void paintCustomers(Graphics2D g) {
        g.draw(new Ellipse2D.Double(8, 3.5, 8, 8));
        Path2D body = new Path2D.Double();
        body.moveTo(4, 20.5);
        body.lineTo(4, 17.5);
        body.quadTo(4, 14, 12, 14);
        body.quadTo(20, 14, 20, 17.5);
        body.lineTo(20, 20.5);
        g.draw(body);
    }

    // Delivery truck / dispatch orders icon
    private static void paintOrders(Graphics2D g) {
        g.draw(new Rectangle2D.Double(2.5, 6, 12.5, 10));
        Path2D cab = new Path2D.Double();
        cab.moveTo(15, 9);
        cab.lineTo(19, 9);
        cab.lineTo(21.5, 12);
        cab.lineTo(21.5, 16);
        cab.lineTo(15, 16);
        g.draw(cab);
        g.draw(new Ellipse2D.Double(5, 16.5, 3.5, 3.5));
        g.draw(new Ellipse2D.Double(16, 16.5, 3.5, 3.5));
    }

    // Invoice / financial document icon
    private static void paintInvoices(Graphics2D g) {
        g.draw(new Rectangle2D.Double(4.5, 3, 15, 18));
        g.draw(new Line2D.Double(8, 8, 16, 8));
        g.draw(new Line2D.Double(8, 12, 16, 12));
        g.draw(new Line2D.Double(8, 16, 13, 16));
    }

    // Admin shield icon
    private static void paintAdmin(Graphics2D g) {
        Path2D shield = new Path2D.Double();
        shield.moveTo(12, 3);
        shield.lineTo(20, 6);
        shield.lineTo(20, 12.5);
        shield.quadTo(20, 18, 12, 21);
        shield.quadTo(4, 18, 4, 12.5);
        shield.lineTo(4, 6);
        shield.closePath();
        g.draw(shield);
        g.draw(new Line2D.Double(9, 12, 11.2, 14.2));
        g.draw(new Line2D.Double(11.2, 14.2, 15.5, 9.5));
    }

    // Forklift / pallet transport arrows icon
    private static void paintTransport(Graphics2D g) {
        g.draw(new Rectangle2D.Double(3, 9, 10, 8));
        g.draw(new Line2D.Double(13, 17, 21, 17));
        g.draw(new Line2D.Double(17, 5, 17, 17));
        g.draw(new Line2D.Double(17, 13, 21, 13));
        g.draw(new Ellipse2D.Double(4.5, 17, 3.5, 3.5));
        g.draw(new Ellipse2D.Double(11, 17, 3.5, 3.5));
    }

    // Envelope icon for corporate Email
    private static void paintEmail(Graphics2D g) {
        g.draw(new Rectangle2D.Double(3, 5, 18, 14));
        Path2D flap = new Path2D.Double();
        flap.moveTo(3, 6);
        flap.lineTo(12, 13);
        flap.lineTo(21, 6);
        g.draw(flap);
    }

    // Chat bubble icon for internal Messages
    private static void paintMessages(Graphics2D g) {
        Path2D bubble = new Path2D.Double();
        bubble.moveTo(3, 5);
        bubble.lineTo(21, 5);
        bubble.lineTo(21, 16);
        bubble.lineTo(9, 16);
        bubble.lineTo(5, 20);
        bubble.lineTo(5, 16);
        bubble.lineTo(3, 16);
        bubble.closePath();
        g.draw(bubble);
        g.draw(new Line2D.Double(7, 9.5, 17, 9.5));
        g.draw(new Line2D.Double(7, 12.5, 14, 12.5));
    }

    // Checklist icon for To-Do
    private static void paintTodo(Graphics2D g) {
        g.draw(new Rectangle2D.Double(3.5, 4, 5, 5));
        g.draw(new Line2D.Double(11, 6.5, 20.5, 6.5));
        g.draw(new Rectangle2D.Double(3.5, 13.5, 5, 5));
        g.draw(new Line2D.Double(11, 16, 20.5, 16));
    }

    // Bell icon for Notifications
    private static void paintNotifications(Graphics2D g) {
        Path2D bell = new Path2D.Double();
        bell.moveTo(18, 15);
        bell.lineTo(18, 10);
        bell.quadTo(18, 4, 12, 4);
        bell.quadTo(6, 4, 6, 10);
        bell.lineTo(6, 15);
        bell.lineTo(4, 17.5);
        bell.lineTo(20, 17.5);
        bell.closePath();
        g.draw(bell);
        g.draw(new Line2D.Double(10, 20.5, 14, 20.5));
    }
}
