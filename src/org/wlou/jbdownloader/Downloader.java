package org.wlou.jbdownloader;

import org.apache.log4j.Logger;
import org.wlou.jbdownloader.http.Http;
import org.wlou.jbdownloader.http.HttpGet;
import org.wlou.jbdownloader.http.HttpHead;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/**
 *
 */
public class Downloader implements Runnable {

    public static final String INIT_ERROR_MESSAGE = "Error was occurred during initialization";
    public static final String PROC_ERROR_MESSAGE = "Error was occurred during downloading";
    public static final String INITIALIZING_MESSAGE = "Preparing download";
    public static final String SUCCESSFUL_INITIALIZED_MESSAGE = "Processing download";
    public static final String SUCCESSFUL_COMPLETED_MESSAGE = "Download is successfully completed";
    public static final String PAUSED_MESSAGE = "Download is paused";

    private static Logger LOG = Logger.getLogger(Downloader.class.getName());

    public Downloader(ConcurrentLinkedQueue<Download> tasks, ExecutorService executors) throws IOException {
        stop = false;
        channels = AsynchronousChannelGroup.withThreadPool(executors);
        this.tasks = tasks;
    }

    /**
     *
     */
    @Override
    public void run() {
        try {
            while (!stop) {
                boolean hasWork = false;
                for (Download download : tasks) {
                    switch (download.getCurrentStatus()) {
                        case NEW:
                            initialize(download);
                            hasWork = true;
                            break;
                        case INITIALIZED:
                            process(download);
                            break;
                        case GHOST:
                            break;
                    }
                }
                if (!hasWork)
                    synchronized (tasks) { tasks.wait(); }
            }
        }
        catch (InterruptedException ignored) {}
        catch (Exception e) {
            LOG.error(e);
        }
    }

