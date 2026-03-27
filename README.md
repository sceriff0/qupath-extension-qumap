# QuPath Extension: FlowPath - qUMAP

[![QuPath](https://img.shields.io/badge/QuPath-%E2%89%A50.7.0-blue.svg)](https://qupath.github.io/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://jdk.java.net/25/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

UMAP dimensionality reduction and visualization for [QuPath](https://qupath.github.io/). Explore cell populations in embedding space, color by phenotype, select subsets with interactive polygons, and overlay marker expression — all on multiplexed imaging data (CODEX, MIBI, mIF).

Part of the [FlowPath extension suite](https://github.com/sceriff0/flowpath-catalog). Works alongside [FlowPath - GatingTree](https://github.com/sceriff0/qupath-extension-flowpath) for a complete phenotyping workflow.

<!-- Add screenshots here -->

## Features

- **UMAP embedding** — Compute 2D UMAP from all available markers using [SMILE](https://haifengl.github.io/)
- **Phenotype coloring** — Cells colored by their PathClass (e.g., from GatingTree), with a sortable legend showing names and counts
- **Interactive polygon gating** — Draw polygons in UMAP space to focus on cell subsets
  - Cells outside the polygon are temporarily marked as **UNFOCUSED** (gray)
  - **Tag populations** with a name and color — permanently stored as QuPath derived PathClass (e.g., "CD4+: Cluster A")
  - Multiple population tags coexist with colored ring overlays
- **Marker expression overlay** — Side-by-side UMAP colored by z-score (blue-white-red) or raw intensity (viridis)
- **Configurable subsampling** — Auto/Off/Fixed modes with stratified sampling preserving phenotype proportions
- **OOM protection** — Pre-computation memory estimation, graceful recovery from out-of-memory
- **Zoom / Pan** — Scroll wheel zoom, middle-click drag pan, double-click reset
- **Export** — UMAP coordinates and phenotypes to CSV

## Installation

### From QuPath Extension Manager

1. Open QuPath → `Extensions` → `Manage extensions` → `Manage extension catalogs` → `Add`
2. Paste: `https://raw.githubusercontent.com/sceriff0/flowpath-catalog/main/catalog.json`
3. Go back to `Manage extensions` → find **FlowPath - qUMAP** → click `+` to install
4. Restart QuPath

### From Release JAR

Download the latest JAR from [Releases](../../releases) and drag it onto QuPath.

### Build from Source

```bash
git clone https://github.com/sceriff0/qupath-extension-qumap.git
cd qupath-extension-qumap
./gradlew build
# JAR at build/libs/FlowPath.-.qUMAP-0.1.0-all.jar -> drag onto QuPath
```

## Quick Start

1. Open your pyramidal OME-TIFF in QuPath
2. Import cell detections with marker intensities (e.g., from [mirage](https://github.com/sceriff0/mirage) or any segmentation pipeline)
3. `Extensions` > `FlowPath - qUMAP` (or `Ctrl+U`)
4. Adjust UMAP parameters if needed (k, epochs) and subsampling mode for large datasets
5. Click **Compute UMAP** — wait for the embedding to appear
6. Cells are automatically colored by phenotype if classified (e.g., from GatingTree)
7. Select a marker from the dropdown to see expression overlay side-by-side
8. Click **Draw Polygon** > click to add vertices > double-click to close
9. Cells outside turn gray (UNFOCUSED). Enter a name + pick a color > **Apply Tag** to permanently mark the selection
10. **Export CSV** to save UMAP coordinates with phenotype labels

## Output Formats

**UMAP CSV** (`umap_coordinates.csv`) — One row per cell:

```csv
UMAP_X,UMAP_Y,Phenotype
-3.241519,1.872034,CD4+
2.109384,-0.543218,CD8+
0.012345,4.321098,Unclassified
```

## Performance

| Cell Count | Strategy | Expected Time |
|-----------|----------|---------------|
| < 10K | Direct computation | 2-5 seconds |
| 10K-50K | Direct with progress indicator | 10-30 seconds |
| 50K-100K | Auto-subsampling recommended | 5-15 seconds |
| > 100K | Subsampling + kNN projection | 10-30 seconds |

The extension estimates memory usage before computation and warns if the dataset may cause out-of-memory issues. Subsampling uses stratified random sampling to preserve phenotype proportions, then projects remaining cells via weighted k-nearest-neighbor interpolation.

## Acknowledgments

This extension uses the following open-source libraries:

- **[SMILE](https://haifengl.github.io/)** (Statistical Machine Intelligence and Learning Engine) — UMAP implementation
  > Haifeng Li. 2014. SMILE -- Statistical Machine Intelligence and Learning Engine. https://haifengl.github.io/

- **[QuPath](https://qupath.github.io/)** — Open-source bioimage analysis platform
  > Bankhead, P. et al. (2017). QuPath: Open source software for digital pathology image analysis. *Scientific Reports*, 7, 16878. https://doi.org/10.1038/s41598-017-17204-5

The UMAP algorithm:
> McInnes, L., Healy, J., & Melville, J. (2018). UMAP: Uniform Manifold Approximation and Projection for Dimension Reduction. *arXiv:1802.03426*. https://arxiv.org/abs/1802.03426

## Citation

If you use this tool in your research, please cite:

> FlowPath - qUMAP: UMAP dimensionality reduction and visualization for QuPath. (2026). https://github.com/sceriff0/qupath-extension-qumap

## License

MIT License. See [LICENSE](LICENSE).
