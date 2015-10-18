package org.wlou.jbdownloader.gui.controllers;


import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.wlou.jbdownloader.lib.Download;

import java.util.Observable;
import java.util.Observer;
import java.util.function.Supplier;

public class DownloadController implements Observer {
    private final StringProperty srcUrl;
    private final StringProperty targetFile;
    private final StringProperty information;
    private final DoubleProperty progress;

    public DownloadController(Download init) {
        download = init;
        download.addObserver(this);
        srcUrl = new SimpleStringProperty(download.getWhat().toString());
        information = new SimpleStringProperty(download.getInformation());
        targetFile = new SimpleStringProperty("");
        if (download.getCurrentStatus() == Download.Status.DOWNLOADED)
            targetFile.setValue(download.getWhere().toString());
        this.progress = new SimpleDoubleProperty(download.getProgress());

    }

    public StringProperty srcUrlProperty() { return srcUrl; }
    public StringProperty targetFileProperty() { return targetFile; }
    public StringProperty informationProperty() { return information; }
    public DoubleProperty progressProperty() { return progress; }

    public Download getDownload() {
        return download;
    }

    @Override
    public void update(Observable o, Object arg) {
        final Supplier<Void> updateLogic = () -> {
            information.setValue(download.getInformation());
            progress.setValue(download.getProgress());
            if (download.getCurrentStatus() == Download.Status.DOWNLOADED)
                targetFile.setValue(download.getWhere().toString());
            return null;
        };
        if (download.equals(o)) {
            if (Platform.isFxApplicationThread())
                updateLogic.get();
            else
                Platform.runLater(() -> updateLogic.get());
        }
    }

    private final Download download;
}