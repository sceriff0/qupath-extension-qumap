package qupath.ext.qumap.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import qupath.ext.qumap.model.PopulationTag;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.*;
import java.util.function.Consumer;

/**
 * Legend panel showing phenotype colors and population tags.
 */
public class PhenotypeLegend extends ScrollPane {

    private final VBox content;
    private Consumer<String> onPopulationRemove;

    public PhenotypeLegend() {
        content = new VBox(2);
        content.setPadding(new Insets(5));
        content.setStyle("-fx-background-color: #2a2a2a;");
        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setPrefWidth(140);
        setMinWidth(120);
        setStyle("-fx-background: #2a2a2a; -fx-background-color: #2a2a2a;");
    }

    public void setOnPopulationRemove(Consumer<String> cb) { this.onPopulationRemove = cb; }

    /**
     * Update legend from cell objects and population tags.
     */
    public void update(PathObject[] objects, List<PopulationTag> populationTags) {
        content.getChildren().clear();

        if (objects == null || objects.length == 0) return;

        // --- Phenotype section ---
        var header = new Label("Phenotypes");
        header.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold; -fx-font-size: 10;");
        content.getChildren().add(header);

        // Count by PathClass
        Map<String, int[]> classCounts = new LinkedHashMap<>(); // name -> [count, color]
        for (PathObject obj : objects) {
            PathClass pc = obj.getPathClass();
            String name = pc != null ? pc.getName() : "Unclassified";
            int color = pc != null ? pc.getColor() : 0x808080;
            classCounts.computeIfAbsent(name, k -> new int[]{0, color})[0]++;
        }

        // Sort by count descending
        var sorted = new ArrayList<>(classCounts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));

        for (var entry : sorted) {
            String name = entry.getKey();
            int count = entry.getValue()[0];
            int color = entry.getValue()[1];

            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;

            var swatch = new Circle(5, Color.rgb(r, g, b));
            var label = new Label(String.format("%s (%,d)", truncate(name, 14), count));
            label.setStyle("-fx-text-fill: #ccc; -fx-font-size: 9;");

            var row = new HBox(4, swatch, label);
            row.setPadding(new Insets(1, 0, 1, 2));
            content.getChildren().add(row);
        }

        // --- Population tags section ---
        if (populationTags != null && !populationTags.isEmpty()) {
            var separator = new Label("Populations");
            separator.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold; -fx-font-size: 10; -fx-padding: 5 0 0 0;");
            content.getChildren().add(separator);

            for (PopulationTag tag : populationTags) {
                int tc = tag.color();
                int r = (tc >> 16) & 0xFF;
                int g = (tc >> 8) & 0xFF;
                int b = tc & 0xFF;

                var ring = new Circle(5);
                ring.setFill(Color.TRANSPARENT);
                ring.setStroke(Color.rgb(r, g, b));
                ring.setStrokeWidth(2);

                var label = new Label(String.format("%s (%,d)", truncate(tag.name(), 12), tag.count()));
                label.setStyle("-fx-text-fill: #ccc; -fx-font-size: 9;");

                var row = new HBox(4, ring, label);
                row.setPadding(new Insets(1, 0, 1, 2));
                Tooltip.install(row, new Tooltip("Right-click to remove"));

                // Right-click to remove
                row.setOnContextMenuRequested(e -> {
                    if (onPopulationRemove != null) {
                        onPopulationRemove.accept(tag.name());
                    }
                });

                content.getChildren().add(row);
            }
        }
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "\u2026" : s;
    }
}
