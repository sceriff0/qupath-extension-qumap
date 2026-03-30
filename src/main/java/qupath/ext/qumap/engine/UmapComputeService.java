package qupath.ext.qumap.engine;

import javafx.application.Platform;
import qupath.ext.qumap.model.CellIndex;
import qupath.ext.qumap.model.UmapParameters;
import qupath.ext.qumap.model.UmapResult;
import smile.manifold.UMAP;
import smile.graph.NearestNeighborGraph;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Background UMAP computation service using SMILE.
 * Supports configurable subsampling, OOM protection, and result caching.
 */
public class UmapComputeService {

    private final ExecutorService executor;
    private volatile UmapResult cachedResult;
    private volatile Future<?> runningTask;
    private volatile boolean cancelled;
    private final AtomicInteger generation = new AtomicInteger(0);

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
        cancelled = false;
        final int myGeneration = generation.incrementAndGet();

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
                    int autoLimit = (int) Math.min(n, Math.max(10000,
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
                double[] imputationMeans = null;
                if (subsampled) {
                    imputationMeans = new double[m];
                    matrix = extractSubMatrix(cellIndex, sampleIndices, imputationMeans);
                } else {
                    matrix = cellIndex.toMatrix();
                }

                // Clamp k to dataset size (NN-descent requires k < n)
                int effectiveK = Math.min(params.k(), computeN - 1);
                if (effectiveK < 2) {
                    Platform.runLater(() -> {
                        if (onError != null) onError.accept(
                                "Too few cells (%d) for UMAP. Need at least %d.".formatted(computeN, params.k() + 1));
                    });
                    return;
                }

                // Build kNN graph (always use approximate NN-descent for speed)
                postStatus("Building neighbor graph (k=%d)...".formatted(effectiveK));
                NearestNeighborGraph nng = NearestNeighborGraph.descent(matrix, effectiveK);

                if (cancelled) return;

                // Run UMAP with pre-computed graph
                postStatus("Optimizing layout (epochs=%d)...".formatted(params.epochs()));
                var options = new UMAP.Options(effectiveK, 2, params.epochs(),
                        1.0, params.minDist(), params.spread(), params.negativeSamples(), 1.0, 1.0);
                double[][] embedding = UMAP.fit(matrix, nng, options);

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
                    projectRemaining(cellIndex, sampleIndices, embedding, umapX, umapY, imputationMeans);
                } else {
                    for (int i = 0; i < n; i++) {
                        umapX[i] = embedding[i][0];
                        umapY[i] = embedding[i][1];
                    }
                }

                if (cancelled || generation.get() != myGeneration) return;

                UmapResult result = new UmapResult(umapX, umapY, cellIndex.getObjects(),
                        cellIndex.getMarkerNames(), params);
                cachedResult = result;

                if (cancelled || generation.get() != myGeneration) return;
                Platform.runLater(() -> {
                    if (generation.get() == myGeneration && onComplete != null)
                        onComplete.accept(result);
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
                if (!cancelled && generation.get() == myGeneration) {
                    Platform.runLater(() -> {
                        if (generation.get() == myGeneration && onError != null)
                            onError.accept("UMAP failed: " + e.getMessage());
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

        // Fill remaining slots randomly (using seeded RNG for reproducibility)
        if (idx < targetN) {
            boolean[] used = new boolean[n];
            for (int i = 0; i < idx; i++) used[sample[i]] = true;
            // Collect unused indices and shuffle with the seeded RNG
            var unused = new java.util.ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                if (!used[i]) unused.add(i);
            }
            java.util.Collections.shuffle(unused, rng);
            for (int i = 0; i < unused.size() && idx < targetN; i++) {
                sample[idx++] = unused.get(i);
            }
        }

        Arrays.sort(sample, 0, idx);
        return idx == targetN ? sample : Arrays.copyOf(sample, idx);
    }

    /**
     * Extract sub-matrix for sampled cells, returning imputation means for reuse.
     */
    private double[][] extractSubMatrix(CellIndex cellIndex, int[] indices, double[] imputationMeans) {
        int m = cellIndex.getMarkerNames().length;
        double[][] matrix = new double[indices.length][m];
        for (int j = 0; j < m; j++) {
            double[] markerVals = cellIndex.getMarkerValues(j);

            // Compute mean for NaN replacement from sampled cells only
            double sum = 0;
            int count = 0;
            for (int idx : indices) {
                double v = markerVals[idx];
                if (!Double.isNaN(v)) { sum += v; count++; }
            }
            double mean = count > 0 ? sum / count : 0.0;
            imputationMeans[j] = mean;

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
                                  double[] umapX, double[] umapY,
                                  double[] imputationMeans) {
        int n = cellIndex.size();
        int m = cellIndex.getMarkerNames().length;
        int knn = Math.min(5, sampleIndices.length);
        if (knn == 0) return;

        boolean[] isSampled = new boolean[n];
        for (int idx : sampleIndices) isSampled[idx] = true;

        // Pre-fetch all marker arrays once (avoid repeated cloning)
        // Use the same imputation means from extractSubMatrix for consistency
        double[][] allMarkerValues = new double[m][];
        for (int j = 0; j < m; j++) {
            allMarkerValues[j] = cellIndex.getMarkerValues(j);
        }

        // Build sample marker matrix for kNN lookup (with NaN imputation)
        double[][] sampleMarkers = new double[sampleIndices.length][m];
        for (int j = 0; j < m; j++) {
            for (int s = 0; s < sampleIndices.length; s++) {
                double v = allMarkerValues[j][sampleIndices[s]];
                sampleMarkers[s][j] = Double.isNaN(v) ? imputationMeans[j] : v;
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
                queryVectors[qi][j] = Double.isNaN(v) ? imputationMeans[j] : v;
            }
            qi++;
        }

        // Parallel kNN projection — each query is independent
        final int totalRemaining = remaining;
        final int progressStep = Math.max(1, remaining / 10);
        AtomicInteger progressCount = new AtomicInteger(0);

        IntStream.range(0, remaining).parallel().forEach(q -> {
            if (cancelled) return;

            // Find k nearest neighbors in sample
            double[] dists = new double[knn];
            int[] neighbors = new int[knn];
            Arrays.fill(dists, Double.MAX_VALUE);

            for (int s = 0; s < sampleIndices.length; s++) {
                if (s % 256 == 0 && cancelled) return;
                double dist = 0;
                for (int j = 0; j < m; j++) {
                    double d = queryVectors[q][j] - sampleMarkers[s][j];
                    dist += d * d;
                }

                // Insert if closer than worst
                int worstIdx = 0;
                for (int ki = 1; ki < knn; ki++) {
                    if (dists[ki] > dists[worstIdx]) worstIdx = ki;
                }
                if (dist < dists[worstIdx]) {
                    dists[worstIdx] = dist;
                    neighbors[worstIdx] = s;
                }
            }

            // Weighted average of neighbor embeddings
            double totalWeight = 0;
            double wx = 0, wy = 0;
            for (int ki = 0; ki < knn; ki++) {
                double w = 1.0 / (Math.sqrt(dists[ki]) + 1e-10);
                wx += w * sampleEmbedding[neighbors[ki]][0];
                wy += w * sampleEmbedding[neighbors[ki]][1];
                totalWeight += w;
            }
            int ci = queryIndices[q];
            umapX[ci] = wx / totalWeight;
            umapY[ci] = wy / totalWeight;

            // Progress update every ~10%
            int done = progressCount.incrementAndGet();
            if (done % progressStep == 0) {
                int pct = (int) ((double) done / totalRemaining * 100);
                postStatus("Projecting remaining cells... %d%%".formatted(pct));
            }
        });
    }

    private void postStatus(String msg) {
        if (onStatusUpdate != null) {
            Platform.runLater(() -> onStatusUpdate.accept(msg));
        }
    }

    public void cancel() {
        cancelled = true;
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
