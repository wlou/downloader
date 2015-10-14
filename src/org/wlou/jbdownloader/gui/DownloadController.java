package org.wlou.jbdownloader.gui;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.wlou.jbdownloader.Download;

public class DownloadController {
    private final StringProperty srcUrl;
    private final StringProperty targetFile;
    private final StringProperty information;

    public DownloadController(Download download) {
        this.download = download;
        this.srcUrl = new SimpleStringProperty(this.download.getWhat().toString());
        this.targetFile = new SimpleStringProperty("");
        this.information = new SimpleStringProperty(this.download.getInformation());
    }

    public String getSrcUrl() { return srcUrl.get(); }
    public void setSrcUrl(String srcUrl) { this.srcUrl.set(srcUrl); }
    public StringProperty srcUrlProperty() { return srcUrl; }

    public String getTargetFile() { return targetFile.get(); }
    public void setTargetFile(String targetFile) { this.targetFile.set(targetFile); }
    public StringProperty targetFileProperty() { return targetFile; }

    public String getInformation() { return information.get(); }
    public void setInformation(String information) { this.information.set(information); }
    public StringProperty informationProperty() { return information; }

    private final Download download;
}