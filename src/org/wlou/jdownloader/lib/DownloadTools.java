package org.wlou.jdownloader.lib;

import javax.xml.ws.http.HTTPException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Collection of static method to make downloading more convenient and testable.
 */
public final class DownloadTools {

    public static final String INIT_ERROR_MESSAGE = "Error was occurred during initialization";
    public static final String PROC_ERROR_MESSAGE = "Error was occurred during downloading";
    public static final String INITIALIZING_MESSAGE = "Preparing download";
    public static final String SUCCESSFUL_INITIALIZED_MESSAGE = "Processing download";
    public static final String SUCCESSFUL_COMPLETED_MESSAGE = "Download is successfully completed";

    public static final String RESERVED = "[<>:\"\\|\\?\\*]";

    private DownloadTools() {}

    /**
     * Wraps {@link Download} to as sequence of output buffers
     */
    public static class DownloadOutputBuffersIterator implements Iterator<ByteBuffer> {

        /**
         * @param source The download to wrap.
         */
        public DownloadOutputBuffersIterator(Download source) {
            this.source = source;
        }

        /**
         * @return Always true, to determine the end of the buffers check items for null
         */
        @Override
        public boolean hasNext() {
            return true;
        }

        /**
         * @return Next buffer to receive part of resource from the network
         */
        @Override
        public ByteBuffer next() {
            return source.nextOutputBuffer();
        }

        private final Download source;
    }

    /**
     * Performs basic checks.
     * Sets up all required download parameters.
     * @param headers String representation of the Http HEAD response.
     * @throws ParseException when {@link HttpTools#parseHeadResponse(String)} throws
     * @throws IOException when {@link Download#prepareOutput(int, int)} throws
     * @throws HTTPException when Http status code is not in [200; 300)
     */
    public static int parseContentLength(String headers) throws ParseException, IOException {
        assert headers != null;

        Map<String, String> parsedHeaders = HttpTools.parseHeadResponse(headers);

        int status = Integer.parseInt(parsedHeaders.get(HttpTools.CODE_KEY));
        if (status < 200 || status >= 300)
            // 2xx: Success - The action was successfully received, understood, and accepted
            throw new HTTPException(status);

        return Integer.parseInt(parsedHeaders.get(HttpTools.CONTENT_LENGTH_KEY));
    }

    /**
     * Checks whether the download is under processing.
     * @param download The download to check.
     * @return True if the status of the <code>download</code> allows next processing step
     */
    public static boolean isActiveDownload(Download download) {
        Download.Status status = download.getCurrentStatus();
        switch (status) {
            case NEW:
            case INITIALIZING:
            case INITIALIZED:
            case DOWNLOADING:
                return true;
        }
        return false;
    }

    /**
     * Auxiliary method for updating progress of the download and checking the status.
     * @param download The download to check.
     * @return True if the status of the <code>download</code> allows next operation.
     */
    public static boolean canProceedProcessing(Download download) {
        download.invalidateProgress();
        return download.getCurrentStatus() == Download.Status.DOWNLOADING;
    }

    /**
     * Auxiliary method for checking download status allows proceed initilization.
     * @param download The download to check.
     * @return True if the status of the <code>download</code> allows next operation.
     */
    public static boolean canProceedInitialization(Download download) {
        return download.getCurrentStatus() == Download.Status.INITIALIZING;
    }

    public static int hash(Download download) {
        return Objects.hash(download.getWhat().toString(), download.getWhere().toString());
    }
}
