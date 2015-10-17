package org.wlou.jbdownloader.gui.controllers;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.stage.Stage;
import org.wlou.jbdownloader.Download;
import org.wlou.jbdownloader.DownloadManager;
import org.wlou.jbdownloader.gui.controls.UrlValidator;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Observable;
import java.util.Observer;
import java.util.function.Supplier;

public class MainController implements Observer {

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
    private TextField urlField;
    @FXML
    private UrlValidator urlFieldValidator;

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
        }
    }

    @FXML
    private void initialize(){
        baseDirField.setText(Paths.get(".").toAbsolutePath().normalize().toString());
        srcUrlColumn.setCellValueFactory(cellData -> cellData.getValue().srcUrlProperty());
        targetFileColumn.setCellValueFactory(cellData -> cellData.getValue().targetFileProperty());
        informationColumn.setCellValueFactory(cellData -> cellData.getValue().informationProperty());
        progressColumn.setCellValueFactory(cellData -> cellData.getValue().progressProperty().asObject());
        progressColumn.setCellFactory(ProgressBarTableCell.<DownloadController>forTableColumn());
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
                Platform.runLater(() -> updateLogic.get());
        }
    }

    private Stage mainStage = null;
    private DownloadManager manager = null;
    private final ObservableList<DownloadController> downloads = FXCollections.observableArrayList();
}
