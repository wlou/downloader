package org.wlou.jbdownloader.gui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.apache.log4j.Logger;
import org.wlou.jbdownloader.gui.controllers.MainController;
import org.wlou.jbdownloader.lib.DownloadManager;

public class Main extends Application {

    private static Logger LOG = Logger.getLogger(Main.class.getName());

    @Override
    public void start(Stage primaryStage) throws Exception{
        FXMLLoader loader = new FXMLLoader(Main.class.getResource("views/MainView.fxml"));
        Parent root = loader.load();
        MainController controller = loader.getController();
        controller.setDownloadManager(mainManager);
        controller.setMainStage(primaryStage);
        primaryStage.setTitle("JBDownloader");
        primaryStage.setScene(new Scene(root));
        primaryStage.show();
    }

    public static void main(String[] args) {
        try (DownloadManager manager = new DownloadManager()) {
            mainManager = manager;
            launch(args);
        }
        catch (Exception e) {
            LOG.error(e);
        }
    }

    private static DownloadManager mainManager;
}

