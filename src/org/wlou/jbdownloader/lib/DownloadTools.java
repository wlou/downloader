package org.wlou.jbdownloader.lib;

import javax.xml.ws.http.HTTPException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.Iterator;
import java.util.Map;

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
            synchronized (source) {
                return source.nextOutputBuffer();
            }
        }

        private final Download source;
    }

    /**
     * Performs basic checks.
     * Sets up all required download parameters.
     * @param target The download to prepare.
     * @param headers String representation of the Http HEAD response.
     * @throws ParseException when {@link HttpTools#parseHeadResponse(String)} throws
     * @throws IOException when {@link Download#prepareOutput(int, int)} throws
     * @throws HTTPException when Http status code is not in [200; 300)
     */
    public static void prepareDownload(Download target, String headers) throws ParseException, IOException {
        Map<String, String> parsedHeaders = HttpTools.parseHeadResponse(headers);

        int status = Integer.parseInt(parsedHeaders.get(HttpTools.CODE_KEY));
        if (status < 200 || status >= 300)
            // 2xx: Success - The action was successfully received, understood, and accepted
            throw new HTTPException(status);

        int headersLength = headers.getBytes(HttpTools.DEFAULT_CONTENT_CHARSET).length;
        int contentLength = Integer.parseInt(parsedHeaders.get(HttpTools.CONTENT_LENGTH_KEY));

        synchronized (target) {
            if (target.getCurrentStatus() != Download.Status.INITIALIZING)
                return;
            target.prepareOutput(headersLength, contentLength);
            target.setCurrentStatus(Download.Status.INITIALIZED, SUCCESSFUL_INITIALIZED_MESSAGE);
            target.notify();
        }
    }
}
