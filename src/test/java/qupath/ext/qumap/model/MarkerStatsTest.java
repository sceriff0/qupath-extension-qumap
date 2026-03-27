package qupath.ext.qumap.model;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkerStatsTest {

    private static CellIndex buildIndex(List<String> markers, double[][] values) {
        int n = values[0].length;
        List<PathObject> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            var roi = ROIs.createPointsROI(i, i, ImagePlane.getDefaultPlane());
            var obj = PathObjects.createDetectionObject(roi);
            for (int m = 0; m < markers.size(); m++) {
                obj.getMeasurements().put(markers.get(m), values[m][i]);
            }
            cells.add(obj);
        }
        return CellIndex.build(cells, markers);
    }

    @Test
    void computeWithKnownDistribution() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{1, 2, 3, 4, 5}});
        MarkerStats stats = MarkerStats.compute(index);

        assertEquals(3.0, stats.getMean("CD45"), 0.001);
        assertEquals(Math.sqrt(2.0), stats.getStd("CD45"), 0.001);
        assertEquals(1.0, stats.getMin("CD45"), 0.001);
        assertEquals(5.0, stats.getMax("CD45"), 0.001);
    }

    @Test
    void zScoreConversion() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{1, 2, 3, 4, 5}});
        MarkerStats stats = MarkerStats.compute(index);

        assertEquals(0.0, stats.toZScore("CD45", 3.0), 0.001);
        assertEquals(Math.sqrt(2.0), stats.toZScore("CD45", 5.0), 0.001);
    }

    @Test
    void singleCell() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{7.0}});
        MarkerStats stats = MarkerStats.compute(index);

        assertEquals(7.0, stats.getMean("CD45"), 0.001);
        assertEquals(0.0, stats.getStd("CD45"), 0.001);
    }

    @Test
    void constantValuesGiveZeroStd() {
        double[] vals = new double[10];
        Arrays.fill(vals, 5.0);
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{vals});
        MarkerStats stats = MarkerStats.compute(index);

        assertEquals(0.0, stats.getStd("CD45"), 0.001);
        assertEquals(0.0, stats.toZScore("CD45", 5.0), 0.001);
    }

    @Test
    void multipleMarkersComputedIndependently() {
        double[] cd45 = {1, 2, 3, 4, 5};
        double[] cd3 = {10, 20, 30, 40, 50};
        CellIndex index = buildIndex(List.of("CD45", "CD3"), new double[][]{cd45, cd3});
        MarkerStats stats = MarkerStats.compute(index);

        assertEquals(3.0, stats.getMean("CD45"), 0.001);
        assertEquals(30.0, stats.getMean("CD3"), 0.001);
        assertNotEquals(stats.getStd("CD45"), stats.getStd("CD3"));
    }

    @Test
    void unknownMarkerReturnsZeroDefaults() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{1, 2, 3}});
        MarkerStats stats = MarkerStats.compute(index);

        assertEquals(0.0, stats.getMean("NONEXISTENT"), 0.001);
        assertEquals(0.0, stats.getStd("NONEXISTENT"), 0.001);
    }
}
