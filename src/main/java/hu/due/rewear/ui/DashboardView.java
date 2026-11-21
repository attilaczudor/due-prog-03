package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.CategoryWeightStat;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.StockMovement;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.PieChart;
import org.knowm.xchart.PieChartBuilder;
import org.knowm.xchart.XChartPanel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Brutalist Monochrome Warehouse Dashboard featuring live KPI cards,
 * XChart stock analytics (by Quality Grade and by Garment Type), and Recent Stock Movements.
 */
public final class DashboardView extends JPanel implements NavigableView {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;

    private final JPanel kpiCard1 = new JPanel();
    private final JPanel kpiCard2 = new JPanel();
    private final JPanel kpiCard3 = new JPanel();
    private final JPanel kpiCard4 = new JPanel();

    private final JLabel kpiTitle1 = new JLabel();
    private final JLabel kpiTitle2 = new JLabel();
    private final JLabel kpiTitle3 = new JLabel();
    private final JLabel kpiTitle4 = new JLabel();

    private final JLabel kpiValue1 = new JLabel("0");
    private final JLabel kpiValue2 = new JLabel("0 kg");
    private final JLabel kpiValue3 = new JLabel("0");
    private final JLabel kpiValue4 = new JLabel("0");

    private final JPanel chartsGrid = new JPanel(new GridLayout(1, 2, 14, 0));
    private final JPanel qualityChartHost = new JPanel(new BorderLayout());
    private final JPanel garmentChartHost = new JPanel(new BorderLayout());

