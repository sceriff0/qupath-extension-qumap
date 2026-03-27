package qupath.ext.qumap.model;

import qupath.lib.objects.PathObject;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Columnar storage for cell data extracted from QuPath PathObjects.
 * Provides cache-friendly array access for UMAP computation and rendering.
 */
public class CellIndex {

    private final PathObject[] objects;
    private final String[] markerNames;
    private final double[][] values; // [markerIndex][cellIndex]
    private final double[] centroidX;
    private final double[] centroidY;
    private final int size;

    private CellIndex(PathObject[] objects, String[] markerNames, double[][] values,
                      double[] centroidX, double[] centroidY) {
        this.objects = objects;
        this.markerNames = markerNames;
        this.values = values;
        this.centroidX = centroidX;
        this.centroidY = centroidY;
        this.size = objects.length;
    }

    public static CellIndex build(Collection<PathObject> detections, List<String> markerNames) {
        int n = detections.size();
        int m = markerNames.size();

        PathObject[] objects = detections.toArray(new PathObject[0]);
        String[] markers = markerNames.toArray(new String[0]);
        double[][] values = new double[m][n];
        double[] centroidX = new double[n];
        double[] centroidY = new double[n];

        int i = 0;
        for (PathObject obj : objects) {
            Map<String, Number> measurements = getMeasurements(obj);

            centroidX[i] = findMeasurement(measurements, "Centroid X");
            centroidY[i] = findMeasurement(measurements, "Centroid Y");

            for (int j = 0; j < m; j++) {
                values[j][i] = findMarkerValue(measurements, markers[j]);
            }
            i++;
        }

        return new CellIndex(objects, markers, values, centroidX, centroidY);
    }

    private static Map<String, Number> getMeasurements(PathObject obj) {
        try {
            var m = obj.getMeasurements();
            if (m != null) return m;
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    private static double findMarkerValue(Map<String, Number> measurements, String channel) {
        Number val = measurements.get(channel);
        if (val != null) return val.doubleValue();

        String suffix = "] " + channel;
        for (Map.Entry<String, Number> entry : measurements.entrySet()) {
            if (entry.getKey().endsWith(suffix) && entry.getValue() != null) {
                return entry.getValue().doubleValue();
            }
        }
        return 0.0;
    }

    private static double findMeasurement(Map<String, Number> measurements, String key) {
        Number val = measurements.get(key);
        if (val != null) return val.doubleValue();

        String suffix = "] " + key;
        for (Map.Entry<String, Number> entry : measurements.entrySet()) {
            if (entry.getKey().endsWith(suffix) && entry.getValue() != null) {
                return entry.getValue().doubleValue();
            }
        }

        for (Map.Entry<String, Number> entry : measurements.entrySet()) {
            if (entry.getKey().startsWith(key) && entry.getValue() != null) {
                return entry.getValue().doubleValue();
            }
        }

        return Double.NaN;
    }

    /**
     * Extract marker data as a cell-by-marker matrix for UMAP input.
     * Transposes from [marker][cell] to [cell][marker] layout.
     * Replaces NaN values with column means.
     */
    public double[][] toMatrix() {
        int n = size;
        int m = markerNames.length;
        double[][] matrix = new double[n][m];

        for (int j = 0; j < m; j++) {
            double sum = 0;
            int count = 0;
            for (int i = 0; i < n; i++) {
                double v = values[j][i];
                if (!Double.isNaN(v)) {
                    sum += v;
                    count++;
                }
            }
            double mean = count > 0 ? sum / count : 0.0;

            for (int i = 0; i < n; i++) {
                double v = values[j][i];
                matrix[i][j] = Double.isNaN(v) ? mean : v;
            }
        }

        return matrix;
    }

    public double[] getMarkerValues(int markerIndex) { return values[markerIndex].clone(); }
    public int getMarkerIndex(String name) {
        for (int i = 0; i < markerNames.length; i++) {
            if (markerNames[i].equals(name)) return i;
        }
        return -1;
    }

    public PathObject getObject(int cellIndex) { return objects[cellIndex]; }
    public PathObject[] getObjects() { return objects.clone(); }
    public String[] getMarkerNames() { return markerNames.clone(); }
    public double getCentroidX(int i) { return centroidX[i]; }
    public double getCentroidY(int i) { return centroidY[i]; }
    public int size() { return size; }
}
