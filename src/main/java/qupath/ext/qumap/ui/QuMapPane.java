package qupath.ext.qumap.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import qupath.ext.qumap.engine.UmapComputeService;
import qupath.ext.qumap.model.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.io.File;
import java.util.*;

/**
 * Main panel for the qUMAP extension.
 * Orchestrates UMAP computation, visualization, polygon gating, and marker overlays.
 */
public class QuMapPane extends BorderPane {

    private final QuPathGUI qupath;

    // Data
    private CellIndex cellIndex;
    private MarkerStats markerStats;
    private UmapResult umapResult;
    private PathClass[] originalClasses;      // backup for UNFOCUSED restore
    private final List<PopulationTag> populationTags = new ArrayList<>();

    // Engine
    private final UmapComputeService computeService;

    // UI components
    private final UmapCanvas umapCanvas;
    private final MarkerOverlayCanvas markerOverlay;
    private final PhenotypeLegend legend;
    private final ColorScaleLegend colorScaleLegend;
    private final PolygonSelector polygonSelector;
    private final Label statusLabel;
    private final ProgressIndicator progressIndicator;

    // Controls
    private final ComboBox<String> qualityPreset;
    private final Spinner<Integer> kSpinner;
    private final Spinner<Integer> epochsSpinner;
    private final Spinner<Double> dotSizeSpinner;
    private final Spinner<Integer> maxCellsSpinner;
    private final ComboBox<String> subsampleMode;
    private final ComboBox<String> markerDropdown;
    private final ComboBox<String> colorScaleDropdown;
    private final TextField tagNameField;
    private final ColorPicker tagColorPicker;
    private final Button computeButton;
    private final Button cancelButton;
    private final ToggleButton drawButton;
    private final Button clearButton;
    private final Button applyTagButton;
    private final Button exportButton;

    // Current preset negative samples (not exposed as spinner — controlled by preset)
    private int negativeSamples = 3;
    private javafx.beans.value.ChangeListener<ImageData<?>> imageDataListener;
    private long computeStartTime;

    // Marker overlay visibility
    private final SplitPane centerSplit;
    private boolean markerOverlayVisible = false;

