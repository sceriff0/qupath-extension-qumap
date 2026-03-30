package qupath.ext.qumap.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Canvas-based 2D scatter plot for marker expression overlay on UMAP.
 * Colors points by z-score or raw intensity using a continuous color scale.
 */
public class MarkerOverlayCanvas extends Canvas {

    private static final int MAX_DISPLAY_POINTS = 30000;
    private static final double PADDING = 10;

    public enum ColorScale {
        BLUE_WHITE_RED(ColorScaleMapper.Scale.BLUE_WHITE_RED),
        VIRIDIS(ColorScaleMapper.Scale.VIRIDIS);

        final ColorScaleMapper.Scale mapperScale;
        ColorScale(ColorScaleMapper.Scale s) { this.mapperScale = s; }
    }

    private double[] xValues;
    private double[] yValues;
    private double[] markerValues;
    private String markerName;
    private ColorScale colorScale = ColorScale.BLUE_WHITE_RED;
    private double dotSize = 2.0;
    private double colorMin, colorMax;

    // Shared view state with UmapCanvas
    private double viewMinX, viewMaxX, viewMinY, viewMaxY;
    private boolean viewOverride = false;
    private double dataMinX, dataMaxX, dataMinY, dataMaxY;

    public MarkerOverlayCanvas() {
        super(400, 400);
        widthProperty().addListener((obs, o, n) -> repaint());
        heightProperty().addListener((obs, o, n) -> repaint());
    }

    @Override public boolean isResizable() { return true; }
    @Override public double prefWidth(double h) { return 400; }
    @Override public double prefHeight(double w) { return 400; }
    @Override public double minWidth(double h) { return 150; }
    @Override public double minHeight(double w) { return 150; }
    @Override public double maxWidth(double h) { return Double.MAX_VALUE; }
    @Override public double maxHeight(double w) { return Double.MAX_VALUE; }

    @Override
    public void resize(double width, double height) {
        setWidth(width);
        setHeight(height);
        repaint();
    }

    public void setData(double[] xValues, double[] yValues) {
        this.xValues = xValues;
        this.yValues = yValues;

        if (xValues != null && xValues.length > 0) {
            dataMinX = Double.MAX_VALUE; dataMaxX = -Double.MAX_VALUE;
            dataMinY = Double.MAX_VALUE; dataMaxY = -Double.MAX_VALUE;
            for (int i = 0; i < xValues.length; i++) {
                if (Double.isFinite(xValues[i]) && Double.isFinite(yValues[i])) {
                    dataMinX = Math.min(dataMinX, xValues[i]);
                    dataMaxX = Math.max(dataMaxX, xValues[i]);
                    dataMinY = Math.min(dataMinY, yValues[i]);
                    dataMaxY = Math.max(dataMaxY, yValues[i]);
                }
            }
            if (dataMinX == Double.MAX_VALUE) { dataMinX = 0; dataMaxX = 1; dataMinY = 0; dataMaxY = 1; }
            if (dataMaxX <= dataMinX) dataMaxX = dataMinX + 1;
            if (dataMaxY <= dataMinY) dataMaxY = dataMinY + 1;
            double padX = (dataMaxX - dataMinX) * 0.05;
            double padY = (dataMaxY - dataMinY) * 0.05;
            dataMinX -= padX; dataMaxX += padX;
            dataMinY -= padY; dataMaxY += padY;
        }
        repaint();
    }

    public void setMarkerValues(double[] values, String name, ColorScale scale) {
        this.markerValues = values;
        this.markerName = name;
        this.colorScale = scale;

        if (values != null && values.length > 0) {
            colorMin = Double.MAX_VALUE;
            colorMax = -Double.MAX_VALUE;
            for (double v : values) {
                if (Double.isFinite(v)) {
                    colorMin = Math.min(colorMin, v);
                    colorMax = Math.max(colorMax, v);
                }
            }
            // If no finite values found, use a safe default range
            if (colorMin == Double.MAX_VALUE) { colorMin = 0; colorMax = 1; }
            else if (colorMax <= colorMin) colorMax = colorMin + 1;
        }
        repaint();
    }

    public void setDotSize(double size) { this.dotSize = size; repaint(); }

    public void syncView(double vMinX, double vMaxX, double vMinY, double vMaxY, boolean override) {
        this.viewMinX = vMinX;
        this.viewMaxX = vMaxX;
        this.viewMinY = vMinY;
        this.viewMaxY = vMaxY;
        this.viewOverride = override;
        repaint();
    }

    public double getColorMin() { return colorMin; }
    public double getColorMax() { return colorMax; }
    public ColorScale getColorScale() { return colorScale; }

    private double eMinX() { return viewOverride ? viewMinX : dataMinX; }
    private double eMaxX() { return viewOverride ? viewMaxX : dataMaxX; }
    private double eMinY() { return viewOverride ? viewMinY : dataMinY; }
    private double eMaxY() { return viewOverride ? viewMaxY : dataMaxY; }

    private void repaint() {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();

        gc.setFill(Color.rgb(30, 30, 30));
        gc.fillRect(0, 0, w, h);

        if (xValues == null || markerValues == null || xValues.length == 0) {
            gc.setFill(Color.gray(0.5));
            gc.setFont(Font.font(12));
            String msg = markerName != null ? markerName : "Select a marker";
            gc.fillText(msg, w / 2 - 35, h / 2);
            return;
        }

        double plotW = w - 2 * PADDING;
        double plotH = h - 2 * PADDING;
        double emx = eMinX(), eMx = eMaxX(), emy = eMinY(), eMy = eMaxY();
        double rangeX = eMx - emx, rangeY = eMy - emy;

        int step = Math.max(1, xValues.length / MAX_DISPLAY_POINTS);
        double halfDot = dotSize / 2;

        for (int i = 0; i < xValues.length; i += step) {
            if (Double.isNaN(xValues[i]) || Double.isNaN(yValues[i])) continue;

            double px = PADDING + ((xValues[i] - emx) / rangeX) * plotW;
            double py = PADDING + plotH - ((yValues[i] - emy) / rangeY) * plotH;

            if (px < -dotSize || px > w + dotSize || py < -dotSize || py > h + dotSize) continue;

            double val = i < markerValues.length ? markerValues[i] : 0;
            gc.setFill(mapColor(val));
            gc.fillOval(px - halfDot, py - halfDot, dotSize, dotSize);
        }

        // Title
        gc.setFill(Color.gray(0.7));
        gc.setFont(Font.font(10));
        if (markerName != null) {
            gc.fillText(markerName, PADDING + 2, PADDING + 12);
        }

        // Border
        gc.setStroke(Color.gray(0.3));
        gc.setLineWidth(1);
        gc.strokeRect(PADDING, PADDING, plotW, plotH);
    }

    private Color mapColor(double value) {
        double t = (value - colorMin) / (colorMax - colorMin);
        return ColorScaleMapper.map(t, colorScale.mapperScale, 0.8);
    }
}
