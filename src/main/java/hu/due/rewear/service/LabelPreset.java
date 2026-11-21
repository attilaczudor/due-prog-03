package hu.due.rewear.service;

/**
 * Supported paper & thermal ticket presets for QR label preview, printing, and PDF export.
 */
public enum LabelPreset {
    THERMAL_100X150("print.preset.thermal_100x150", 100.0, 150.0, 1, 1),
    THERMAL_100X100("print.preset.thermal_100x100", 100.0, 100.0, 1, 1),
    THERMAL_80X100("print.preset.thermal_80x100", 80.0, 100.0, 1, 1),
    A6_CARD("print.preset.a6", 105.0, 148.0, 1, 1),
    A4_4UP("print.preset.a4_4up", 210.0, 297.0, 2, 2);

    private static final double MM_PER_INCH = 25.4;

    private final String i18nKey;
    private final double widthMm;
    private final double heightMm;
    private final int columns;
    private final int rows;

    LabelPreset(String i18nKey, double widthMm, double heightMm, int columns, int rows) {
        this.i18nKey = i18nKey;
        this.widthMm = widthMm;
        this.heightMm = heightMm;
        this.columns = columns;
        this.rows = rows;
    }

    public String getI18nKey() {
        return i18nKey;
    }

    public double getWidthMm() {
        return widthMm;
    }

    public double getHeightMm() {
        return heightMm;
    }

    public int getColumns() {
        return columns;
    }

    public int getRows() {
        return rows;
    }

    public int getLabelsPerPage() {
        return columns * rows;
    }

    /** Width in PostScript/Java2D points (72 pt = 1 inch). */
    public double widthPoints() {
        return widthMm * 72.0 / MM_PER_INCH;
    }

    /** Height in PostScript/Java2D points (72 pt = 1 inch). */
    public double heightPoints() {
        return heightMm * 72.0 / MM_PER_INCH;
    }
}
