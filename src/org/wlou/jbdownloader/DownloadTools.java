package org.wlou.jbdownloader;

import java.util.Map;

/**
 *
 */
public final class DownloadTools {
    private DownloadTools() {}

    public static final String INIT_ERROR_MESSAGE = "Error was occurred during initialization";
    public static final String PROC_ERROR_MESSAGE = "Error was occurred during downloading";
    public static final String SUCCESSFULL_INITIALIZED_MESSAGE = "Download is processing";
    public static final String SUCCESSFULL_COMPLETED_MESSAGE = "Download is successfully completed";
    public static final String PAUSED_MESSAGE = "Download is paused";

    public static void prepareDownload(Download download, Map<String, String> head) {
        assert download != null;
        assert head != null;
    }

    public static void setFailed(Download download) {

    }

}
