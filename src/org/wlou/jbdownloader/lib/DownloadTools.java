package org.wlou.jbdownloader.lib;

import javax.xml.ws.http.HTTPException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.Iterator;
import java.util.Map;

/**
 *
 */
public final class DownloadTools {

    public static final String INIT_ERROR_MESSAGE = "Error was occurred during initialization";
    public static final String PROC_ERROR_MESSAGE = "Error was occurred during downloading";
    public static final String INITIALIZING_MESSAGE = "Preparing download";
    public static final String SUCCESSFUL_INITIALIZED_MESSAGE = "Processing download";
    public static final String SUCCESSFUL_COMPLETED_MESSAGE = "Download is successfully completed";

    private DownloadTools() {}

    public static class DownloadOutputBuffersIterator implements Iterator<ByteBuffer> {

        public DownloadOutputBuffersIterator(Download source) {
            this.source = source;
        }

        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public ByteBuffer next() {
            synchronized (source) {
                return source.nextOutputBuffer();
            }
        }

        private final Download source;
    }

    /**
     * @param target
     * @param headers
     * @throws ParseException
     * @throws IOException
     * @throws HTTPException
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
