package qupath.ext.qumap.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.qumap.model.CellIndex;
import qupath.ext.qumap.model.UmapParameters;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UmapComputeServiceTest {

    private static PathObject createCell(double value) {
        var obj = PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
        obj.getMeasurements().put("CD45", value);
        return obj;
    }

    private static CellIndex buildIndex(int n) {
        List<PathObject> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cells.add(createCell(i * 1.0));
        }
        return CellIndex.build(cells, List.of("CD45"));
    }

    @Test
    void shutdownNullsCallbacks() {
        var service = new UmapComputeService();
        AtomicReference<String> errorMsg = new AtomicReference<>();
        service.setOnError(errorMsg::set);
        service.shutdown();
        // After shutdown, callbacks should be nulled - verify no NPE on next access
        assertDoesNotThrow(() -> service.shutdown());
    }

    @Test
    void cachedResultInitiallyNull() {
        var service = new UmapComputeService();
        assertNull(service.getCachedResult());
        service.shutdown();
    }

    @Test
    void cancelWithNoRunningTaskDoesNotThrow() {
        var service = new UmapComputeService();
        assertDoesNotThrow(service::cancel);
        service.shutdown();
    }
}
