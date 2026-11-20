package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.model.PackageStatus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;

/**
 * Clean localized placeholder panel for modules scheduled in upcoming phases.
 */
public final class PlaceholderView extends JPanel implements NavigableView {

    private final String viewKey;
    private final String titleI18nKey;
    private final AppContext ctx;

    private final JLabel titleLabel = new JLabel();
    private final JLabel statsLabel = new JLabel();

    public PlaceholderView(String viewKey, String titleI18nKey, AppContext ctx) {
        this.viewKey = viewKey;
        this.titleI18nKey = titleI18nKey;
        this.ctx = ctx;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(32, 32, 32, 32));

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(""),
                BorderFactory.createEmptyBorder(24, 24, 24, 24)
        ));

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 20f));
        statsLabel.setFont(statsLabel.getFont().deriveFont(Font.PLAIN, 14f));

        card.add(titleLabel);
        card.add(Box.createVerticalStrut(12));
        card.add(statsLabel);

        add(card, BorderLayout.NORTH);
        applyTranslations();
        refreshData();
    }

    @Override
    public String getViewKey() {
        return viewKey;
    }

    @Override
    public String getTitleI18nKey() {
        return titleI18nKey;
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        int inStock = ctx.getPackageDao().countByStatus(PackageStatus.IN_STOCK);
        int openOrders = ctx.getOrderDao().countOpenOrders();
        statsLabel.setText(ctx.getI18n().t("dashboard.kpi.bales") + ": " + inStock
                + "   |   " + ctx.getI18n().t("dashboard.kpi.open_orders") + ": " + openOrders);
    }

    @Override
    public void applyTranslations() {
        titleLabel.setText(ctx.getI18n().t(titleI18nKey));
        refreshData();
    }
}
