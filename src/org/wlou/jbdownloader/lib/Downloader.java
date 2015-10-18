package org.wlou.jbdownloader.lib;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/**
 *
 */
public class Downloader implements Runnable {

    private static Logger LOG = Logger.getLogger(Downloader.class.getName());

    public Downloader(List<Download> downloads, ExecutorService executors) throws IOException {
        stop = false;
        httpParams = new HashMap<>();
        httpParams.put(HttpTools.CONNECTION_DIRECTIVE, HttpTools.CONNECTION_KEEP_ALIVE);
        channels = AsynchronousChannelGroup.withThreadPool(executors);
        tasks = downloads;
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

    public void stop() {
        stop = true;
        synchronized (tasks) { tasks.notifyAll(); }
    }

    /**
     * @param download
     * @throws IOException
     */
    public void initialize(Download download) throws IOException {
        synchronized (download) {
            if (download.getCurrentStatus() != Download.Status.NEW)
                return;
            download.setCurrentStatus(Download.Status.INITIALIZING, DownloadTools.INITIALIZING_MESSAGE);
            download.notifyAll();
        }

        // Initialization error handler
        final BiConsumer<Throwable, AsyncTools.NetworkOperationContext> onError = (exc, ctx) -> {
            LOG.error(exc);
            synchronized (download) {
                download.setCurrentStatus(Download.Status.ERROR, DownloadTools.INIT_ERROR_MESSAGE);
                download.notifyAll();
            }
            if (ctx != null)
                ctx.close();
        };

        // Prepare endpoint parameters.
        final URL what = download.getWhat();
        SocketAddress remote;
        try {
            remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), HttpTools.DEFAULT_PORT);
        }
        catch (Exception e) {
            onError.accept(e, null);
            return;
        }

        // Prepare context.
        AsyncTools.NetworkOperationContext context = new AsyncTools.NetworkOperationContext(
            AsynchronousSocketChannel.open(channels),
            HttpTools.makeHeadRequest(what, httpParams),
            // FIXME: for now only short headers are supported
            ByteBuffer.allocate(1024)
        );

        // Prepare asynchronous initialization workflow.
        // [Connect] -> [Send HEAD request] -> [Handle HEAD response]
        // The process based on callbacks, so define them in the reversed order.

        // FIXME: use channel reader
        final CompletionHandler<Integer, AsyncTools.NetworkOperationContext> onReceived = AsyncTools.handlerFrom(
            (read, ctx) -> {
                try {
                    String responseString = ctx.responseString(read, HttpTools.DEFAULT_CONTENT_CHARSET);
                    LOG.debug(String.format("Head response has been received: [%s]", responseString));
                    DownloadTools.prepareDownload(download, responseString);
                    synchronized (tasks) { tasks.notifyAll(); }
                    ctx.close();
                }
                catch (Exception exc) {
                    onError.accept(exc, ctx);
                }
            },
            onError
        );

        final CompletionHandler<Integer, AsyncTools.NetworkOperationContext> onSent = AsyncTools.handlerFrom(
            (written, ctx) -> {
                assert ctx.requestBytes(HttpTools.DEFAULT_CONTENT_CHARSET).limit() == written;
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
                ctx.Channel.write(ctx.requestBytes(HttpTools.DEFAULT_CONTENT_CHARSET), ctx, onSent);
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
        synchronized (download) {
            if (download.getCurrentStatus() != Download.Status.INITIALIZED)
                return;
            download.setCurrentStatus(Download.Status.DOWNLOADING, DownloadTools.SUCCESSFUL_INITIALIZED_MESSAGE);
            download.notifyAll();
        }

        // Prepare endpoint parameters.
        final URL what = download.getWhat();
        SocketAddress remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), HttpTools.DEFAULT_PORT);

        // Prepare context.
        AsyncTools.NetworkOperationContext context = new AsyncTools.NetworkOperationContext(
            AsynchronousSocketChannel.open(channels),
            HttpTools.makeGetRequest(what, httpParams),
            download.nextOutputBuffer()
        );

        // Prepare asynchronous download workflow.
        // [Connect] -> [Send Get request] -> [Read portion1] -> [Read portion2] ... -> [Complete]
        // The process based on callbacks, so define them in the reversed order.

        final BiConsumer<Throwable, AsyncTools.NetworkOperationContext> onError = (exc, ctx) -> {
            LOG.error(exc);
            synchronized (download) {
                download.setCurrentStatus(Download.Status.ERROR, DownloadTools.PROC_ERROR_MESSAGE);
                download.releaseBuffers();
                download.notifyAll();
            }
            if (ctx != null)
                ctx.close();

        };

        final BiConsumer<Integer, AsyncTools.NetworkOperationContext> onCompleteReading = (read, ctx) -> {
            LOG.debug(String.format("Reading \"%s\" channel successfully completed", what));
            synchronized (download) {
                download.setCurrentStatus(Download.Status.DOWNLOADED, DownloadTools.SUCCESSFUL_COMPLETED_MESSAGE);
                download.releaseBuffers();
                download.notifyAll();
            }
            ctx.close();
        };

        final AsyncTools.ChannelReader onReceived = new AsyncTools.ChannelReader(
            new DownloadTools.DownloadOutputBuffersIterator(download),
            () -> {
                download.updateProgress();
                return download.getCurrentStatus() == Download.Status.DOWNLOADING;
            },
            onCompleteReading,
            onError
        );

        final CompletionHandler<Integer, AsyncTools.NetworkOperationContext> onSent = AsyncTools.handlerFrom(
            (written, ctx) -> {
                assert ctx.requestBytes(HttpTools.DEFAULT_CONTENT_CHARSET).limit() == written;
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
                ctx.Channel.write(ctx.requestBytes(HttpTools.DEFAULT_CONTENT_CHARSET), ctx, onSent);
            },
            onError
        );

        // Start the workflow.
        LOG.debug(String.format("Connecting to: [%s]", what.getHost()));
        context.Channel.connect(remote, context, onConnect);
    }

    private volatile boolean stop;
    private final List<Download> tasks;
    private final AsynchronousChannelGroup channels;
    private final Map<String, String> httpParams;
}
