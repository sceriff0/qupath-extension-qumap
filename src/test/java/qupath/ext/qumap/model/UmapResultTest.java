package qupath.ext.qumap.model;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UmapResultTest {

    private static PathObject createCell(String classification) {
        var obj = PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
        if (classification != null) {
            obj.setPathClass(PathClass.fromString(classification));
        }
        return obj;
    }

    @Test
    void exportToCsvHasCorrectHeader() throws IOException {
        var obj = createCell("T-cell");
        var result = new UmapResult(
                new double[]{1.0}, new double[]{2.0},
                new PathObject[]{obj}, new String[]{"CD45"},
                UmapParameters.defaults());

        File temp = File.createTempFile("umap", ".csv");
        temp.deleteOnExit();
        result.exportToCsv(temp);

        List<String> lines = Files.readAllLines(temp.toPath());
        assertEquals("UMAP_X,UMAP_Y,Phenotype", lines.get(0));
    }

    @Test
    void exportToCsvHasCorrectCoordinates() throws IOException {
        var obj = createCell("T-cell");
        var result = new UmapResult(
                new double[]{-3.14159}, new double[]{2.71828},
                new PathObject[]{obj}, new String[]{"CD45"},
                UmapParameters.defaults());

        File temp = File.createTempFile("umap", ".csv");
        temp.deleteOnExit();
        result.exportToCsv(temp);

        List<String> lines = Files.readAllLines(temp.toPath());
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith("-3.141590,2.718280,T-cell"));
    }

    @Test
    void exportUnclassifiedCells() throws IOException {
        var obj = createCell(null); // no PathClass
        var result = new UmapResult(
                new double[]{0.0}, new double[]{0.0},
                new PathObject[]{obj}, new String[]{},
                UmapParameters.defaults());

        File temp = File.createTempFile("umap", ".csv");
        temp.deleteOnExit();
        result.exportToCsv(temp);

        List<String> lines = Files.readAllLines(temp.toPath());
        assertTrue(lines.get(1).endsWith("Unclassified"));
    }

    @Test
    void sizeMatchesArrayLength() {
        var result = new UmapResult(
                new double[]{1, 2, 3}, new double[]{4, 5, 6},
                new PathObject[3], new String[]{},
                UmapParameters.defaults());

        assertEquals(3, result.size());
    }
}