    /**
     * @param download
     * @throws IOException
     */
    public void initialize(Download download) throws IOException {
        boolean acquired = false;
        while (Download.Status.NEW == download.getCurrentStatus())
            acquired = download.changeCurrentStatus(Download.Status.NEW, Download.Status.INITIALIZING, INITIALIZING_MESSAGE);

        if (!acquired)
            return;

        // Prepare endpoint parameters.
        final URL what = download.getWhat();
        final SocketAddress remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), Http.DEFAULT_PORT);

        // Prepare context.
        AsyncTools.NetworkOperationContext context = new AsyncTools.NetworkOperationContext(
            AsynchronousSocketChannel.open(channels),
            HttpHead.makeRequest(what),
            ByteBuffer.allocate(1024) // FIXME: for now only short headers are supported
        );

        // Prepare asynchronous initialization workflow.
        // [Connect] -> [Send HEAD request] -> [Handle HEAD response]
        // The process based on callbacks, so define them in the reversed order.

        final BiConsumer<Throwable, AsyncTools.NetworkOperationContext> onError = (exc, ctx) -> {
            try {
                LOG.error(exc);
                download.setCurrentStatus(Download.Status.ERROR, INIT_ERROR_MESSAGE);
                ctx.close();
            } catch (IOException ignored) {} // Ignore ctx.close exception
        };

        final CompletionHandler<Integer, AsyncTools.NetworkOperationContext> onReceived =
            new CompletionHandler<Integer, AsyncTools.NetworkOperationContext>() {
                @Override
                public void completed(Integer read, AsyncTools.NetworkOperationContext ctx) {
                    try {
                        String responseString = ctx.responseString(read, Http.DEFAULT_CONTENT_CHARSET);
                        LOG.debug(String.format("Head response has been received: [%s]", responseString));
                        prepareDownload(download, responseString);
                        synchronized (tasks) { tasks.notify(); }
                        ctx.close();
                    } catch (IOException ignored) {} // Ignore ctx.close exception
                    catch (Exception exc) {
                        onError.accept(exc, ctx);
                    }
                }

                @Override
                public void failed(Throwable exc, AsyncTools.NetworkOperationContext ctx) {
                    onError.accept(exc, ctx);
                }

                //TODO: more specific exceptions
                private void prepareDownload(Download target, String headers) throws Exception {
                    Map<String, String> parsedHeaders = HttpHead.parseResponse(headers);

                    int status = Integer.parseInt(parsedHeaders.get(Http.CODE_KEY));
                    if (status < 200 || status >= 300)
                        // 2xx: Success - The action was successfully received, understood, and accepted
                        throw new Exception(String.format("Unsupported http status: %d", status));

                    int headersLength = headers.getBytes(Http.DEFAULT_CONTENT_CHARSET).length;
                    int contentLength = Integer.parseInt(parsedHeaders.get(Http.CONTENT_LENGTH_KEY));

                    target.prepareOutput(headersLength, contentLength);
                    target.changeCurrentStatus(Download.Status.INITIALIZING, Download.Status.INITIALIZED,
                            SUCCESSFUL_INITIALIZED_MESSAGE);
                }
            };

        final CompletionHandler<Integer, AsyncTools.NetworkOperationContext> onSent = AsyncTools.handlerFrom(
            (written, ctx) -> {
                assert ctx.requestBytes(Http.DEFAULT_CONTENT_CHARSET).limit() == written;
                LOG.debug(String.format("Request of %d bytes is sent", written));
                LOG.debug("Start reading response");
                ctx.Channel.read(ctx.ResponseBytes, ctx, onReceived);
            },
            onError
        );

        final CompletionHandler<Void, AsyncTools.NetworkOperationContext> onConnect = AsyncTools.handlerFrom(
            (stub, ctx) -> {
                LOG.debug(String.format("Domain: [%s] connected", what.getHost()));
                LOG.debug(String.format("Sending request: [%s]", ctx.RequestString));
                ctx.Channel.write(ctx.requestBytes(Http.DEFAULT_CONTENT_CHARSET), ctx, onSent);
            },
            onError
        );

        // Start the workflow.
        LOG.debug(String.format("Connecting to: [%s]", what.getHost()));
        context.Channel.connect(remote, context, onConnect);
    }

    /**
     * @param download
     */
    public void process(Download download) throws IOException {
        boolean acquired = false;
        while (Download.Status.INITIALIZED == download.getCurrentStatus()) {
            acquired = download.changeCurrentStatus(Download.Status.INITIALIZED, Download.Status.DOWNLOADING,
                    SUCCESSFUL_INITIALIZED_MESSAGE);
        }

        if (!acquired)
            return;

        // Prepare endpoint parameters.
        final URL what = download.getWhat();
        SocketAddress remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), Http.DEFAULT_PORT);

        // Prepare context.
        AsyncTools.NetworkOperationContext context = new AsyncTools.NetworkOperationContext(
            AsynchronousSocketChannel.open(channels),
            HttpGet.makeRequest(what),
            download.nextOutputBuffer()
        );

        // Prepare asynchronous download workflow.
        // [Connect] -> [Send Get request] -> [Read portion1] -> [Read portion2] ... -> [Complete]
        // The process based on callbacks, so define them in the reversed order.

        final BiConsumer<Throwable, AsyncTools.NetworkOperationContext> onError = (exc, ctx) -> {
            try {
                LOG.error(exc);
                download.setCurrentStatus(Download.Status.ERROR, PROC_ERROR_MESSAGE);
                ctx.close();
            } catch (IOException ignored) {} // Ignore ctx.close exception
        };

        final BiConsumer<Integer, AsyncTools.NetworkOperationContext> onCompleteReading = (read, ctx) -> {
            try {
                LOG.debug(String.format("Reading \"%s\" channel successfully completed", what));
                download.setCurrentStatus(Download.Status.DOWNLOADED, SUCCESSFUL_COMPLETED_MESSAGE);
                ctx.close();
            } catch (IOException ignored) {} // Ignore ctx.close exception
        };

        final AsyncTools.ChannelReader onReceived = new AsyncTools.ChannelReader(
            new DownloadTools.DownloadOutputBuffersIterator(download),
            () -> download.getCurrentStatus() == Download.Status.DOWNLOADING,
            onCompleteReading,
            onError
        );

        final CompletionHandler<Integer, AsyncTools.NetworkOperationContext> onSent = AsyncTools.handlerFrom(
            (written, ctx) -> {
                assert ctx.requestBytes(Http.DEFAULT_CONTENT_CHARSET).limit() == written;
                LOG.debug(String.format("Request of %d bytes is sent", written));
                LOG.debug("Start reading response");
                ctx.Channel.read(ctx.ResponseBytes, ctx, onReceived);
            },
            onError
        );

        final CompletionHandler<Void, AsyncTools.NetworkOperationContext> onConnect = AsyncTools.handlerFrom(
            (stub, ctx) -> {
                LOG.debug(String.format("Domain: [%s] connected", what.getHost()));
                LOG.debug(String.format("Sending request: [%s]", ctx.RequestString));
                ctx.Channel.write(ctx.requestBytes(Http.DEFAULT_CONTENT_CHARSET), ctx, onSent);
            },
            onError
        );

        // Start the workflow.
        LOG.debug(String.format("Connecting to: [%s]", what.getHost()));
        context.Channel.connect(remote, context, onConnect);
    }

    private volatile boolean stop;
    private final ConcurrentLinkedQueue<Download> tasks;
    private final AsynchronousChannelGroup channels;
}
