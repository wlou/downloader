package org.wlou.jbdownloader;

import org.wlou.jbdownloader.http.Http;

import java.io.IOException;
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

    /**
     * @param download
     * @param head
     */
    public static void prepareDownload(Download download, Map<String, String> head) throws IOException {
        assert download != null;
        assert head != null;

        if (!head.containsKey(Http.CODE_KEY))
            throw new IllegalArgumentException(String.format("%s parameter required", Http.CODE_KEY));

        download.HttpStatusCode = Integer.parseInt(head.get(Http.CODE_KEY));
        if (download.HttpStatusCode < 200 || download.HttpStatusCode >= 300) {
            // 2xx: Success - The action was successfully received, understood, and accepted
            download.CurrentStatus.set(Download.Status.ERROR);
            download.Information = String.format("Unsupported http status: %d", download.HttpStatusCode);
            return;
        }

        download.prepareOutput(Integer.parseInt(head.get(Http.CONTENT_LENGTH_KEY)));
    }

    public static void setFailed(Download download, String info) {
        download.CurrentStatus.set(Download.Status.ERROR);
        download.Information = info;
    }

}
