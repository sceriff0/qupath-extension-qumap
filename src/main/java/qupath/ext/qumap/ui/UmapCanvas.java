package qupath.ext.qumap.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;

/**
 * Canvas-based 2D scatter plot for UMAP visualization.
 * Supports per-point coloring, zoom/pan, subsampling, and population ring rendering.
 */
public class UmapCanvas extends Canvas {

    private static final int MAX_DISPLAY_POINTS = 30000;
    private static final double PADDING_LEFT = 10;
    private static final double PADDING_RIGHT = 10;
    private static final double PADDING_TOP = 10;
    private static final double PADDING_BOTTOM = 10;

    private double[] xValues;
    private double[] yValues;
    private int[] pointColors;     // packed RGB per point
    private double minX, maxX, minY, maxY;

    // Zoom/pan
    private double viewMinX, viewMaxX, viewMinY, viewMaxY;
    private boolean viewOverride = false;
    private double panStartX, panStartY;
    private double panStartViewMinX, panStartViewMinY;
    private boolean panning = false;

    // Rendering
    private double dotSize = 2.0;

    // Population rings
    private List<int[]> ringColors;    // packed RGB per population
    private List<boolean[]> ringMasks; // one mask per population

    // Polygon overlay
    private List<double[]> polygonVertices;

    // View change listener
    private Runnable onViewChanged;

    public void setOnViewChanged(Runnable cb) { this.onViewChanged = cb; }

    private void fireViewChanged() {
        if (onViewChanged != null) onViewChanged.run();
    }

    public double getViewMinX() { return effectiveMinX(); }
    public double getViewMaxX() { return effectiveMaxX(); }
    public double getViewMinY() { return effectiveMinY(); }
    public double getViewMaxY() { return effectiveMaxY(); }
    public boolean isViewOverride() { return viewOverride; }

