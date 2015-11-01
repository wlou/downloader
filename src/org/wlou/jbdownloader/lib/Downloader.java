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
import java.nio.charset.Charset;
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
        httpParams.put(HttpTools.CONNECTION_DIRECTIVE, HttpTools.CONNECTION_CLOSE);
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
     *     send request -> read response -> call {@link #completeInitialization(Download, String, String)};
     *  4. runs Http HEAD request asynchronously.
     * @param download The download to initialize
     * @throws IOException when {@link AsynchronousSocketChannel#open()} throws
     */
    public void initialize(Download download) throws IOException {
        // 1. Trying to acquire download and start initialization.
        if (!startInitialization(download))
            return; // Somebody else blocked this try.

        LOG.info(String.format("Start download [%s -> %s : %x] initialization",
                download.getWhat(), download.getWhere(), DownloadTools.hash(download)));

        // 2. Here we've acquired exclusive initialization rights.
        //    Start the process.
        //    Prepare endpoint parameters.
        final URL what = download.getWhat();
        SocketAddress remote;
        try {
            int port = what.getPort() == -1 ? HttpTools.DEFAULT_PORT : what.getPort();
            remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), port);
        }
        catch (Exception exc) {
            LOG.error("Initialization error: " + exc.toString());
            interruptExceptionally(download, DownloadTools.INIT_ERROR_MESSAGE);
            return;
        }

        // 3. Remote host is successfully resolved.
        //    Prepare context.
        AsyncTools.OutputBuffersCollector responseCollector = new AsyncTools.OutputBuffersCollector(1024);
        AsyncTools.NetworkOperationContext context = new AsyncTools.NetworkOperationContext(
            "initialize",
            DownloadTools.hash(download),
            AsynchronousSocketChannel.open(channels),
            ByteBuffer.wrap(HttpTools.makeHeadRequest(what, httpParams).getBytes()),
            responseCollector.next()
        );

        // 4. Prepare asynchronous initialization workflow.
        //    [Connect] -> [Send HEAD request] -> [Read .. read ..] -> [Handle HEAD response]
        //    The process based on callbacks, so define them in the reversed order.
        BiConsumer<Throwable, AsyncTools.NetworkOperationContext> initErrorHandler = (exc, ctx) -> {
            LOG.error(String.format("%s Initialization error: %s", AsyncTools.getOpInfo(ctx), exc.toString()));
            interruptExceptionally(download, DownloadTools.INIT_ERROR_MESSAGE);
            ctx.close();
        };

        // 4.1 Let's start with the last stage: "we've already read some portion of data (may be last)"
        //     There is a AsyncTools.ChannelReader for handling such situation.
        final AsyncTools.ChannelReader reader = new AsyncTools.ChannelReader(
            responseCollector,
            () -> DownloadTools.canProceedInitialization(download),
            (read, ctx) -> {
                byte[] response = responseCollector.getCollectedBytes();
                String headers = new String(response, Charset.forName(HttpTools.DEFAULT_CONTENT_CHARSET));
                LOG.info(String.format("%s Head response has been received: \"%s\"", AsyncTools.getOpInfo(ctx), headers));
                completeInitialization(download, headers, AsyncTools.getOpInfo(ctx));
                synchronized (tasks) { tasks.notifyAll(); }
                ctx.close();
            },
            initErrorHandler
        );
        reader.setLog(LOG);

        // 4.2 Now we need writer for sending the request
        //     There is a AsyncTools.ChannelWriter is appropriate for this.
        final AsyncTools.ChannelWriter writer = new AsyncTools.ChannelWriter(
            () -> DownloadTools.canProceedInitialization(download),
            (written, ctx) -> {
                LOG.info(String.format("%s Request \"%s\" is sent",
                    AsyncTools.getOpInfo(ctx),
                    AsyncTools.extractString(ctx.RequestBytes, HttpTools.DEFAULT_CONTENT_CHARSET)));
                ctx.Channel.read(ctx.ResponseBytes, ctx, reader);
            },
            initErrorHandler
        );
        writer.setLog(LOG);

        // 4.3 Connection handler is the starting point in the workflow.
        final CompletionHandler<Void, AsyncTools.NetworkOperationContext> onConnect = AsyncTools.handlerFrom(
            (stub, ctx) -> {
                LOG.info(String.format("%s Domain \"%s\" connected", AsyncTools.getOpInfo(ctx), what.getHost()));
                ctx.Channel.write(ctx.RequestBytes, ctx, writer);
            },
            initErrorHandler
        );

        // 5. Start the workflow.
        LOG.info(String.format("%s Start initialization workflow", AsyncTools.getOpInfo(context)));
        context.Channel.connect(remote, context, onConnect);
    }

    /**
     * Main processing workflow.
     * General scheme:
     *  1. prepares Http GET request and {@link org.wlou.jbdownloader.lib.AsyncTools.NetworkOperationContext};
     *  2. sets up sequence of callback handlers of Http GET response, simplified logic is as follows:
     *     send request -> set of reads -> finalize the download (set status, release buffers);
     *  3. runs Http GET request asynchronously.
     * @param download The download to initialize
     * @throws IOException when
     *          {@link InetAddress#getByName(String)} or
     *          {@link AsynchronousSocketChannel#open()} throw
     */
    public void process(Download download) throws IOException {
        // 1. Trying to acquire download and start processing.
        if (!startProcessing(download))
            return; // Somebody else blocked this try.

        LOG.info(String.format("Start download [%s -> %s : %x] processing",
                download.getWhat(), download.getWhere(), DownloadTools.hash(download)));

        // 2. Here we've acquired exclusive processing rights.
        //    Start the process.
        //    Prepare endpoint parameters.
        final URL what = download.getWhat();
        int port = what.getPort() == -1 ? HttpTools.DEFAULT_PORT : what.getPort();
        SocketAddress remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), port);

        // 3. Remote host is successfully resolved.
        //    Prepare context.
        AsyncTools.NetworkOperationContext context = new AsyncTools.NetworkOperationContext(
            "process",
            DownloadTools.hash(download),
            AsynchronousSocketChannel.open(channels),
            ByteBuffer.wrap(HttpTools.makeGetRequest(what, httpParams).getBytes(HttpTools.DEFAULT_CONTENT_CHARSET)),
            download.nextOutputBuffer()
        );

        // 4. Prepare asynchronous download workflow.
        //    The process based on callbacks, so define them in the reversed order.
        //    [Connect] -> [Send Get request] -> [Read portion1] -> [Read portion2] ... -> [Complete]
        BiConsumer<Throwable, AsyncTools.NetworkOperationContext> processErrorHandler = (exc, ctx) -> {
            LOG.error(String.format("%s Processing error: %s", AsyncTools.getOpInfo(ctx), exc.toString()));
            interruptExceptionally(download, DownloadTools.PROC_ERROR_MESSAGE);
            ctx.close();
        };

        // 4.1 Let's start with the last stage: "we've already read some portion of data (may be last)"
        //     There is a AsyncTools.ChannelReader for handling such situation.
        final AsyncTools.ChannelReader reader = new AsyncTools.ChannelReader(
            new DownloadTools.DownloadOutputBuffersIterator(download),
            () -> DownloadTools.canProceedProcessing(download),
            (read, ctx) -> {
                LOG.info(String.format("%s Last reading operation is completed", AsyncTools.getOpInfo(ctx)));
                completeProcessing(download, AsyncTools.getOpInfo(ctx));
                ctx.close();
            },
            processErrorHandler
        );
        reader.setLog(LOG);

        // 4.2 Before reading we need to send request for the data
        //     We have AsyncTools.ChannelWriter for that
        final AsyncTools.ChannelWriter writer = new AsyncTools.ChannelWriter(
            () -> DownloadTools.canProceedProcessing(download),
            (written, ctx) -> {
                LOG.info(String.format("%s Request \"%s\" is sent",
                    AsyncTools.getOpInfo(ctx),
                    AsyncTools.extractString(ctx.RequestBytes, HttpTools.DEFAULT_CONTENT_CHARSET)));
                ctx.Channel.read(ctx.ResponseBytes, ctx, reader);
            },
            processErrorHandler
        );
        writer.setLog(LOG);

        // 4.3 To Send the request we need to connect to the remote host
        final CompletionHandler<Void, AsyncTools.NetworkOperationContext> onConnect = AsyncTools.handlerFrom(
            (stub, ctx) -> {
                LOG.info(String.format("%s Domain \"%s\" connected", AsyncTools.getOpInfo(ctx), what.getHost()));
                ctx.Channel.write(ctx.RequestBytes, ctx, writer);
            },
            processErrorHandler
        );

        // 5. All tings prepared.
        //    Start the workflow.
        LOG.info(String.format("%s Start processing workflow", AsyncTools.getOpInfo(context)));
        context.Channel.connect(remote, context, onConnect);
    }

    private boolean startInitialization(Download download) {
        synchronized (download) {
            if (download.getCurrentStatus() != Download.Status.NEW)
                return false;
            download.setCurrentStatus(Download.Status.INITIALIZING, DownloadTools.INITIALIZING_MESSAGE);
            download.notifyAll();
        }
        return true;
    }

    private void completeInitialization(Download download, String headers, String opInfo) {
        synchronized (download) {
            if (download.getCurrentStatus() != Download.Status.INITIALIZING)
                return;
            try {
                DownloadTools.prepareDownload(download, headers);
            } catch (Exception exc) {
                LOG.error(String.format("%s Failed to prepare download from HEAD response: %s", opInfo, exc.toString()));
                // Reentrant synchronization from the same thread
                interruptExceptionally(download, DownloadTools.INIT_ERROR_MESSAGE);
                return;
            }
            download.setCurrentStatus(Download.Status.INITIALIZED, DownloadTools.SUCCESSFUL_INITIALIZED_MESSAGE);
            LOG.info(String.format("%s Initialization succeeded", opInfo));
            download.notifyAll();
        }
    }

    private boolean startProcessing(Download download) {
        synchronized (download) {
            if (download.getCurrentStatus() != Download.Status.INITIALIZED)
                return false;
            download.setCurrentStatus(Download.Status.DOWNLOADING, DownloadTools.SUCCESSFUL_INITIALIZED_MESSAGE);
            download.notifyAll();
        }
        return true;
    }

    private void completeProcessing(Download download, String opInfo) {
        synchronized (download) {
            if (download.getCurrentStatus() != Download.Status.DOWNLOADING)
                return;
            download.setCurrentStatus(Download.Status.DOWNLOADED, DownloadTools.SUCCESSFUL_COMPLETED_MESSAGE);
            download.releaseBuffers();
            LOG.info(String.format("%s Finalization succeeded", opInfo));
            download.notifyAll();
        }
    }

    private void interruptExceptionally(Download download, String information) {
        synchronized (download) {
            if (!DownloadTools.isActiveDownload(download))
                return;
            download.setCurrentStatus(Download.Status.ERROR, information);
            download.releaseBuffers();
            download.notifyAll();
        }
    }

    private final List<Download> tasks;
    private final AsynchronousChannelGroup channels;
    private final Map<String, String> httpParams;
}