    private final JLabel recentTitleLabel = new JLabel();
    private final JButton refreshBtn = new JButton();
    private final DefaultTableModel recentModel = new DefaultTableModel() {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable recentTable = new JTable(recentModel);
    private List<StockMovement> recentMovements = List.of();

    public DashboardView(AppContext ctx) {
        this.ctx = ctx;

        setLayout(new BorderLayout(0, 14));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        buildUi();
        applyTranslations();
        applyTheme();
        refreshData();
    }

    private void buildUi() {
        // Top KPI Grid
        JPanel kpiGrid = new JPanel(new GridLayout(1, 4, 12, 0));
        configureKpiCard(kpiCard1, kpiTitle1, kpiValue1);
        configureKpiCard(kpiCard2, kpiTitle2, kpiValue2);
        configureKpiCard(kpiCard3, kpiTitle3, kpiValue3);
        configureKpiCard(kpiCard4, kpiTitle4, kpiValue4);
        kpiGrid.add(kpiCard1);
        kpiGrid.add(kpiCard2);
        kpiGrid.add(kpiCard3);
        kpiGrid.add(kpiCard4);
        add(kpiGrid, BorderLayout.NORTH);

        // Center: Charts + Recent Movements
        JPanel center = new JPanel(new BorderLayout(0, 14));

        qualityChartHost.setPreferredSize(new Dimension(420, 270));
        garmentChartHost.setPreferredSize(new Dimension(480, 270));
        chartsGrid.add(qualityChartHost);
        chartsGrid.add(garmentChartHost);
        center.add(chartsGrid, BorderLayout.NORTH);

        JPanel recentPanel = new JPanel(new BorderLayout(0, 6));
        JPanel recentHeader = new JPanel(new BorderLayout());
        recentTitleLabel.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        refreshBtn.addActionListener(e -> refreshData());
        recentHeader.add(recentTitleLabel, BorderLayout.WEST);
        recentHeader.add(refreshBtn, BorderLayout.EAST);
        recentPanel.add(recentHeader, BorderLayout.NORTH);

        BrutalistStyle.styleTable(recentTable);
        recentTable.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        recentTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    int row = recentTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && row < recentMovements.size()) {
                        openMovementPackageDetails(recentMovements.get(row));
                    }
                }
            }
        });
        recentPanel.add(new JScrollPane(recentTable), BorderLayout.CENTER);
        center.add(recentPanel, BorderLayout.CENTER);

        add(center, BorderLayout.CENTER);
    }

    private void openMovementPackageDetails(StockMovement movement) {
        Optional<InventoryPackage> pkgOpt = ctx.getPackageDao().findById(movement.packageId());
        if (pkgOpt.isEmpty() && movement.packageQrCode() != null) {
            pkgOpt = ctx.getPackageDao().findByQrCode(movement.packageQrCode());
        }
        if (pkgOpt.isPresent()) {
            Window owner = SwingUtilities.getWindowAncestor(this);
            if (EditPackageDialog.showDialog(owner, ctx, pkgOpt.get()) != null) {
                refreshData();
            }
        }
    }

    private static void configureKpiCard(JPanel card, JLabel title, JLabel value) {
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        title.setFont(ThemeManager.monoFont(Font.BOLD, 11f));
        value.setFont(ThemeManager.monoFont(Font.BOLD, 24f));
        card.add(title);
        card.add(Box.createVerticalStrut(6));
        card.add(value);
    }

    private void rebuildCharts() {
        I18nManager i18n = ctx.getI18n();
        String lang = i18n.getActiveLanguage();
        boolean dark = ThemeManager.isDarkMode();

        Color bg = ThemeManager.bg();
        Color fg = ThemeManager.fg();
        Color border = ThemeManager.border();
        Color grid = ThemeManager.grid();

        Color[] monoPiePalette = dark
                ? new Color[]{Color.WHITE, new Color(0xBDBDBD), new Color(0x7D7D7D), new Color(0x454545), new Color(0xE0E0E0)}
                : new Color[]{Color.BLACK, new Color(0x4A4A4A), new Color(0x888888), new Color(0xBDBDBD), new Color(0x222222)};

        // 1. Quality Grade Pie Chart
        qualityChartHost.removeAll();
        List<CategoryWeightStat> qualityStats = ctx.getPackageDao().sumInStockWeightByQualityGrade();
        PieChart pieChart = new PieChartBuilder()
                .width(420)
                .height(260)
                .title(i18n.t("dashboard.chart.by_quality").toUpperCase())
                .build();
        pieChart.getStyler().setChartBackgroundColor(bg);
        pieChart.getStyler().setPlotBackgroundColor(bg);
        pieChart.getStyler().setChartFontColor(fg);
        pieChart.getStyler().setPlotBorderColor(border);
        pieChart.getStyler().setLegendBackgroundColor(bg);
        pieChart.getStyler().setLegendBorderColor(border);
        pieChart.getStyler().setChartTitleFont(ThemeManager.monoFont(Font.BOLD, 12f));
        pieChart.getStyler().setLegendFont(ThemeManager.monoFont(Font.PLAIN, 11f));
        pieChart.getStyler().setSeriesColors(monoPiePalette);

        if (qualityStats.isEmpty()) {
            pieChart.addSeries("N/A", 1);
        } else {
            for (CategoryWeightStat st : qualityStats) {
                if (st.totalWeightKg().compareTo(BigDecimal.ZERO) > 0) {
                    pieChart.addSeries(st.code() + " (" + st.localizedName(lang) + ")", st.totalWeightKg().doubleValue());
                }
            }
        }
        qualityChartHost.add(new XChartPanel<>(pieChart), BorderLayout.CENTER);

        // 2. Garment Type Bar Chart
        garmentChartHost.removeAll();
        List<CategoryWeightStat> garmentStats = ctx.getPackageDao().sumInStockWeightByGarmentType();
        CategoryChart barChart = new CategoryChartBuilder()
                .width(480)
                .height(260)
                .title(i18n.t("dashboard.chart.by_type").toUpperCase())
                .xAxisTitle("")
                .yAxisTitle(i18n.t("common.kg").toUpperCase())
                .build();
        barChart.getStyler().setChartBackgroundColor(bg);
        barChart.getStyler().setPlotBackgroundColor(bg);
        barChart.getStyler().setChartFontColor(fg);
        barChart.getStyler().setAxisTickLabelsColor(fg);
        barChart.getStyler().setPlotGridLinesColor(grid);
        barChart.getStyler().setPlotBorderColor(border);
        barChart.getStyler().setLegendVisible(false);
        barChart.getStyler().setChartTitleFont(ThemeManager.monoFont(Font.BOLD, 12f));
        barChart.getStyler().setAxisTickLabelsFont(ThemeManager.monoFont(Font.BOLD, 10.5f));
        barChart.getStyler().setSeriesColors(new Color[]{fg});

        List<String> xLabels = new ArrayList<>();
        List<Double> yValues = new ArrayList<>();
        if (garmentStats.isEmpty()) {
            xLabels.add("-");
            yValues.add(0.0);
        } else {
            for (CategoryWeightStat st : garmentStats) {
                xLabels.add(st.code());
                yValues.add(st.totalWeightKg().doubleValue());
            }
        }
        barChart.addSeries("kg", xLabels, yValues);
        garmentChartHost.add(new XChartPanel<>(barChart), BorderLayout.CENTER);

        chartsGrid.revalidate();
        chartsGrid.repaint();
    }

    private static String stripZeros(BigDecimal val) {
        if (val == null) return "0";
        BigDecimal s = val.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_DASHBOARD;
    }

    @Override
    public String getTitleI18nKey() {
        return "dashboard.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        I18nManager i18n = ctx.getI18n();
        int inStockCount = ctx.getPackageDao().countByStatus(PackageStatus.IN_STOCK)
                + ctx.getPackageDao().countByStatus(PackageStatus.RESERVED);
        BigDecimal inStockWeight = ctx.getPackageDao().sumWeightByStatus(PackageStatus.IN_STOCK)
                .add(ctx.getPackageDao().sumWeightByStatus(PackageStatus.RESERVED));
        int todayIntake = ctx.getPackageDao().countCreatedOnDate(LocalDate.now());
        int openOrders = ctx.getOrderDao().countOpenOrders();

        kpiValue1.setText(Integer.toString(inStockCount));
        kpiValue2.setText(stripZeros(inStockWeight) + " " + i18n.t("common.kg"));
        kpiValue3.setText(Integer.toString(todayIntake));
        kpiValue4.setText(Integer.toString(openOrders));

        rebuildCharts();

        recentModel.setRowCount(0);
        recentMovements = ctx.getMovementDao().findRecent(15);
        for (StockMovement m : recentMovements) {
            String fromTo = (m.fromLocationCode() != null ? m.fromLocationCode() : "—")
                    + " -> " + (m.toLocationCode() != null ? m.toLocationCode() : "—");
            recentModel.addRow(new Object[]{
                    m.createdAt() != null ? DT_FMT.format(m.createdAt()) : "",
                    m.packageQrCode(),
                    i18n.t("movement." + m.movementType().name()).toUpperCase(),
                    fromTo,
                    m.userFullName(),
                    m.note() != null ? m.note() : ""
            });
        }
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        kpiTitle1.setText(i18n.t("dashboard.kpi.bales").toUpperCase());
        kpiTitle2.setText(i18n.t("dashboard.kpi.weight").toUpperCase());
        kpiTitle3.setText(i18n.t("dashboard.kpi.today_intake").toUpperCase());
        kpiTitle4.setText(i18n.t("dashboard.kpi.open_orders").toUpperCase());

        recentTitleLabel.setText(i18n.t("dashboard.recent_movements").toUpperCase());
        refreshBtn.setText(i18n.t("common.refresh").toUpperCase());

        recentModel.setColumnIdentifiers(new Object[]{
                i18n.t("common.created_at").toUpperCase(),
                i18n.t("intake.generated_code").toUpperCase(),
                i18n.t("common.actions").toUpperCase(),
                i18n.t("intake.location").toUpperCase(),
                i18n.t("scanner.field.operator").toUpperCase(),
                i18n.t("common.notes").toUpperCase()
        });

        refreshData();
    }

    @Override
    public void applyTheme() {
        setBackground(ThemeManager.bg());
        chartsGrid.setBackground(ThemeManager.bg());
        BrutalistStyle.applyBoxCard(kpiCard1, 14, 16);
        BrutalistStyle.applyBoxCard(kpiCard2, 14, 16);
        BrutalistStyle.applyBoxCard(kpiCard3, 14, 16);
        BrutalistStyle.applyBoxCard(kpiCard4, 14, 16);
        kpiTitle1.setForeground(ThemeManager.muted());
        kpiTitle2.setForeground(ThemeManager.muted());
        kpiTitle3.setForeground(ThemeManager.muted());
        kpiTitle4.setForeground(ThemeManager.muted());
        kpiValue1.setForeground(ThemeManager.fg());
        kpiValue2.setForeground(ThemeManager.fg());
        kpiValue3.setForeground(ThemeManager.fg());
        kpiValue4.setForeground(ThemeManager.fg());
        recentTitleLabel.setForeground(ThemeManager.fg());

        BrutalistStyle.applyBoxCard(qualityChartHost, 8, 8);
        BrutalistStyle.applyBoxCard(garmentChartHost, 8, 8);
        BrutalistStyle.styleTable(recentTable);
        rebuildCharts();
    }
}
