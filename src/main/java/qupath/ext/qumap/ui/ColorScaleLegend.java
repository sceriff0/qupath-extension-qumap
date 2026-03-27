package qupath.ext.qumap.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.Locale;

/**
 * Vertical gradient legend for continuous color scales (z-score or raw intensity).
 */
public class ColorScaleLegend extends Canvas {

    private double minValue;
    private double maxValue;
    private MarkerOverlayCanvas.ColorScale colorScale;
    private String label;

    public ColorScaleLegend() {
        super(30, 200);
        widthProperty().addListener((obs, o, n) -> repaint());
        heightProperty().addListener((obs, o, n) -> repaint());
    }

    @Override public boolean isResizable() { return true; }
    @Override public double prefWidth(double h) { return 30; }
    @Override public double prefHeight(double w) { return 200; }
    @Override public double minWidth(double h) { return 25; }
    @Override public double minHeight(double w) { return 80; }
    @Override public double maxWidth(double h) { return 40; }
    @Override public double maxHeight(double w) { return Double.MAX_VALUE; }

    @Override
    public void resize(double width, double height) {
        setWidth(width);
        setHeight(height);
        repaint();
    }

    public void setScale(double min, double max, MarkerOverlayCanvas.ColorScale scale, String label) {
        this.minValue = min;
        this.maxValue = max;
        this.colorScale = scale;
        this.label = label;
        repaint();
    }

    public void clear() {
        this.colorScale = null;
        repaint();
    }

    private void repaint() {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();

        gc.setFill(Color.rgb(42, 42, 42));
        gc.fillRect(0, 0, w, h);

        if (colorScale == null) return;

        double barX = 5;
        double barW = 12;
        double barTop = 20;
        double barBottom = h - 20;
        double barH = barBottom - barTop;
        if (barH <= 0) return;

        // Draw gradient bar (top = max, bottom = min)
        for (int y = 0; y < (int) barH; y++) {
            double t = 1.0 - (double) y / barH; // top=1, bottom=0
            gc.setFill(mapColor(t));
            gc.fillRect(barX, barTop + y, barW, 1);
        }

        // Border
        gc.setStroke(Color.gray(0.4));
        gc.setLineWidth(0.5);
        gc.strokeRect(barX, barTop, barW, barH);

        // Labels
        gc.setFill(Color.gray(0.7));
        gc.setFont(Font.font(8));
        gc.fillText(String.format(Locale.US, "%.1f", maxValue), barX + barW + 2, barTop + 8);
        gc.fillText(String.format(Locale.US, "%.1f", minValue), barX + barW + 2, barBottom);
    }

    private Color mapColor(double t) {
        if (colorScale == MarkerOverlayCanvas.ColorScale.BLUE_WHITE_RED) {
            if (t < 0.5) {
                double s = t * 2;
                return Color.color(s, s, 1.0);
            } else {
                double s = (t - 0.5) * 2;
                return Color.color(1.0, 1.0 - s, 1.0 - s);
            }
        } else {
            // Viridis-like
            if (t < 0.25) {
                double s = t / 0.25;
                return Color.color(lerp(0.27, 0.13, s), lerp(0.0, 0.14, s), lerp(0.33, 0.42, s));
            } else if (t < 0.5) {
                double s = (t - 0.25) / 0.25;
                return Color.color(lerp(0.13, 0.15, s), lerp(0.14, 0.40, s), lerp(0.42, 0.44, s));
            } else if (t < 0.75) {
                double s = (t - 0.5) / 0.25;
                return Color.color(lerp(0.15, 0.45, s), lerp(0.40, 0.68, s), lerp(0.44, 0.19, s));
            } else {
                double s = (t - 0.75) / 0.25;
                return Color.color(lerp(0.45, 0.99, s), lerp(0.68, 0.91, s), lerp(0.19, 0.15, s));
            }
        }
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
