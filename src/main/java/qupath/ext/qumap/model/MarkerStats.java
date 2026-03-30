package qupath.ext.qumap.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Precomputed statistics for each marker channel.
 * Used for z-score normalization and color mapping.
 */
public class MarkerStats {

    private final Map<String, Double> means;
    private final Map<String, Double> stds;
    private final Map<String, Double> mins;
    private final Map<String, Double> maxs;

    private MarkerStats(Map<String, Double> means, Map<String, Double> stds,
                        Map<String, Double> mins, Map<String, Double> maxs) {
        this.means = means;
        this.stds = stds;
        this.mins = mins;
        this.maxs = maxs;
    }

    /**
     * Compute statistics for all markers using all cells (no quality mask).
     */
    public static MarkerStats compute(CellIndex index) {
        String[] markers = index.getMarkerNames();
        int n = index.size();

        Map<String, Double> means = new HashMap<>();
        Map<String, Double> stds = new HashMap<>();
        Map<String, Double> mins = new HashMap<>();
        Map<String, Double> maxs = new HashMap<>();

        for (int m = 0; m < markers.length; m++) {
            String name = markers[m];
            double[] raw = index.getMarkerValues(m);

            if (n == 0) {
                means.put(name, 0.0);
                stds.put(name, 0.0);
                mins.put(name, 0.0);
                maxs.put(name, 0.0);
                continue;
            }

            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            double sum = 0;
            int count = 0;
            for (int i = 0; i < n; i++) {
                double v = raw[i];
                if (!Double.isFinite(v)) continue;
                sum += v;
                count++;
                if (v < min) min = v;
                if (v > max) max = v;
            }
            if (count == 0) { min = 0; max = 0; }
            double mean = count > 0 ? sum / count : 0.0;

            double sumSq = 0;
            for (int i = 0; i < n; i++) {
                double v = raw[i];
                if (!Double.isFinite(v)) continue;
                double d = v - mean;
                sumSq += d * d;
            }
            double std = count > 1 ? Math.sqrt(sumSq / (count - 1)) : 0.0;

            means.put(name, mean);
            stds.put(name, std);
            mins.put(name, min);
            maxs.put(name, max);
        }

        return new MarkerStats(means, stds, mins, maxs);
    }

    public double toZScore(String channel, double rawValue) {
        double mean = means.getOrDefault(channel, 0.0);
        double std = stds.getOrDefault(channel, 0.0);
        if (std < 1e-10) return 0.0;
        return (rawValue - mean) / std;
    }

    public double getMean(String channel) { return means.getOrDefault(channel, 0.0); }
    public double getStd(String channel) { return stds.getOrDefault(channel, 0.0); }
    public double getMin(String channel) { return mins.getOrDefault(channel, 0.0); }
    public double getMax(String channel) { return maxs.getOrDefault(channel, 0.0); }
    public Set<String> getMarkerNames() { return Collections.unmodifiableSet(means.keySet()); }
}