    public QuMapPane(QuPathGUI qupath) {
        this.qupath = qupath;

        // --- Initialize components ---
        computeService = new UmapComputeService();
        umapCanvas = new UmapCanvas();
        markerOverlay = new MarkerOverlayCanvas();
        legend = new PhenotypeLegend();
        colorScaleLegend = new ColorScaleLegend();
        polygonSelector = new PolygonSelector(umapCanvas);
        statusLabel = new Label("Load an image with cell detections");
        progressIndicator = new ProgressIndicator();
        progressIndicator.setMaxSize(16, 16);
        progressIndicator.setVisible(false);

        // --- Controls ---
        qualityPreset = new ComboBox<>(FXCollections.observableArrayList(
                "Fast", "Balanced", "Quality", "Custom"));
        qualityPreset.setValue("Fast");
        qualityPreset.setPrefWidth(90);
        qualityPreset.setTooltip(new Tooltip(
                "Fast: quick preview (k=10, 50 epochs)\n" +
                "Balanced: good quality (k=15, 100 epochs)\n" +
                "Quality: publication-ready (k=15, 200 epochs)"));
        qualityPreset.setOnAction(e -> applyPreset(qualityPreset.getValue()));

        kSpinner = new Spinner<>(5, 50, 10, 5);
        kSpinner.setPrefWidth(70);
        kSpinner.setEditable(true);
        kSpinner.setTooltip(new Tooltip(
                "Number of nearest neighbors (k).\n" +
                "Lower = faster, more local detail.\n" +
                "Higher = slower, more global structure."));
        kSpinner.valueProperty().addListener((obs, o, n) -> {
            if (!applyingPreset) qualityPreset.setValue("Custom");
        });

        epochsSpinner = new Spinner<>(50, 1000, 50, 50);
        epochsSpinner.setPrefWidth(80);
        epochsSpinner.setEditable(true);
        epochsSpinner.setTooltip(new Tooltip(
                "Optimization iterations.\n" +
                "More = better embedding, slower.\n" +
                "50-100 is usually enough for exploration."));
        epochsSpinner.valueProperty().addListener((obs, o, n) -> {
            if (!applyingPreset) qualityPreset.setValue("Custom");
        });

        dotSizeSpinner = new Spinner<>(1.0, 5.0, 2.0, 0.5);
        dotSizeSpinner.setPrefWidth(65);
        dotSizeSpinner.setEditable(true);
        dotSizeSpinner.setTooltip(new Tooltip("Size of each cell dot in the plot."));
        dotSizeSpinner.valueProperty().addListener((obs, o, n) -> {
            umapCanvas.setDotSize(n);
            markerOverlay.setDotSize(n);
        });

        maxCellsSpinner = new Spinner<>(10000, 200000, 50000, 10000);
        maxCellsSpinner.setPrefWidth(90);
        maxCellsSpinner.setEditable(true);
        maxCellsSpinner.setTooltip(new Tooltip(
                "Maximum cells before subsampling kicks in.\n" +
                "Lower = faster but less complete."));

        subsampleMode = new ComboBox<>(FXCollections.observableArrayList("Auto", "Off", "Fixed"));
        subsampleMode.setValue("Auto");
        subsampleMode.setPrefWidth(70);
        subsampleMode.setTooltip(new Tooltip(
                "Auto: subsample based on available memory.\n" +
                "Off: use all cells (may be slow/OOM).\n" +
                "Fixed: subsample to the Max value."));

        markerDropdown = new ComboBox<>();
        markerDropdown.setPromptText("-- none --");
        markerDropdown.setPrefWidth(120);
        markerDropdown.setTooltip(new Tooltip("Select a marker to color cells by expression level."));
        markerDropdown.setOnAction(e -> onMarkerSelected());

        colorScaleDropdown = new ComboBox<>(FXCollections.observableArrayList("Z-score", "Raw"));
        colorScaleDropdown.setValue("Z-score");
        colorScaleDropdown.setPrefWidth(75);
        colorScaleDropdown.setTooltip(new Tooltip(
                "Z-score: normalized (blue=low, red=high).\n" +
                "Raw: actual measurement values."));
        colorScaleDropdown.setOnAction(e -> onMarkerSelected());

        computeButton = new Button("Compute UMAP");
        computeButton.setTooltip(new Tooltip("Run UMAP dimensionality reduction on cell data."));
        computeButton.setOnAction(e -> runUmap());

        cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> cancelUmap());
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);

        drawButton = new ToggleButton("Draw Polygon");
        drawButton.setTooltip(new Tooltip("Click to draw a polygon gate on the UMAP plot.\nClick points to create vertices, close the shape to finish."));
        drawButton.setOnAction(e -> {
            if (drawButton.isSelected()) {
                polygonSelector.activate();
            } else {
                polygonSelector.deactivate();
            }
        });

        clearButton = new Button("Clear Shape");
        clearButton.setTooltip(new Tooltip("Remove polygon gate and restore all cell classes. (Esc)"));
        clearButton.setOnAction(e -> clearPolygon());

        tagNameField = new TextField();
        tagNameField.setPromptText("Population name");
        tagNameField.setPrefWidth(100);

        tagColorPicker = new ColorPicker(Color.ORANGE);
        tagColorPicker.setPrefWidth(50);

        applyTagButton = new Button("Apply Tag");
        applyTagButton.setTooltip(new Tooltip("Label gated cells with the population name.\nCells are tagged in QuPath's hierarchy."));
        applyTagButton.setOnAction(e -> applyPopulationTag());

        exportButton = new Button("Export CSV");
        exportButton.setTooltip(new Tooltip("Export UMAP coordinates and marker data to CSV. (Ctrl+E)"));
        exportButton.setOnAction(e -> exportCsv());

        // Disable gating/tag/export controls until UMAP is computed
        drawButton.setDisable(true);
        clearButton.setDisable(true);
        tagNameField.setDisable(true);
        tagColorPicker.setDisable(true);
        applyTagButton.setDisable(true);
        exportButton.setDisable(true);

        // --- Layout ---

        // Toolbar row 1
        var row1 = new HBox(6,
                computeButton, cancelButton, progressIndicator,
                qualityPreset,
                new Label("k:"), kSpinner,
                new Label("Epochs:"), epochsSpinner,
                new Label("Dot:"), dotSizeSpinner,
                new Separator(Orientation.VERTICAL),
                new Label("Subsample:"), subsampleMode,
                new Label("Max:"), maxCellsSpinner
        );
        row1.setPadding(new Insets(4));

        // Toolbar row 2
        var row2 = new HBox(6,
                new Label("Marker:"), markerDropdown, colorScaleDropdown,
                new Separator(Orientation.VERTICAL),
                drawButton, clearButton,
                new Separator(Orientation.VERTICAL),
                new Label("Tag:"), tagNameField, tagColorPicker, applyTagButton,
                new Separator(Orientation.VERTICAL),
                exportButton
        );
        row2.setPadding(new Insets(4));

        var toolbar = new VBox(row1, row2);
        toolbar.setStyle("-fx-background-color: #333;");
        setTop(toolbar);

        // Center: UMAP canvas + optional marker overlay + legend
        var legendBox = new VBox(legend, colorScaleLegend);
        VBox.setVgrow(legend, Priority.ALWAYS);

        centerSplit = new SplitPane(umapCanvas, legendBox);
        centerSplit.setDividerPositions(0.85);
        setCenter(centerSplit);

        // Status bar
        var statusBar = new HBox(8, statusLabel);
        statusBar.setPadding(new Insets(3, 6, 3, 6));
        statusBar.setStyle("-fx-background-color: #2a2a2a;");
        setBottom(statusBar);

        // --- Callbacks ---
        computeService.setOnComplete(this::onUmapComplete);
        computeService.setOnError(this::onUmapError);
        computeService.setOnStatusUpdate(s -> statusLabel.setText(s));

        polygonSelector.setOnPolygonComplete(this::onPolygonComplete);

        // Sync marker overlay zoom/pan with main canvas
        umapCanvas.setOnViewChanged(() -> {
            if (markerOverlayVisible) {
                markerOverlay.syncView(
                        umapCanvas.getViewMinX(), umapCanvas.getViewMaxX(),
                        umapCanvas.getViewMinY(), umapCanvas.getViewMaxY(),
                        umapCanvas.isViewOverride());
            }
        });

        legend.setOnPopulationRemove(this::removePopulationTag);

        // --- Listen for image changes ---
        imageDataListener = (obs, oldImg, newImg) -> Platform.runLater(this::initializeFromImage);
        qupath.imageDataProperty().addListener(imageDataListener);

        // --- Keyboard shortcuts ---
        setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE -> {
                    if (polygonSelector.isActive()) {
                        polygonSelector.deactivate();
                        drawButton.setSelected(false);
                    } else if (originalClasses != null) {
                        clearPolygon();
                    }
                }
                case E -> {
                    if (e.isControlDown()) exportCsv();
                }
                default -> {}
            }
        });

        // Initialize if image already loaded
        Platform.runLater(this::initializeFromImage);
    }

    // --- Initialization ---

    private void initializeFromImage() {
        computeService.cancel();
        umapResult = null;
        cellIndex = null;
        markerStats = null;
        originalClasses = null;
        populationTags.clear();
        umapCanvas.setData(null, null);
        markerOverlay.setData(null, null);
        legend.update(null, null);
        colorScaleLegend.clear();

        // Disable gating/export controls until UMAP is recomputed
        computeButton.setDisable(true);
        drawButton.setDisable(true);
        clearButton.setDisable(true);
        tagNameField.setDisable(true);
        tagColorPicker.setDisable(true);
        applyTagButton.setDisable(true);
        exportButton.setDisable(true);
        drawButton.setSelected(false);
        progressIndicator.setVisible(false);

        ImageData<?> imageData = qupath.getImageData();
        if (imageData == null) {
            statusLabel.setText("No image loaded");
            return;
        }

        var detections = imageData.getHierarchy().getDetectionObjects()
                .stream()
                .filter(d -> {
                    var pc = d.getPathClass();
                    return pc == null || !"Excluded".equals(pc.getName());
                })
                .toList();
        if (detections.isEmpty()) {
            statusLabel.setText("No cell detections found");
            return;
        }

        // Discover marker names
        List<String> markers = discoverMarkerNames(imageData, detections);
        if (markers.isEmpty()) {
            statusLabel.setText("No markers found in measurements");
            return;
        }

        statusLabel.setText("Building cell index...");

        // Build CellIndex and MarkerStats off the FX thread
        var detectionsCopy = new ArrayList<>(detections);
        var markersCopy = List.copyOf(markers);
        Thread bgThread = new Thread(() -> {
            CellIndex builtIndex = CellIndex.build(detectionsCopy, markersCopy);
            MarkerStats builtStats = MarkerStats.compute(builtIndex);
            Platform.runLater(() -> {
                cellIndex = builtIndex;
                markerStats = builtStats;
                computeButton.setDisable(false);

                markerDropdown.getItems().clear();
                markerDropdown.getItems().add("-- none --");
                markerDropdown.getItems().addAll(markersCopy);
                markerDropdown.setValue("-- none --");

                statusLabel.setText(String.format("%,d cells, %d markers. Ready to compute UMAP.",
                        builtIndex.size(), markersCopy.size()));
            });
        }, "qumap-init");
        bgThread.setDaemon(true);
        bgThread.start();
    }

    private List<String> discoverMarkerNames(ImageData<?> imageData, Collection<PathObject> detections) {
        // Primary: from image metadata channels
        var channels = imageData.getServer().getMetadata().getChannels();
        List<String> candidates = new ArrayList<>();
        for (var ch : channels) {
            candidates.add(ch.getName());
        }

        // Validate against actual measurements (sample up to 20 cells to avoid outlier bias)
        if (!candidates.isEmpty() && !detections.isEmpty()) {
            Set<String> allKeys = new HashSet<>();
            int sampled = 0;
            for (PathObject obj : detections) {
                var measurements = obj.getMeasurements();
                if (measurements != null) {
                    allKeys.addAll(measurements.keySet());
                }
                if (++sampled >= 20) break;
            }
            candidates.removeIf(name -> {
                if (allKeys.contains(name)) return false;
                // Check layer-prefixed
                for (String key : allKeys) {
                    if (key.endsWith("] " + name)) return false;
                }
                return true;
            });
        }

        // Fallback: from measurements directly (sample up to 20 cells)
        if (candidates.isEmpty() && !detections.isEmpty()) {
            Set<String> exclude = Set.of(
                    "Centroid X", "Centroid Y", "Centroid X µm", "Centroid Y µm",
                    "area", "area µm²", "eccentricity", "perimeter", "convex_area",
                    "axis_major_length", "axis_minor_length", "solidity",
                    "x", "y", "label", "fov", "cell_size"
            );
            Set<String> allKeys = new LinkedHashSet<>();
            int sampled = 0;
            for (PathObject obj : detections) {
                var measurements = obj.getMeasurements();
                if (measurements != null) {
                    allKeys.addAll(measurements.keySet());
                }
                if (++sampled >= 20) break;
            }
            for (String key : allKeys) {
                boolean skip = false;
                for (String ex : exclude) {
                    if (key.equalsIgnoreCase(ex) || key.startsWith(ex)) { skip = true; break; }
                }
                if (!skip) candidates.add(key);
            }
        }

        return candidates;
    }

    // --- UMAP Presets ---

    private boolean applyingPreset = false;

    private void applyPreset(String preset) {
        if (applyingPreset || "Custom".equals(preset)) return;
        applyingPreset = true;
        try {
            switch (preset) {
                case "Fast" -> {
                    kSpinner.getValueFactory().setValue(10);
                    epochsSpinner.getValueFactory().setValue(50);
                    negativeSamples = 3;
                }
                case "Balanced" -> {
                    kSpinner.getValueFactory().setValue(15);
                    epochsSpinner.getValueFactory().setValue(100);
                    negativeSamples = 5;
                }
                case "Quality" -> {
                    kSpinner.getValueFactory().setValue(15);
                    epochsSpinner.getValueFactory().setValue(200);
                    negativeSamples = 5;
                }
            }
        } finally {
            qualityPreset.setValue(preset);
            applyingPreset = false;
        }
    }

    // --- UMAP Computation ---

    private <T> void commitSpinner(Spinner<T> spinner) {
        if (spinner.isEditable()) {
            try {
                String text = spinner.getEditor().getText();
                spinner.getValueFactory().setValue(
                        spinner.getValueFactory().getConverter().fromString(text));
            } catch (Exception e) {
                // Reset editor text to current value on parse failure
                spinner.getEditor().setText(
                        spinner.getValueFactory().getConverter().toString(spinner.getValue()));
                statusLabel.setText("Invalid value — reverted to " + spinner.getValue());
            }
        }
    }

    private void runUmap() {
        if (cellIndex == null) {
            statusLabel.setText("No cell data available");
            return;
        }

        // Force-commit editable spinner values (JavaFX only commits on Enter/focus-loss)
        commitSpinner(kSpinner);
        commitSpinner(epochsSpinner);
        commitSpinner(maxCellsSpinner);
        commitSpinner(dotSizeSpinner);

        UmapParameters params = new UmapParameters(
                kSpinner.getValue(),
                0.1,
                1.0,
                epochsSpinner.getValue(),
                negativeSamples
        );

        int maxCells = switch (subsampleMode.getValue()) {
            case "Off" -> 0;
            case "Fixed" -> maxCellsSpinner.getValue();
            default -> -1; // Auto: let compute service decide based on available memory
        };

        // Check if warning needed
        int n = cellIndex.size();
        if (n > 100000 && "Off".equals(subsampleMode.getValue())) {
            var result = new Alert(Alert.AlertType.WARNING,
                    String.format("You have %,d cells. UMAP without subsampling may be slow or run out of memory.\n\n" +
                            "Recommended: Enable subsampling.", n),
                    ButtonType.OK, ButtonType.CANCEL).showAndWait();
            if (result.isEmpty() || result.get() == ButtonType.CANCEL) return;
        }

        computeButton.setDisable(true);
        cancelButton.setVisible(true);
        cancelButton.setManaged(true);
        progressIndicator.setVisible(true);
        computeStartTime = System.currentTimeMillis();
        statusLabel.setText("Computing UMAP...");

        computeService.compute(cellIndex, params, maxCells);
    }

    private void cancelUmap() {
        computeService.cancel();
        computeButton.setDisable(false);
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        progressIndicator.setVisible(false);
        statusLabel.setText("UMAP cancelled");
    }

    private void onUmapComplete(UmapResult result) {
        this.umapResult = result;
        computeButton.setDisable(false);
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        progressIndicator.setVisible(false);

        // Enable gating and export controls
        drawButton.setDisable(false);
        clearButton.setDisable(false);
        tagNameField.setDisable(false);
        tagColorPicker.setDisable(false);
        applyTagButton.setDisable(false);
        exportButton.setDisable(false);

        umapCanvas.setData(result.getUmapX(), result.getUmapY());
        markerOverlay.setData(result.getUmapX(), result.getUmapY());

        updatePhenotypeColors();
        legend.update(result.getObjects(), populationTags);

        long elapsed = System.currentTimeMillis() - computeStartTime;
        String timeStr = elapsed < 1000 ? "%dms".formatted(elapsed)
                : "%.1fs".formatted(elapsed / 1000.0);
        statusLabel.setText(String.format("UMAP computed: %,d cells (k=%d) in %s",
                result.size(), result.getParams().k(), timeStr));
    }

    private void onUmapError(String message) {
        computeButton.setDisable(false);
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        progressIndicator.setVisible(false);
        statusLabel.setText("Error: " + message);

        new Alert(Alert.AlertType.ERROR, message, ButtonType.OK).showAndWait();
    }

    // --- Phenotype Coloring ---

    private void updatePhenotypeColors() {
        if (umapResult == null) return;

        PathObject[] objects = umapResult.getObjects();
        int n = objects.length;
        int[] colors = new int[n];

        for (int i = 0; i < n; i++) {
            PathClass pc = objects[i].getPathClass();
            if (pc != null) {
                int c = pc.getColor();
                // QuPath uses ARGB, extract RGB
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                colors[i] = (r << 16) | (g << 8) | b;
            } else {
                colors[i] = 0x808080;
            }
        }

        umapCanvas.setPointColors(colors);
    }

    // --- Polygon Gating ---

    private void onPolygonComplete(List<double[]> vertices) {
        if (umapResult == null) return;

        // Backup original classes
        PathObject[] objects = umapResult.getObjects();
        int n = objects.length;
        originalClasses = new PathClass[n];
        for (int i = 0; i < n; i++) {
            originalClasses[i] = objects[i].getPathClass();
        }

        // Compute mask
        boolean[] insideMask = polygonSelector.computeInsideMask(
                umapResult.getUmapX(), umapResult.getUmapY());

        // Apply UNFOCUSED to outside cells
        PathClass unfocused = PathClass.fromString("UNFOCUSED", 0xFF505050);
        int outsideCount = 0;
        for (int i = 0; i < n; i++) {
            if (!insideMask[i]) {
                objects[i].setPathClass(unfocused);
                outsideCount++;
            }
        }

        // Update QuPath hierarchy
        var imageData = qupath.getImageData();
        if (imageData != null) {
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
        }

        // Update canvas colors
        updatePhenotypeColors();

        // Deactivate polygon drawing
        polygonSelector.deactivate();
        drawButton.setSelected(false);

        int insideCount = n - outsideCount;
        statusLabel.setText(String.format("Polygon: %,d inside / %,d unfocused",
                insideCount, outsideCount));
    }

    private void clearPolygon() {
        // Restore original classes
        if (originalClasses != null && umapResult != null) {
            PathObject[] objects = umapResult.getObjects();
            for (int i = 0; i < objects.length; i++) {
                objects[i].setPathClass(originalClasses[i]);
            }
            originalClasses = null;

            var imageData = qupath.getImageData();
            if (imageData != null) {
                imageData.getHierarchy().fireHierarchyChangedEvent(this);
            }
        }

        polygonSelector.clear();
        polygonSelector.deactivate();
        drawButton.setSelected(false);
        updatePhenotypeColors();

        if (umapResult != null) {
            statusLabel.setText(String.format("UMAP: %,d cells", umapResult.size()));
        }
    }

    // --- Population Tagging ---

    private void applyPopulationTag() {
        if (umapResult == null || originalClasses == null) {
            statusLabel.setText("Draw a polygon first to select cells");
            return;
        }

        String name = tagNameField.getText().trim();
        if (name.isEmpty()) {
            statusLabel.setText("Enter a population name before applying tag");
            tagNameField.requestFocus();
            return;
        }

        Color color = tagColorPicker.getValue();
        int packedColor = ((int) (color.getRed() * 255) << 16)
                | ((int) (color.getGreen() * 255) << 8)
                | (int) (color.getBlue() * 255);

        // Derive inside mask from UNFOCUSED state (polygon vertices are already cleared)
        PathObject[] objects = umapResult.getObjects();
        boolean[] insideMask = new boolean[objects.length];
        for (int i = 0; i < objects.length; i++) {
            PathClass pc = objects[i].getPathClass();
            insideMask[i] = pc == null || !"UNFOCUSED".equals(pc.getName());
        }

        // Apply derived PathClass to cells inside the polygon, preserving phenotype color
        for (int i = 0; i < objects.length; i++) {
            if (insideMask[i]) {
                PathClass current = objects[i].getPathClass();
                String baseName = current != null ? current.getName() : "Unclassified";
                int originalColor = current != null ? current.getColor() : 0xFF808080;
                // Strip existing tag suffix if present (use lastIndexOf to preserve phenotype names containing ": ")
                int tagSep = baseName.lastIndexOf(": ");
                if (tagSep >= 0) {
                    String possibleTag = baseName.substring(tagSep + 2);
                    // Only strip if it matches a known population tag name
                    boolean isKnownTag = populationTags.stream()
                            .anyMatch(t -> t.name().equals(possibleTag));
                    if (isKnownTag) {
                        baseName = baseName.substring(0, tagSep);
                    }
                }
                PathClass derived = PathClass.fromString(baseName + ": " + name,
                        originalColor);
                objects[i].setPathClass(derived);
            }
        }

        // Create population tag
        PopulationTag tag = new PopulationTag(name, packedColor, insideMask);
        populationTags.add(tag);

        // Update ring rendering
        updatePopulationRings();

        // Clear the polygon and restore non-tagged cells
        if (originalClasses != null) {
            for (int i = 0; i < objects.length; i++) {
                if (!insideMask[i]) {
                    objects[i].setPathClass(originalClasses[i]);
                }
            }
            originalClasses = null;
        }

        polygonSelector.clear();
        polygonSelector.deactivate();
        drawButton.setSelected(false);
        updatePhenotypeColors();
        legend.update(objects, populationTags);

        var imageData = qupath.getImageData();
        if (imageData != null) {
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
        }

        statusLabel.setText(String.format("Tagged %,d cells as '%s'", tag.count(), name));
    }

    private void removePopulationTag(String tagName) {
        if (umapResult == null) return;

        PopulationTag tagToRemove = null;
        for (PopulationTag tag : populationTags) {
            if (tag.name().equals(tagName)) {
                tagToRemove = tag;
                break;
            }
        }
        if (tagToRemove == null) return;

        // Restore original PathClass (strip ": tagName" suffix)
        PathObject[] objects = umapResult.getObjects();
        boolean[] mask = tagToRemove.mask();
        String suffix = ": " + tagName;
        for (int i = 0; i < objects.length; i++) {
            if (mask[i]) {
                PathClass current = objects[i].getPathClass();
                if (current != null && current.getName().endsWith(suffix)) {
                    String baseName = current.getName().substring(0,
                            current.getName().length() - suffix.length());
                    objects[i].setPathClass(PathClass.fromString(baseName));
                }
            }
        }

        populationTags.remove(tagToRemove);
        updatePopulationRings();
        updatePhenotypeColors();
        legend.update(objects, populationTags);

        var imageData = qupath.getImageData();
        if (imageData != null) {
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
        }

        statusLabel.setText(String.format("Removed tag '%s'", tagName));
    }

    private void updatePopulationRings() {
        if (populationTags.isEmpty()) {
            umapCanvas.setPopulationRings(null, null);
            return;
        }

        List<int[]> colors = new ArrayList<>();
        List<boolean[]> masks = new ArrayList<>();
        for (PopulationTag tag : populationTags) {
            colors.add(new int[]{tag.color()});
            masks.add(tag.mask());
        }
        umapCanvas.setPopulationRings(colors, masks);
    }

    // --- Marker Overlay ---

    private void onMarkerSelected() {
        if (umapResult == null || cellIndex == null || markerStats == null) return;

        String selected = markerDropdown.getValue();
        if (selected == null || "-- none --".equals(selected)) {
            hideMarkerOverlay();
            return;
        }

        int idx = cellIndex.getMarkerIndex(selected);
        if (idx < 0) return;

        double[] rawValues = cellIndex.getMarkerValues(idx);
        boolean useZScore = "Z-score".equals(colorScaleDropdown.getValue());

        double[] displayValues;
        MarkerOverlayCanvas.ColorScale scale;
        if (useZScore) {
            displayValues = new double[rawValues.length];
            for (int i = 0; i < rawValues.length; i++) {
                displayValues[i] = markerStats.toZScore(selected, rawValues[i]);
            }
            scale = MarkerOverlayCanvas.ColorScale.BLUE_WHITE_RED;
        } else {
            displayValues = rawValues;
            scale = MarkerOverlayCanvas.ColorScale.VIRIDIS;
        }

        markerOverlay.setMarkerValues(displayValues, selected, scale);
        colorScaleLegend.setScale(markerOverlay.getColorMin(), markerOverlay.getColorMax(),
                scale, selected);

        showMarkerOverlay();
    }

    private void showMarkerOverlay() {
        if (!markerOverlayVisible) {
            // Insert marker overlay before legend
            var items = centerSplit.getItems();
            if (items.size() == 2) {
                items.add(1, markerOverlay);
                centerSplit.setDividerPositions(0.45, 0.88);
            }
            markerOverlayVisible = true;
        }
    }

    private void hideMarkerOverlay() {
        if (markerOverlayVisible) {
            centerSplit.getItems().remove(markerOverlay);
            centerSplit.setDividerPositions(0.85);
            markerOverlayVisible = false;
            colorScaleLegend.clear();
        }
    }

    // --- Export ---

    private void exportCsv() {
        if (umapResult == null) {
            statusLabel.setText("No UMAP data to export");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export UMAP Coordinates");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        chooser.setInitialFileName("umap_coordinates.csv");
        File file = chooser.showSaveDialog(getScene().getWindow());

        if (file != null) {
            try {
                umapResult.exportToCsv(file, cellIndex, markerStats, populationTags);
                statusLabel.setText("Exported to " + file.getName());
            } catch (Exception e) {
                statusLabel.setText("Export failed: " + e.getMessage());
            }
        }
    }

    // --- Lifecycle ---

    public void shutdown() {
        computeService.shutdown();
        if (imageDataListener != null) {
            qupath.imageDataProperty().removeListener(imageDataListener);
        }
    }
}
