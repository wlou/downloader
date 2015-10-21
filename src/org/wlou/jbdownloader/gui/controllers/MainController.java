package org.wlou.jbdownloader.gui.controllers;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Point2D;
import javafx.scene.control.*;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.scene.layout.FlowPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import org.wlou.jbdownloader.gui.controls.UrlValidator;
import org.wlou.jbdownloader.lib.Download;
import org.wlou.jbdownloader.lib.DownloadManager;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Observable;
import java.util.Observer;
import java.util.function.Supplier;

public class MainController implements Observer {

    @FXML
    private Menu parametersMenu;
    @FXML
    private ContextMenu downloadContextMenu;
    @FXML
    private MenuItem showDownloadInFMMenuItem;
    @FXML
    private TableView<DownloadController> downloadsTableView;
    @FXML
    private TableColumn<DownloadController, String> srcUrlColumn;
    @FXML
    private TableColumn<DownloadController, String> targetFileColumn;
    @FXML
    private TableColumn<DownloadController, String> informationColumn;
    @FXML
    private TableColumn<DownloadController, Double> progressColumn;
    @FXML
    private TextField baseDirField;
    @FXML
    private UrlValidator urlFieldValidator;
    @FXML
    private FlowPane missedTargetPopupContent;

    public void setDownloadManager(DownloadManager manager) {
        this.manager = manager;
        this.manager.addObserver(this);
    }

    public void addNewDownload(ActionEvent actionEvent) {
        urlFieldValidator.eval();
        if (!urlFieldValidator.getHasErrors()) {
            URL url = urlFieldValidator.getValidUrl();
            Path baseDir = Paths.get(baseDirField.getText());
            manager.addDownload(url, baseDir);
            urlFieldValidator.getSrcTextInputControl().setText("");
        }
    }

    public void selectBaseDir(ActionEvent actionEvent) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        File selectedDirectory = directoryChooser.showDialog(mainStage);
        if(selectedDirectory != null){
            baseDirField.setText(selectedDirectory.getAbsolutePath());
        }
    }

    public void setThreads(ActionEvent actionEvent) {
        CheckMenuItem current = (CheckMenuItem)actionEvent.getSource();
        current.setSelected(true);
        for (MenuItem item: parametersMenu.getItems()) {
            CheckMenuItem checkItem = (CheckMenuItem)item;
            if (!checkItem.equals(current))
                checkItem.setSelected(false);
        }
        manager.setParallelCapacity(Integer.parseInt(current.getId()));
    }

    public void showDownloadInFM(ActionEvent actionEvent) throws IOException {
        DownloadController dc = downloadsTableView.getSelectionModel().getSelectedItem();
        Download d = dc.getDownload();
        if (d.getCurrentStatus() == Download.Status.DOWNLOADED) {
            if (!d.getWhere().toFile().exists()) {
                Popup popup = new Popup();
                popup.getContent().add(missedTargetPopupContent);
                popup.setAutoHide(true);
                // FIXME: make precise calculations
                double localX = downloadsTableView.getWidth()/2 - 90;
                double localY = downloadsTableView.getHeight()/3;
                Point2D anchor = downloadsTableView.localToScreen(localX, localY);
                popup.show(mainStage, anchor.getX(), anchor.getY());
                manager.removeDownload(d);
            }
            else
                Desktop.getDesktop().browse(d.getWhere().getParent().toUri());
        }

    }

    public void removeDownload(ActionEvent actionEvent) {
        DownloadController dc = downloadsTableView.getSelectionModel().getSelectedItem();
        Download d = dc.getDownload();
        manager.removeDownload(d);
    }

    @FXML
    private void initialize(){
        baseDirField.setText(Paths.get(".").toAbsolutePath().normalize().toString());
        srcUrlColumn.setCellValueFactory(cellData -> cellData.getValue().srcUrlProperty());
        targetFileColumn.setCellValueFactory(cellData -> cellData.getValue().targetFileProperty());
        informationColumn.setCellValueFactory(cellData -> cellData.getValue().informationProperty());
        progressColumn.setCellValueFactory(cellData -> cellData.getValue().progressProperty().asObject());
        progressColumn.setCellFactory(ProgressBarTableCell.<DownloadController>forTableColumn());

        // Context menu for downloads table initialization
        showDownloadInFMMenuItem.setDisable(!Desktop.isDesktopSupported());
        downloadsTableView.setRowFactory(callback -> {
            final TableRow<DownloadController> row = new TableRow<>();
            row.contextMenuProperty().bind(
                Bindings.when(Bindings.isNotNull(row.itemProperty()))
                        .then(downloadContextMenu)
                        .otherwise((ContextMenu) null)
            );
            row.setOnContextMenuRequested((actionEvent) -> {
                DownloadController dc = row.getItem();
                showDownloadInFMMenuItem.setDisable(dc.getDownload().getCurrentStatus() != Download.Status.DOWNLOADED);
            });
            return row;
        });
        downloadsTableView.setItems(downloads);
        urlFieldValidator.reset();
    }

    public void setMainStage(Stage mainStage) { this.mainStage = mainStage; }

    @Override
    public void update(Observable o, Object arg) {
        Supplier<Void> updateLogic = () -> {
            downloads.clear();
            for (Download d : manager.getDownloads())
                downloads.add(new DownloadController(d));
            return null;
        };
        if (manager.equals(o)) {
            if (Platform.isFxApplicationThread())
                updateLogic.get();
            else
                Platform.runLater(updateLogic::get);
        }
    }

    private Stage mainStage = null;
    private DownloadManager manager = null;
    private final ObservableList<DownloadController> downloads = FXCollections.observableArrayList();
}
