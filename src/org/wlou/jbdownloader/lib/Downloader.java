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
 * The dispatcher object.
 * The main activity is scanning the downloading queue and calling appropriate handlers:
 *  - initialize for new downloads (see {@link org.wlou.jbdownloader.lib.Download.Status#NEW}
 *  - process for initialized downloads (see {@link org.wlou.jbdownloader.lib.Download.Status#INITIALIZED}
 */
public class Downloader implements Runnable {

    private static Logger LOG = Logger.getLogger(Downloader.class.getName());

    /**
     * Initializes downloading queue and worker-treads
     * @param downloads The downloading queue.
     * @param executors The worker-threads.
     * @throws IOException when {@link AsynchronousChannelGroup#withThreadPool(ExecutorService)} throws
     */
    public Downloader(List<Download> downloads, ExecutorService executors) throws IOException {
        httpParams = new HashMap<>();
        httpParams.put(HttpTools.CONNECTION_DIRECTIVE, HttpTools.CONNECTION_KEEP_ALIVE);
        channels = AsynchronousChannelGroup.withThreadPool(executors);
        tasks = downloads;
    }

    /**
     * Implements infinite loop over downloading queue and dispatching calls.
     */
    @Override
    public void run() {
        try {
            //noinspection InfiniteLoopStatement
            while (true) {
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
        catch (InterruptedException ignored) {} // just interrupt the loop
        catch (Exception e) {
            LOG.error(e);
        }
    }

    /**
     * Main initialization workflow.
     * General scheme:
     *  1. checks {@link Download}'s source domain for availability;
     *  2. prepares Http HEAD request and {@link org.wlou.jbdownloader.lib.AsyncTools.NetworkOperationContext};
     *  3. sets up sequence of callback handlers of Http HEAD response, simplified logic is as follows:
     *     send request -> read response -> call {@link DownloadTools#prepareDownload(Download, String)};
     *  4. runs Http HEAD request asynchronously.
     * @param download The download to initialize
     * @throws IOException when {@link AsynchronousSocketChannel#open()} throws
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
            int port = what.getPort() == -1 ? HttpTools.DEFAULT_PORT : what.getPort();
            remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), port);
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
     * Main processing workflow.
     * General scheme:
     *  1. prepares Http GET request and {@link org.wlou.jbdownloader.lib.AsyncTools.NetworkOperationContext};
     *  2. sets up sequence of callback handlers of Http HEAD response, simplified logic is as follows:
     *     send request -> set of reads -> finalize the download (set status, release buffers);
     *  3. runs Http GET request asynchronously.
     * @param download The download to initialize
     * @throws IOException when
     *          {@link InetAddress#getByName(String)} or
     *          {@link AsynchronousSocketChannel#open()} throw
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
        int port = what.getPort() == -1 ? HttpTools.DEFAULT_PORT : what.getPort();
        SocketAddress remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), port);

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
            LOG.debug(String.format("Reading \"%s\" channel is completed", what));
            synchronized (download) {
                if (download.getCurrentStatus() == Download.Status.DOWNLOADING) {
                    LOG.debug(String.format("Successfully finalizing \"%s\"", what));
                    download.setCurrentStatus(Download.Status.DOWNLOADED, DownloadTools.SUCCESSFUL_COMPLETED_MESSAGE);
                }
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
        onReceived.setLog(LOG);

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

    private final List<Download> tasks;
    private final AsynchronousChannelGroup channels;
    private final Map<String, String> httpParams;
}
