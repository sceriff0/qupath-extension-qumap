# Architecture: FlowPath - qUMAP

## Overview

qUMAP provides UMAP dimensionality reduction visualization for QuPath. It computes a 2D embedding from high-dimensional marker data, renders it as an interactive scatter plot, and supports polygon-based population selection with phenotype-aware coloring.

## Layers

```
QuMapExtension (entry point)
    |
QuMapPane (UI orchestrator)
    |
    +-- UmapComputeService (engine)     -- background UMAP computation
    |       |
    |       +-- CellIndex              -- columnar marker data
    |       +-- MarkerStats            -- z-score normalization
    |       +-- SMILE UMAP            -- embedding algorithm
    |
    +-- UmapCanvas                     -- primary scatter plot
    +-- MarkerOverlayCanvas            -- expression heatmap
    +-- PolygonSelector                -- interactive polygon drawing
    +-- PhenotypeLegend                -- discrete color legend
    +-- ColorScaleLegend               -- continuous gradient legend
```

## Design Decisions

### 1. Columnar Data Layout (CellIndex)

Same pattern as GatingTree. Marker values stored as `double[markerIndex][cellIndex]` for cache-friendly sequential scans during UMAP matrix construction.

**Why:** UMAP input requires a `double[cells][markers]` matrix. Building this from per-cell objects would require N random accesses per marker. The columnar layout allows a single sequential scan per marker, with NaN imputation computed in-line.

**Key method:** `CellIndex.toMatrix()` transposes `[marker][cell]` to `[cell][marker]` while replacing NaN with column means — this is the bridge between QuPath's data model and SMILE's UMAP API.

### 2. Background Computation with OOM Protection

UMAP is computationally expensive (O(n * k * d) for kNN graph, O(n * epochs) for SGD optimization). It must not block the JavaFX UI thread.

**Approach:**
- Single daemon `ExecutorService` in `UmapComputeService`
- Data is copied before handoff (CellIndex is immutable after construction)
- Results delivered via `Platform.runLater()` callback
- `OutOfMemoryError` is caught, matrix is nulled, GC requested, UI returns to idle state

**Memory estimation:**
```
estimated_bytes = cells * markers * 8      (input matrix)
                + cells * k * 32           (kNN graph + fuzzy set)
                + cells * 2 * 8            (output embedding)
```

If estimated usage exceeds 60% of free heap, a warning dialog is shown before computation.

### 3. Stratified Subsampling

For large datasets (>50K cells by default), random subsampling preserves phenotype proportions by allocating sample slots proportionally to each PathClass group.

**Projection:** Non-sampled cells get approximate UMAP coordinates via weighted k-nearest-neighbor interpolation in marker space. This is O(n * sample_size * markers) — slower than the UMAP itself for very large datasets, but avoids the O(n^2) kNN graph construction that makes full UMAP infeasible.

**Trade-off:** Approximate embeddings for projected cells vs. full computation. For visualization purposes, the approximation is sufficient — local structure is preserved for sampled cells, and projected cells land near their marker-space neighbors.

### 4. Per-Point Color Arrays

Unlike GatingTree's ScatterPlotCanvas (which uses binary inside/outside coloring), UmapCanvas stores a packed `int[]` color per point. This supports:
- Phenotype coloring (each cell's PathClass color)
- UNFOCUSED graying (outside polygon)
- Population ring overlays (additional stroke per tagged cell)

**Trade-off:** 4 bytes per cell for colors vs. computing color on-the-fly during rendering. Given that rendering already subsamples to 30K points, the memory cost is negligible and avoids repeated PathClass lookups during paint.

### 5. Derived PathClass for Population Tags

QuPath supports hierarchical classifications: `PathClass.fromString("CD4+: Cluster A")` creates a derived class with "CD4+" as the base. This preserves the original phenotype in the class name while adding the population tag.

**Visual representation:**
- In QuPath viewer: cells show the derived class color
- In UMAP canvas: cells show phenotype fill color + population ring stroke
- This dual rendering ensures both classification levels are visible

**Removability:** Tags can be removed by stripping the `: TagName` suffix and restoring the base PathClass.

## Dependencies

- **SMILE 3.1.1** (`smile-core`) — UMAP implementation. Native BLAS/LAPACK libraries are excluded from the shadow JAR to reduce size from ~70MB to ~1.6MB. SMILE falls back to pure Java matrix operations, which is adequate for UMAP's workload (no large matrix decompositions).