    public UmapCanvas() {
        super(400, 400);
        widthProperty().addListener((obs, o, n) -> repaint());
        heightProperty().addListener((obs, o, n) -> repaint());

        // Zoom with scroll wheel
        setOnScroll(e -> {
            if (xValues == null) return;
            double factor = e.getDeltaY() > 0 ? 0.9 : 1.1;
            double cx = screenXToDataX(e.getX());
            double cy = screenYToDataY(e.getY());

            double eMinX = effectiveMinX(), eMaxX = effectiveMaxX();
            double eMinY = effectiveMinY(), eMaxY = effectiveMaxY();

            viewMinX = cx + (eMinX - cx) * factor;
            viewMaxX = cx + (eMaxX - cx) * factor;
            viewMinY = cy + (eMinY - cy) * factor;
            viewMaxY = cy + (eMaxY - cy) * factor;
            viewOverride = true;
            repaint();
            fireViewChanged();
        });

        // Pan with middle mouse button
        setOnMousePressed(e -> {
            if (e.isMiddleButtonDown()) {
                panning = true;
                panStartX = e.getX();
                panStartY = e.getY();
                panStartViewMinX = effectiveMinX();
                panStartViewMinY = effectiveMinY();
                e.consume();
            }
        });
        setOnMouseDragged(e -> {
            if (panning) {
                double dx = screenXToDataX(panStartX) - screenXToDataX(e.getX());
                double dy = screenYToDataY(panStartY) - screenYToDataY(e.getY());
                double rangeX = effectiveMaxX() - effectiveMinX();
                double rangeY = effectiveMaxY() - effectiveMinY();
                viewMinX = panStartViewMinX + dx;
                viewMaxX = viewMinX + rangeX;
                viewMinY = panStartViewMinY + dy;
                viewMaxY = viewMinY + rangeY;
                viewOverride = true;
                repaint();
                fireViewChanged();
                e.consume();
            }
        });
        setOnMouseReleased(e -> {
            if (panning) {
                panning = false;
                e.consume();
            }
        });

        // Double-click to reset view
        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !e.isMiddleButtonDown()) {
                resetView();
            }
        });
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
        this.pointColors = null;
        this.viewOverride = false;

        if (xValues == null || yValues == null || xValues.length == 0) {
            repaint();
            return;
        }

        computeDataBounds();
        repaint();
    }

    private void computeDataBounds() {
        minX = Double.MAX_VALUE; maxX = -Double.MAX_VALUE;
        minY = Double.MAX_VALUE; maxY = -Double.MAX_VALUE;
        for (int i = 0; i < xValues.length; i++) {
            if (Double.isFinite(xValues[i]) && Double.isFinite(yValues[i])) {
                minX = Math.min(minX, xValues[i]);
                maxX = Math.max(maxX, xValues[i]);
                minY = Math.min(minY, yValues[i]);
                maxY = Math.max(maxY, yValues[i]);
            }
        }
        // If no finite values found, use safe defaults
        if (minX == Double.MAX_VALUE) { minX = 0; maxX = 1; minY = 0; maxY = 1; }
        if (maxX <= minX) maxX = minX + 1;
        if (maxY <= minY) maxY = minY + 1;

        double padX = (maxX - minX) * 0.05;
        double padY = (maxY - minY) * 0.05;
        minX -= padX; maxX += padX;
        minY -= padY; maxY += padY;
    }

    public void setPointColors(int[] colors) {
        this.pointColors = colors;
        repaint();
    }

    public void setDotSize(double size) {
        this.dotSize = size;
        repaint();
    }

    public void setPopulationRings(List<int[]> ringColors, List<boolean[]> ringMasks) {
        this.ringColors = ringColors;
        this.ringMasks = ringMasks;
        repaint();
    }

    public void setPolygonOverlay(List<double[]> vertices) {
        this.polygonVertices = vertices;
        repaint();
    }

    public void clearPolygonOverlay() {
        this.polygonVertices = null;
        repaint();
    }

    public void resetView() {
        viewOverride = false;
        repaint();
        fireViewChanged();
    }

    // --- Coordinate conversion ---

    private double effectiveMinX() { return viewOverride ? viewMinX : minX; }
    private double effectiveMaxX() { return viewOverride ? viewMaxX : maxX; }
    private double effectiveMinY() { return viewOverride ? viewMinY : minY; }
    private double effectiveMaxY() { return viewOverride ? viewMaxY : maxY; }

    public double dataXToScreenX(double dataX) {
        double plotW = getWidth() - PADDING_LEFT - PADDING_RIGHT;
        return PADDING_LEFT + ((dataX - effectiveMinX()) / (effectiveMaxX() - effectiveMinX())) * plotW;
    }

    public double dataYToScreenY(double dataY) {
        double plotH = getHeight() - PADDING_TOP - PADDING_BOTTOM;
        return PADDING_TOP + plotH - ((dataY - effectiveMinY()) / (effectiveMaxY() - effectiveMinY())) * plotH;
    }

    public double screenXToDataX(double screenX) {
        double plotW = getWidth() - PADDING_LEFT - PADDING_RIGHT;
        if (plotW <= 0) return effectiveMinX();
        return effectiveMinX() + ((screenX - PADDING_LEFT) / plotW) * (effectiveMaxX() - effectiveMinX());
    }

    public double screenYToDataY(double screenY) {
        double plotH = getHeight() - PADDING_TOP - PADDING_BOTTOM;
        if (plotH <= 0) return effectiveMinY();
        return effectiveMinY() + ((PADDING_TOP + plotH - screenY) / plotH) * (effectiveMaxY() - effectiveMinY());
    }

    /**
     * Find the index of the nearest point within a screen distance threshold.
     */
    public int findNearestPoint(double screenX, double screenY, double maxScreenDist) {
        if (xValues == null) return -1;

        double bestDist = maxScreenDist * maxScreenDist;
        int bestIdx = -1;
        for (int i = 0; i < xValues.length; i++) {
            double px = dataXToScreenX(xValues[i]);
            double py = dataYToScreenY(yValues[i]);
            double d = (px - screenX) * (px - screenX) + (py - screenY) * (py - screenY);
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private void repaint() {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();

        // Background
        gc.setFill(Color.rgb(30, 30, 30));
        gc.fillRect(0, 0, w, h);

        if (xValues == null || yValues == null || xValues.length == 0) {
            gc.setFill(Color.gray(0.5));
            gc.setFont(Font.font(12));
            gc.fillText("No UMAP data", w / 2 - 35, h / 2);
            return;
        }

        double plotW = w - PADDING_LEFT - PADDING_RIGHT;
        double plotH = h - PADDING_TOP - PADDING_BOTTOM;
        double eMinX = effectiveMinX(), eMaxX = effectiveMaxX();
        double eMinY = effectiveMinY(), eMaxY = effectiveMaxY();
        double rangeX = eMaxX - eMinX;
        double rangeY = eMaxY - eMinY;

        // Draw dots with subsampling
        int step = Math.max(1, xValues.length / MAX_DISPLAY_POINTS);
        double halfDot = dotSize / 2;

        for (int i = 0; i < xValues.length; i += step) {
            if (Double.isNaN(xValues[i]) || Double.isNaN(yValues[i])) continue;

            double px = PADDING_LEFT + ((xValues[i] - eMinX) / rangeX) * plotW;
            double py = PADDING_TOP + plotH - ((yValues[i] - eMinY) / rangeY) * plotH;

            // Skip points outside visible area
            if (px < PADDING_LEFT - dotSize || px > w - PADDING_RIGHT + dotSize ||
                py < PADDING_TOP - dotSize || py > h - PADDING_BOTTOM + dotSize) continue;

            // Point color
            if (pointColors != null && i < pointColors.length) {
                int c = pointColors[i];
                gc.setFill(Color.rgb((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, 0.7));
            } else {
                gc.setFill(Color.rgb(100, 150, 200, 0.7));
            }
            gc.fillOval(px - halfDot, py - halfDot, dotSize, dotSize);
        }

        // Population rings — separate pass over ALL tagged cells (not subsampled)
        if (ringColors != null && ringMasks != null) {
            for (int p = 0; p < ringColors.size(); p++) {
                boolean[] mask = ringMasks.get(p);
                int rc = ringColors.get(p)[0];
                gc.setStroke(Color.rgb((rc >> 16) & 0xFF, (rc >> 8) & 0xFF, rc & 0xFF));
                gc.setLineWidth(1.5);
                double ringR = halfDot + 2;

                for (int i = 0; i < xValues.length; i++) {
                    if (i >= mask.length || !mask[i]) continue;
                    if (Double.isNaN(xValues[i]) || Double.isNaN(yValues[i])) continue;

                    double px = PADDING_LEFT + ((xValues[i] - eMinX) / rangeX) * plotW;
                    double py = PADDING_TOP + plotH - ((yValues[i] - eMinY) / rangeY) * plotH;

                    if (px < PADDING_LEFT - dotSize || px > w - PADDING_RIGHT + dotSize ||
                        py < PADDING_TOP - dotSize || py > h - PADDING_BOTTOM + dotSize) continue;

                    gc.strokeOval(px - ringR, py - ringR, ringR * 2, ringR * 2);
                }
            }
        }

        // Draw polygon overlay
        if (polygonVertices != null && polygonVertices.size() >= 2) {
            gc.setStroke(Color.YELLOW);
            gc.setLineWidth(1.5);
            if (polygonVertices.size() >= 3) {
                double[] xp = new double[polygonVertices.size()];
                double[] yp = new double[polygonVertices.size()];
                for (int i = 0; i < polygonVertices.size(); i++) {
                    xp[i] = dataXToScreenX(polygonVertices.get(i)[0]);
                    yp[i] = dataYToScreenY(polygonVertices.get(i)[1]);
                }
                gc.strokePolygon(xp, yp, xp.length);
            } else {
                // Just 2 points: draw a line
                gc.strokeLine(
                    dataXToScreenX(polygonVertices.get(0)[0]),
                    dataYToScreenY(polygonVertices.get(0)[1]),
                    dataXToScreenX(polygonVertices.get(1)[0]),
                    dataYToScreenY(polygonVertices.get(1)[1])
                );
            }

            // Vertex handles
            gc.setFill(Color.CYAN);
            for (double[] v : polygonVertices) {
                double sx = dataXToScreenX(v[0]);
                double sy = dataYToScreenY(v[1]);
                gc.fillOval(sx - 4, sy - 4, 8, 8);
            }
        }

        // Border
        gc.setStroke(Color.gray(0.3));
        gc.setLineWidth(1);
        gc.strokeRect(PADDING_LEFT, PADDING_TOP, plotW, plotH);
    }

    /**
     * Ray-casting point-in-polygon test.
     */
    public static boolean pointInPolygon(double x, double y, List<double[]> vertices) {
        boolean inside = false;
        int n = vertices.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = vertices.get(i)[0], yi = vertices.get(i)[1];
            double xj = vertices.get(j)[0], yj = vertices.get(j)[1];
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }
}
