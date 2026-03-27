package qupath.ext.qumap;

import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import qupath.ext.qumap.ui.QuMapPane;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuPath extension entry point for UMAP dimensionality reduction visualization.
 * Registers a menu item under Extensions and opens a floating Stage.
 */
public class QuMapExtension implements QuPathExtension {

    private static final String NAME = "FlowPath - qUMAP";
    private static final String DESCRIPTION = "UMAP dimensionality reduction and visualization for multiplexed imaging";

    private Stage stage;
    private QuMapPane quMapPane;

    @Override
    public void installExtension(QuPathGUI qupath) {
        var menuItem = new MenuItem(NAME);
        menuItem.setOnAction(e -> showQuMapWindow(qupath));
        menuItem.setAccelerator(new KeyCodeCombination(KeyCode.U, KeyCombination.CONTROL_DOWN));
        qupath.getMenu("Extensions", true).getItems().add(menuItem);
    }

    private void showQuMapWindow(QuPathGUI qupath) {
        if (stage != null && stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }

        quMapPane = new QuMapPane(qupath);

        stage = new Stage();
        stage.setTitle("qUMAP \u2014 Dimensionality Reduction");
        stage.initOwner(qupath.getStage());
        stage.setScene(new Scene(quMapPane, 1100, 700));
        stage.setMinWidth(800);
        stage.setMinHeight(600);

        stage.setOnCloseRequest(e -> {
            if (quMapPane != null) {
                quMapPane.shutdown();
            }
            quMapPane = null;
            stage = null;
        });

        stage.show();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }
}
