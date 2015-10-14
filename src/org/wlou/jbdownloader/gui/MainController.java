package org.wlou.jbdownloader.gui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.stage.Stage;

public class MainController {

    private Stage mainStage = null;
    private ObservableList<DownloadController> tasks = FXCollections.observableArrayList();

    @FXML
    private TableView<DownloadController> tasksTableView;
    @FXML
    private TableColumn<DownloadController, String> srcUrlColumn;
    @FXML
    private TableColumn<DownloadController, String> targetFileColumn;
    @FXML
    private TableColumn<DownloadController, String> informationColumn;
    @FXML
    private TableColumn<DownloadController, Double> progressColumn;


    public void addNewDownload(ActionEvent actionEvent) {

    }

    @FXML
    private void initialize(){
        srcUrlColumn.setCellValueFactory(cellData -> cellData.getValue().srcUrlProperty());
        targetFileColumn.setCellValueFactory(cellData -> cellData.getValue().targetFileProperty());
        informationColumn.setCellValueFactory(cellData -> cellData.getValue().informationProperty());
//        progressColumn.setCellValueFactory(cellData -> cellData.getValue().progressProperty().asObject());
        progressColumn.setCellFactory(ProgressBarTableCell.<DownloadController>forTableColumn());
        tasksTableView.setItems(tasks);
    }

    public void setMainStage(Stage mainStage) { this.mainStage = mainStage; }
}
