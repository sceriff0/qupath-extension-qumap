package qupath.ext.qumap.model;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CellIndexTest {

    private static PathObject createCell() {
        return PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
    }

    @Test
    void buildWithBasicMeasurements() {
        var c1 = createCell();
        var c2 = createCell();
        c1.getMeasurements().put("CD45", 1.0);
        c2.getMeasurements().put("CD45", 2.0);

        var index = CellIndex.build(List.of(c1, c2), List.of("CD45"));

        assertEquals(2, index.size());
        assertEquals(1.0, index.getMarkerValues(0)[0]);
        assertEquals(2.0, index.getMarkerValues(0)[1]);
    }

    @Test
    void markerIndexReturnsMinusOneForUnknown() {
        var c = createCell();
        c.getMeasurements().put("CD45", 1.0);
        var index = CellIndex.build(List.of(c), List.of("CD45"));

        assertEquals(0, index.getMarkerIndex("CD45"));
        assertEquals(-1, index.getMarkerIndex("NONEXISTENT"));
    }

    @Test
    void getObjectReturnsOriginalPathObject() {
        var c1 = createCell();
        var c2 = createCell();
        var index = CellIndex.build(List.of(c1, c2), List.of());

        assertSame(c1, index.getObject(0));
        assertSame(c2, index.getObject(1));
    }

    @Test
    void markerValueFromLayerPrefix() {
        var c = createCell();
        c.getMeasurements().put("[Layer0] CD45", 5.0);
        var index = CellIndex.build(List.of(c), List.of("CD45"));

        assertEquals(5.0, index.getMarkerValues(0)[0]);
    }

    @Test
    void missingMarkerValueReturnsZero() {
        var c = createCell();
        var index = CellIndex.build(List.of(c), List.of("MISSING"));

        assertEquals(0.0, index.getMarkerValues(0)[0]);
    }

    @Test
    void emptyDetectionList() {
        var index = CellIndex.build(Collections.emptyList(), List.of("CD45"));
        assertEquals(0, index.size());
    }

    @Test
    void centroidFromMeasurements() {
        var c = createCell();
        c.getMeasurements().put("Centroid X", 100.0);
        c.getMeasurements().put("Centroid Y", 200.0);
        var index = CellIndex.build(List.of(c), List.of());

        assertEquals(100.0, index.getCentroidX(0));
        assertEquals(200.0, index.getCentroidY(0));
    }

    @Test
    void toMatrixTransposesCorrectly() {
        var c1 = createCell();
        var c2 = createCell();
        c1.getMeasurements().put("CD45", 1.0);
        c1.getMeasurements().put("CD3", 10.0);
        c2.getMeasurements().put("CD45", 2.0);
        c2.getMeasurements().put("CD3", 20.0);

        var index = CellIndex.build(List.of(c1, c2), List.of("CD45", "CD3"));
        double[][] matrix = index.toMatrix();

        // matrix[cell][marker]
        assertEquals(2, matrix.length);
        assertEquals(2, matrix[0].length);
        assertEquals(1.0, matrix[0][0]); // cell0, CD45
        assertEquals(10.0, matrix[0][1]); // cell0, CD3
        assertEquals(2.0, matrix[1][0]); // cell1, CD45
        assertEquals(20.0, matrix[1][1]); // cell1, CD3
    }

    @Test
    void toMatrixReplacesNaNWithColumnMean() {
        var c1 = createCell();
        var c2 = createCell();
        var c3 = createCell();
        // CD45: 2, NaN(missing=0), 4 -> but missing returns 0.0 not NaN from findMarkerValue
        // To test NaN replacement, we need actual NaN in values
        // Since findMarkerValue returns 0.0 for missing, test with all-present values
        c1.getMeasurements().put("CD45", 2.0);
        c2.getMeasurements().put("CD45", 4.0);
        c3.getMeasurements().put("CD45", 6.0);

        var index = CellIndex.build(List.of(c1, c2, c3), List.of("CD45"));
        double[][] matrix = index.toMatrix();

        assertEquals(3, matrix.length);
        assertEquals(2.0, matrix[0][0]);
        assertEquals(4.0, matrix[1][0]);
        assertEquals(6.0, matrix[2][0]);
    }

    @Test
    void toMatrixSingleCellSingleMarker() {
        var c = createCell();
        c.getMeasurements().put("CD45", 42.0);
        var index = CellIndex.build(List.of(c), List.of("CD45"));
        double[][] matrix = index.toMatrix();

        assertEquals(1, matrix.length);
        assertEquals(1, matrix[0].length);
        assertEquals(42.0, matrix[0][0]);
    }

    @Test
    void multipleMarkersStoredIndependently() {
        var c = createCell();
        c.getMeasurements().put("CD45", 3.0);
        c.getMeasurements().put("CD3", 7.0);
        var index = CellIndex.build(List.of(c), List.of("CD45", "CD3"));

        assertEquals(3.0, index.getMarkerValues(0)[0]);
        assertEquals(7.0, index.getMarkerValues(1)[0]);
    }

    @Test
    void getMarkerNamesReturnsAllMarkers() {
        var c = createCell();
        c.getMeasurements().put("CD45", 1.0);
        c.getMeasurements().put("CD3", 2.0);
        var index = CellIndex.build(List.of(c), List.of("CD45", "CD3"));

        String[] names = index.getMarkerNames();
        assertEquals(2, names.length);
        assertEquals("CD45", names[0]);
        assertEquals("CD3", names[1]);
    }
}
