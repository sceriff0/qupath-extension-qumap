package qupath.ext.qumap.engine;

import javafx.application.Platform;
import qupath.ext.qumap.model.CellIndex;
import qupath.ext.qumap.model.UmapParameters;
import qupath.ext.qumap.model.UmapResult;
import smile.manifold.UMAP;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Background UMAP computation service using SMILE.
 * Supports configurable subsampling, OOM protection, and result caching.
 */
public class UmapComputeService {

    private final ExecutorService executor;
    private volatile UmapResult cachedResult;
    private volatile Future<?> runningTask;

    private volatile Consumer<UmapResult> onComplete;
    private volatile Consumer<String> onError;
    private volatile Consumer<String> onStatusUpdate;

    public UmapComputeService() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "qumap-umap-compute");
            t.setDaemon(true);
            return t;
        });
    }

    public void setOnComplete(Consumer<UmapResult> cb) { this.onComplete = cb; }
    public void setOnError(Consumer<String> cb) { this.onError = cb; }
    public void setOnStatusUpdate(Consumer<String> cb) { this.onStatusUpdate = cb; }

    /**
     * Compute UMAP embedding. Runs in background thread.
     *
     * @param cellIndex   cell data
     * @param params      UMAP parameters
     * @param maxCells    maximum cells before subsampling (0 = no limit)
     */
    public void compute(CellIndex cellIndex, UmapParameters params, int maxCells) {
        cancel();

        runningTask = executor.submit(() -> {
            try {
                int n = cellIndex.size();
                int m = cellIndex.getMarkerNames().length;

                // Memory estimation
                long estimatedBytes = (long) n * m * 8 + (long) n * params.k() * 32 + (long) n * 2 * 8;
                long freeMemory = Runtime.getRuntime().maxMemory()
                        - Runtime.getRuntime().totalMemory()
                        + Runtime.getRuntime().freeMemory();

                // Determine if subsampling is needed
                int computeN = n;
                int[] sampleIndices = null;
                boolean subsampled = false;

                if (maxCells > 0 && n > maxCells) {
                    // Fixed mode: user-specified limit
                    postStatus("Subsampling %,d -> %,d cells...".formatted(n, maxCells));
                    sampleIndices = stratifiedSample(cellIndex, maxCells);
                    computeN = sampleIndices.length;
                    subsampled = true;
                } else if (maxCells < 0 || estimatedBytes > freeMemory * 0.6) {
                    // Auto mode (maxCells == -1) or memory pressure
                    int autoLimit = (int) Math.min(n, Math.max(20000,
                            freeMemory * 0.4 / (m * 8 + params.k() * 32 + 2 * 8)));
                    if (autoLimit < n) {
                        postStatus("Auto-subsampling %,d -> %,d cells (based on available memory)..."
                                .formatted(n, autoLimit));
                        sampleIndices = stratifiedSample(cellIndex, autoLimit);
                        computeN = sampleIndices.length;
                        subsampled = true;
                    }
                }

                // Build matrix
                postStatus("Preparing data matrix (%,d cells x %d markers)...".formatted(computeN, m));
                double[][] matrix;
                if (subsampled) {
                    matrix = extractSubMatrix(cellIndex, sampleIndices);
                } else {
                    matrix = cellIndex.toMatrix();
                }

                // Run UMAP
                postStatus("Computing UMAP (k=%d, epochs=%d)...".formatted(params.k(), params.epochs()));
                var options = new UMAP.Options(params.k(), 2, params.epochs(),
                        1.0, params.minDist(), params.spread(), params.negativeSamples(), 1.0, 1.0);
                double[][] embedding = UMAP.fit(matrix, options);

                // Build result arrays
                double[] umapX = new double[n];
                double[] umapY = new double[n];

                if (subsampled) {
                    // Fill sampled cells
                    for (int i = 0; i < sampleIndices.length; i++) {
                        umapX[sampleIndices[i]] = embedding[i][0];
                        umapY[sampleIndices[i]] = embedding[i][1];
                    }

                    // Project remaining cells via kNN
                    postStatus("Projecting remaining %,d cells...".formatted(n - computeN));
                    projectRemaining(cellIndex, sampleIndices, embedding, umapX, umapY);
                } else {
                    for (int i = 0; i < n; i++) {
                        umapX[i] = embedding[i][0];
                        umapY[i] = embedding[i][1];
                    }
                }

                if (Thread.currentThread().isInterrupted()) return;

                UmapResult result = new UmapResult(umapX, umapY, cellIndex.getObjects(),
                        cellIndex.getMarkerNames(), params);
                cachedResult = result;

                if (Thread.currentThread().isInterrupted()) return;
                Platform.runLater(() -> {
                    if (onComplete != null) onComplete.accept(result);
                });

            } catch (OutOfMemoryError e) {
                // Free memory immediately
                System.gc();
                Platform.runLater(() -> {
                    if (onError != null) {
                        onError.accept("Out of memory. Try enabling subsampling or reducing cell count.\n"
                                + "You can also increase QuPath's memory in Edit > Preferences.");
                    }
                });
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Platform.runLater(() -> {
                        if (onError != null) onError.accept("UMAP failed: " + e.getMessage());
                    });
                }
            }
        });
    }

    /**
     * Stratified random sample preserving phenotype proportions.
     */
    private int[] stratifiedSample(CellIndex cellIndex, int targetN) {
        int n = cellIndex.size();
        if (targetN >= n) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = i;
            return all;
        }

        // Group by PathClass
        var classGroups = new java.util.LinkedHashMap<String, java.util.List<Integer>>();
        for (int i = 0; i < n; i++) {
            var pc = cellIndex.getObject(i).getPathClass();
            String key = pc != null ? pc.getName() : "__unclassified__";
            classGroups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(i);
        }

        Random rng = new Random(42); // reproducible
        int[] sample = new int[targetN];
        int idx = 0;

        // Proportional allocation per class
        for (var entry : classGroups.entrySet()) {
            var indices = entry.getValue();
            int classN = (int) Math.round((double) indices.size() / n * targetN);
            classN = Math.max(1, Math.min(classN, indices.size()));
            classN = Math.min(classN, targetN - idx);

            java.util.Collections.shuffle(indices, rng);
            for (int i = 0; i < classN && idx < targetN; i++) {
                sample[idx++] = indices.get(i);
            }
        }

        // Fill remaining slots randomly
        if (idx < targetN) {
            boolean[] used = new boolean[n];
            for (int i = 0; i < idx; i++) used[sample[i]] = true;
            for (int i = 0; i < n && idx < targetN; i++) {
                if (!used[i]) sample[idx++] = i;
            }
        }

        Arrays.sort(sample, 0, idx);
        return idx == targetN ? sample : Arrays.copyOf(sample, idx);
    }

    private double[][] extractSubMatrix(CellIndex cellIndex, int[] indices) {
        int m = cellIndex.getMarkerNames().length;
        double[][] matrix = new double[indices.length][m];
        for (int j = 0; j < m; j++) {
            double[] markerVals = cellIndex.getMarkerValues(j);

            // Compute mean for NaN replacement
            double sum = 0;
            int count = 0;
            for (int idx : indices) {
                double v = markerVals[idx];
                if (!Double.isNaN(v)) { sum += v; count++; }
            }
            double mean = count > 0 ? sum / count : 0.0;

            for (int i = 0; i < indices.length; i++) {
                double v = markerVals[indices[i]];
                matrix[i][j] = Double.isNaN(v) ? mean : v;
            }
        }
        return matrix;
    }

    /**
     * Project non-sampled cells by finding their k nearest neighbors among the sampled cells
     * and averaging those neighbors' UMAP coordinates weighted by inverse distance.
     */
    private void projectRemaining(CellIndex cellIndex, int[] sampleIndices,
                                  double[][] sampleEmbedding,
                                  double[] umapX, double[] umapY) {
        int n = cellIndex.size();
        int m = cellIndex.getMarkerNames().length;
        int knn = Math.min(5, sampleIndices.length);
        if (knn == 0) return;

        boolean[] isSampled = new boolean[n];
        for (int idx : sampleIndices) isSampled[idx] = true;

        // Pre-fetch all marker arrays once (avoid repeated cloning)
        double[][] allMarkerValues = new double[m][];
        double[] markerMeans = new double[m];
        for (int j = 0; j < m; j++) {
            allMarkerValues[j] = cellIndex.getMarkerValues(j);
            double sum = 0; int cnt = 0;
            for (double v : allMarkerValues[j]) {
                if (!Double.isNaN(v)) { sum += v; cnt++; }
            }
            markerMeans[j] = cnt > 0 ? sum / cnt : 0.0;
        }

        // Build sample marker matrix for kNN lookup (with NaN imputation)
        double[][] sampleMarkers = new double[sampleIndices.length][m];
        for (int j = 0; j < m; j++) {
            for (int s = 0; s < sampleIndices.length; s++) {
                double v = allMarkerValues[j][sampleIndices[s]];
                sampleMarkers[s][j] = Double.isNaN(v) ? markerMeans[j] : v;
            }
        }

        // Pre-compute query vectors for all non-sampled cells (NaN imputed)
        int remaining = 0;
        for (int i = 0; i < n; i++) if (!isSampled[i]) remaining++;

        int[] queryIndices = new int[remaining];
        double[][] queryVectors = new double[remaining][m];
        int qi = 0;
        for (int i = 0; i < n; i++) {
            if (isSampled[i]) continue;
            queryIndices[qi] = i;
            for (int j = 0; j < m; j++) {
                double v = allMarkerValues[j][i];
                queryVectors[qi][j] = Double.isNaN(v) ? markerMeans[j] : v;
            }
            qi++;
        }

        for (int q = 0; q < remaining; q++) {
            if (Thread.currentThread().isInterrupted()) return;

            // Find k nearest neighbors in sample
            double[] dists = new double[knn];
            int[] neighbors = new int[knn];
            Arrays.fill(dists, Double.MAX_VALUE);

            for (int s = 0; s < sampleIndices.length; s++) {
                double dist = 0;
                for (int j = 0; j < m; j++) {
                    double d = queryVectors[q][j] - sampleMarkers[s][j];
                    dist += d * d;
                }

                // Insert if closer than worst
                int worstIdx = 0;
                for (int k = 1; k < knn; k++) {
                    if (dists[k] > dists[worstIdx]) worstIdx = k;
                }
                if (dist < dists[worstIdx]) {
                    dists[worstIdx] = dist;
                    neighbors[worstIdx] = s;
                }
            }

            // Weighted average of neighbor embeddings
            double totalWeight = 0;
            double wx = 0, wy = 0;
            for (int k = 0; k < knn; k++) {
                double w = 1.0 / (Math.sqrt(dists[k]) + 1e-10);
                wx += w * sampleEmbedding[neighbors[k]][0];
                wy += w * sampleEmbedding[neighbors[k]][1];
                totalWeight += w;
            }
            int ci = queryIndices[q];
            umapX[ci] = wx / totalWeight;
            umapY[ci] = wy / totalWeight;

            // Progress update every 10%
            if (q % (Math.max(1, remaining / 10)) == 0) {
                int pct = (int) ((double) q / remaining * 100);
                postStatus("Projecting remaining cells... %d%%".formatted(pct));
            }
        }
    }

    private void postStatus(String msg) {
        if (onStatusUpdate != null) {
            Platform.runLater(() -> onStatusUpdate.accept(msg));
        }
    }

    public void cancel() {
        if (runningTask != null && !runningTask.isDone()) {
            runningTask.cancel(true);
        }
    }

    public UmapResult getCachedResult() { return cachedResult; }

    public void shutdown() {
        cancel();
        executor.shutdownNow();
        onComplete = null;
        onError = null;
        onStatusUpdate = null;
    }
}
