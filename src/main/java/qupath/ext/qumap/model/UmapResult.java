package qupath.ext.qumap.model;

import qupath.lib.objects.PathObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable result of a UMAP computation.
 * Stores 2D embedding coordinates with back-references to PathObjects.
 */
public class UmapResult {

    private final double[] umapX;
    private final double[] umapY;
    private final PathObject[] objects;
    private final String[] markerNames;
    private final UmapParameters params;

    public UmapResult(double[] umapX, double[] umapY, PathObject[] objects,
                      String[] markerNames, UmapParameters params) {
        Objects.requireNonNull(umapX, "umapX");
        Objects.requireNonNull(umapY, "umapY");
        Objects.requireNonNull(objects, "objects");
        Objects.requireNonNull(markerNames, "markerNames");
        if (umapX.length != umapY.length || umapX.length != objects.length) {
            throw new IllegalArgumentException(
                    "Array length mismatch: umapX=%d, umapY=%d, objects=%d"
                            .formatted(umapX.length, umapY.length, objects.length));
        }
        this.umapX = umapX;
        this.umapY = umapY;
        this.objects = objects;
        this.markerNames = markerNames;
        this.params = params;
    }

    public double[] getUmapX() { return umapX.clone(); }
    public double[] getUmapY() { return umapY.clone(); }
    public PathObject[] getObjects() { return objects.clone(); }
    public String[] getMarkerNames() { return markerNames.clone(); }
    public UmapParameters getParams() { return params; }
    public int size() { return umapX.length; }

    /**
     * Export cell data to CSV in FlowPath-compatible format with UMAP coordinates
     * and population tags.
     * <p>
     * Columns: cell_id, phenotype, population, centroid_x, centroid_y, umap_x, umap_y,
     * then per-marker: {marker}_raw, {marker}_zscore.
     * <p>
     * When cells have been tagged via polygon gating, the PathClass has the form
     * "BasePhenotype: TagName". This method splits that into phenotype and population columns.
     *
     * @param file           destination CSV file
     * @param cellIndex      the cell index containing marker data and centroids
     * @param markerStats    per-marker statistics for z-score computation (may be null)
     * @param populationTags list of population tags (may be null or empty)
     */
    public void exportToCsv(File file, CellIndex cellIndex, MarkerStats markerStats,
                            List<PopulationTag> populationTags) throws IOException {
        if (cellIndex.size() != umapX.length) {
            throw new IllegalArgumentException(
                    "CellIndex size %d does not match UmapResult size %d"
                            .formatted(cellIndex.size(), umapX.length));
        }
        boolean hasTags = populationTags != null && !populationTags.isEmpty();
        String[] markers = cellIndex.getMarkerNames();

        // Pre-fetch all marker value arrays once (avoid cloning per cell per marker)
        double[][] allMarkerValues = new double[markers.length][];
        for (int m = 0; m < markers.length; m++) {
            allMarkerValues[m] = cellIndex.getMarkerValues(m);
        }

        try (var writer = new BufferedWriter(new FileWriter(file))) {
            // Header
            writer.write("cell_id,phenotype,population,centroid_x,centroid_y,umap_x,umap_y");
            for (String marker : markers) {
                String safe = escapeCsv(marker);
                writer.write("," + safe + "_raw");
                writer.write("," + safe + "_zscore");
            }
            writer.newLine();

            // One row per cell
            for (int i = 0; i < umapX.length; i++) {
                var pc = objects[i].getPathClass();
                String fullLabel = pc != null ? pc.toString() : "Unclassified";

                String phenotype;
                String population = "";
                int sepIdx = fullLabel.lastIndexOf(": ");
                if (hasTags && sepIdx >= 0) {
                    String possibleTag = fullLabel.substring(sepIdx + 2);
                    boolean isKnownTag = populationTags.stream()
                            .anyMatch(t -> t.name().equals(possibleTag));
                    if (isKnownTag) {
                        phenotype = fullLabel.substring(0, sepIdx);
                        population = possibleTag;
                    } else {
                        phenotype = fullLabel;
                    }
                } else {
                    phenotype = fullLabel;
                }

                // cell_id, phenotype, population
                writer.write(String.valueOf(i));
                writer.write(',');
                writer.write(escapeCsv(phenotype));
                writer.write(',');
                writer.write(escapeCsv(population));

                // centroid_x, centroid_y
                writer.write(',' + fmt(cellIndex.getCentroidX(i)));
                writer.write(',' + fmt(cellIndex.getCentroidY(i)));

                // umap_x, umap_y
                writer.write(',' + fmt(umapX[i]));
                writer.write(',' + fmt(umapY[i]));

                // Per-marker: raw, zscore
                for (int m = 0; m < markers.length; m++) {
                    double raw = allMarkerValues[m][i];
                    double zscore;
                    if (Double.isNaN(raw) || markerStats == null) {
                        zscore = Double.NaN;
                    } else {
                        zscore = markerStats.toZScore(markers[m], raw);
                    }
                    writer.write(',' + fmt(raw));
                    writer.write(',' + fmt(zscore));
                }
                writer.newLine();
            }
        }
    }

    /** Format a double for CSV; NaN → empty string. */
    private static String fmt(double val) {
        return Double.isNaN(val) ? "" : String.format(Locale.US, "%.4f", val);
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
