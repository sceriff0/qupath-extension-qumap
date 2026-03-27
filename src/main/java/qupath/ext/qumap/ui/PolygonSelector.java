package qupath.ext.qumap.ui;

import javafx.scene.input.MouseEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Interactive polygon drawing controller for UmapCanvas.
 * Click to add vertices, double-click to close and complete the polygon.
 */
public class PolygonSelector {

    private final UmapCanvas canvas;
    private final List<double[]> vertices = new ArrayList<>();
    private boolean active = false;
    private Consumer<List<double[]>> onPolygonComplete;

    public PolygonSelector(UmapCanvas canvas) {
        this.canvas = canvas;
    }

    public void setOnPolygonComplete(Consumer<List<double[]>> cb) {
        this.onPolygonComplete = cb;
    }

    public void activate() {
        active = true;
        vertices.clear();
        canvas.setPolygonOverlay(null);

        canvas.setOnMouseClicked(this::handleClick);
    }

    public void deactivate() {
        active = false;
        vertices.clear();
        canvas.setOnMouseClicked(e -> {
            // Restore double-click-to-reset
            if (e.getClickCount() == 2) canvas.resetView();
        });
    }

    public boolean isActive() { return active; }

    public void clear() {
        vertices.clear();
        canvas.clearPolygonOverlay();
    }

    private void handleClick(MouseEvent e) {
        if (!active) return;

        if (e.getClickCount() == 2) {
            // Remove the spurious vertex added by the preceding single-click event
            if (!vertices.isEmpty()) vertices.remove(vertices.size() - 1);
            // Complete polygon
            if (vertices.size() >= 3 && onPolygonComplete != null) {
                onPolygonComplete.accept(new ArrayList<>(vertices));
            }
            e.consume();
        } else if (e.getClickCount() == 1) {
            double dx = canvas.screenXToDataX(e.getX());
            double dy = canvas.screenYToDataY(e.getY());
            vertices.add(new double[]{dx, dy});
            canvas.setPolygonOverlay(new ArrayList<>(vertices));
            e.consume();
        }
    }

    /**
     * Compute a boolean mask: true = inside the polygon.
     */
    public boolean[] computeInsideMask(double[] umapX, double[] umapY) {
        int n = umapX.length;
        boolean[] mask = new boolean[n];
        if (vertices.size() < 3) return mask;

        for (int i = 0; i < n; i++) {
            mask[i] = UmapCanvas.pointInPolygon(umapX[i], umapY[i], vertices);
        }
        return mask;
    }

    public List<double[]> getVertices() { return vertices; }
}
