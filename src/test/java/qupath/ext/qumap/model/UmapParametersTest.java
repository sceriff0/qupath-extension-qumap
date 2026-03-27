package qupath.ext.qumap.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UmapParametersTest {

    @Test
    void defaultsHaveExpectedValues() {
        var params = UmapParameters.defaults();

        assertEquals(15, params.k());
        assertEquals(0.1, params.minDist(), 0.001);
        assertEquals(1.0, params.spread(), 0.001);
        assertEquals(200, params.epochs());
    }

    @Test
    void customValuesPreserved() {
        var params = new UmapParameters(30, 0.5, 2.0, 500);

        assertEquals(30, params.k());
        assertEquals(0.5, params.minDist(), 0.001);
        assertEquals(2.0, params.spread(), 0.001);
        assertEquals(500, params.epochs());
    }
}
