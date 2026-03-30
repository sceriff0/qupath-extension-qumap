package qupath.ext.qumap.model;

public record UmapParameters(int k, double minDist, double spread, int epochs, int negativeSamples) {

    public UmapParameters {
        if (k <= 0) throw new IllegalArgumentException("k must be > 0, got: " + k);
        if (minDist <= 0) throw new IllegalArgumentException("minDist must be > 0, got: " + minDist);
        if (spread <= 0) throw new IllegalArgumentException("spread must be > 0, got: " + spread);
        if (minDist > spread) throw new IllegalArgumentException("minDist must be <= spread, got minDist=" + minDist + " spread=" + spread);
        if (epochs <= 0) throw new IllegalArgumentException("epochs must be > 0, got: " + epochs);
        if (negativeSamples <= 0) throw new IllegalArgumentException("negativeSamples must be > 0, got: " + negativeSamples);
    }

    /** Backward-compatible constructor defaulting negativeSamples to 5. */
    public UmapParameters(int k, double minDist, double spread, int epochs) {
        this(k, minDist, spread, epochs, 5);
    }

    public static UmapParameters defaults() {
        return new UmapParameters(15, 0.1, 1.0, 100, 5);
    }
}
