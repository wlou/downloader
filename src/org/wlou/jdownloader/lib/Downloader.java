package org.wlou.jdownloader.lib;

import org.apache.log4j.Logger;
import org.wlou.jdownloader.lib.AsyncTools.NetworkOperationContext;
import org.wlou.jdownloader.lib.AsyncTools.OutputBuffersCollector;

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
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/**
 * The dispatcher object.
 * The main activity is scanning the downloading queue and calling appropriate handlers:
 *  - initialize for new downloads (see {@link org.wlou.jdownloader.lib.Download.Status#NEW}
 *  - process for initialized downloads (see {@link org.wlou.jdownloader.lib.Download.Status#INITIALIZED}
 */
public class Downloader implements Runnable {

    private static Logger LOG = Logger.getLogger(Downloader.class.getName());

    /**
     * The download and its' additional information for tacking during downloading process
     */
    public static class DownloaderContext {
        /**
         * @param target the download
         * @param opName the name of the operation the context will be created for
         */
        public DownloaderContext(Download target, String opName) {
            assert target != null;
            assert opName != null && !opName.isEmpty();

            Target = target;
            OperationName = opName;
            OperationInfo = String.format("[%s:%x]", OperationName, DownloadTools.hash(Target));
            LOG.info(String.format("DownloaderContext %s: \"%s\" -> \"%s\"", OperationInfo, Target.getWhat(), Target.getWhere()));
        }

        /**
         * Reference to the download provided in the {@link #DownloaderContext(Download, String)}
         */
        public final Download Target;
        /**
         * Reference to the operation name provided in the {@link #DownloaderContext(Download, String)}
         */
        public final String OperationName;
        /**
         * Synthetic field that uniquely identifies downloading step
         */
        public final String OperationInfo;
    }

    /**
     * Initializes downloading queue and worker-treads
     * @param downloads The downloading queue.
     * @param executors The worker-threads.
     * @throws IOException when {@link AsynchronousChannelGroup#withThreadPool(ExecutorService)} throws
     */
    public Downloader(ConcurrentLinkedQueue<Download> downloads, ExecutorService executors) throws IOException {
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
        while (true) {
            try {
                synchronized (mutex) {
                    dispatch();
                    while (!hasWork) {
                        mutex.wait();
                    }
                }
            }
            catch (InterruptedException e) {
                break;
            }
            catch (Exception e) {
                LOG.error(e);
            }
        }
    }

    private void dispatch() {
        int tasksToDispatch = 0;
        for (Download download : tasks) {
            switch (download.getCurrentStatus()) {
                case NEW: {
                    DownloaderContext dc = new DownloaderContext(download, "initialize");
                    initialize(dc);
                    ++tasksToDispatch;
                    break;
                }
                case INITIALIZING:
                    ++tasksToDispatch;
                    break;
                case INITIALIZED: {
                    DownloaderContext dc = new DownloaderContext(download, "process");
                    process(dc);
                    break;
                }
            }
        }
        hasWork = tasksToDispatch > 0;
    }

    /**
     * Force to make one iteration of dispatching loop
     */
    public void checkForNewTasks() {
        synchronized (mutex) {
            hasWork = true;
            mutex.notifyAll();
        }
    }

    /**
     * Main initialization workflow.
     * General scheme:
     *  1. checks {@link Download}'s source domain for availability;
     *  2. prepares Http HEAD request and {@link NetworkOperationContext};
     *  3. sets up sequence of callback handlers of Http HEAD response, simplified logic is as follows:
     *     send request -> read response -> call {@link Download#completeInitialization(String, String)};
     *  4. runs Http HEAD request asynchronously.
     * @param dc The downloader context for initialize operation
     */
    public void initialize(DownloaderContext dc)  {
        assert dc != null && dc.Target != null;
        assert dc.OperationName.equals("initialize");

        final BiConsumer<Throwable, NetworkOperationContext> initErrorHandler = (exc, nc) ->
            onDownloaderError(dc, nc, DownloadTools.INIT_ERROR_MESSAGE, exc);

        // 1. Trying to acquire download and start initialization.
        if (!dc.Target.lockForInitialization())
            return; // Somebody else blocked this try.

        // 2. Here we've acquired exclusive initialization rights.
        //    Start initialization from preparing endpoint parameters.
        final URL what = dc.Target.getWhat();
        SocketAddress remote;
        try {
            int port = what.getPort() == -1 ? HttpTools.DEFAULT_PORT : what.getPort();
            remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), port);
        }
        catch (Exception exc) {
            initErrorHandler.accept(exc, null);
            return;
        }

        // 3. Remote host is successfully resolved.
        //    Prepare network context.
        NetworkOperationContext networkContext;
        OutputBuffersCollector responseCollector = new OutputBuffersCollector(1024);
        try {
            networkContext = new NetworkOperationContext(
                 dc.OperationInfo,
                 AsynchronousSocketChannel.open(channels),
                 ByteBuffer.wrap(HttpTools.makeHeadRequest(what, httpParams).getBytes()),
                 responseCollector.next()
             );
        } catch (Exception exc) {
            initErrorHandler.accept(exc, null);
            return;
        }

        // 4.  Prepare asynchronous initialization workflow.
        //     [Connect] -> [Send HEAD request] -> [Read .. read ..] -> [Handle HEAD response]
        //     The process based on callbacks, so define them in the reversed order.
        // 4.1 Let's start with the last stage: "we've already read some portion of data (may be last)"
        //     There is a AsyncTools.ChannelReader for handling such situation.
        final AsyncTools.ChannelReader reader = new AsyncTools.ChannelReader(
            responseCollector,
            () -> DownloadTools.canProceedInitialization(dc.Target),
            (read, nc) -> onInitResponded(dc, nc, responseCollector),
            initErrorHandler
        );
        reader.setLog(LOG);

        // 4.2 Now we need writer for sending the request
        //     The AsyncTools.ChannelWriter appropriates for this.
        final AsyncTools.ChannelWriter writer = new AsyncTools.ChannelWriter(
            () -> DownloadTools.canProceedInitialization(dc.Target),
            (written, nc) -> {
                LOG.info(String.format("%s request \"%s\" is sent", dc.OperationInfo,
                    AsyncTools.extractString(nc.RequestBytes, HttpTools.DEFAULT_CONTENT_CHARSET)));
                nc.Channel.read(nc.ResponseBytes, nc, reader);
            },
            initErrorHandler
        );
        writer.setLog(LOG);

        // 4.3 Connection handler is the starting point in the workflow.
        final CompletionHandler<Void, NetworkOperationContext> onConnect = AsyncTools.handlerFrom(
            (stub, nc) -> {
                LOG.info(String.format("%s domain \"%s\" connected", dc.OperationInfo, what.getHost()));
                nc.Channel.write(nc.RequestBytes, nc, writer);
            },
            initErrorHandler
        );

        // 5. Start the workflow.
        LOG.info(String.format("%s start workflow", dc.OperationInfo));
        networkContext.Channel.connect(remote, networkContext, onConnect);
    }

    /**
     * Main processing workflow.
     * General scheme:
     *  1. prepares Http GET request and {@link NetworkOperationContext};
     *  2. sets up sequence of callback handlers of Http GET response, simplified logic is as follows:
     *     send request -> set of reads -> finalize the download (set status, release buffers);
     *  3. runs Http GET request asynchronously.
     * @param dc The download to initialize
     */
    public void process(DownloaderContext dc) {
        assert dc != null && dc.Target != null;
        assert dc.OperationName.equals("process");

        final BiConsumer<Throwable, NetworkOperationContext> procErrorHandler = (exc, nc) ->
            onDownloaderError(dc, nc, DownloadTools.PROC_ERROR_MESSAGE, exc);

        // 1. Trying to acquire download and start processing.
        if (!dc.Target.lockForProcessing())
            return; // Somebody else blocked this try.

        // 2. Here we've acquired exclusive processing rights.
        //    Start processing from preparing endpoint.
        final URL what = dc.Target.getWhat();
        SocketAddress remote;
        try {
            int port = what.getPort() == -1 ? HttpTools.DEFAULT_PORT : what.getPort();
            remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), port);
        } catch (Exception exc) {
            procErrorHandler.accept(exc, null);
            return;
        }

        // 3. Remote host is successfully resolved.
        //    Prepare network context.
        NetworkOperationContext networkContext;
        try {
            networkContext = new NetworkOperationContext(
                dc.OperationInfo,
                AsynchronousSocketChannel.open(channels),
                ByteBuffer.wrap(HttpTools.makeGetRequest(what, httpParams).getBytes(HttpTools.DEFAULT_CONTENT_CHARSET)),
                dc.Target.nextOutputBuffer()
            );
        } catch (Exception exc) {
            procErrorHandler.accept(exc, null);
            return;
        }

        // 4.  Prepare asynchronous download workflow.
        //     The process based on callbacks, so define them in the reversed order.
        //     [Connect] -> [Send Get request] -> [Read portion1] -> [Read portion2] ... -> [Complete]
        // 4.1 Let's start with the last stage: "we've already read some portion of data (may be last)"
        //     There is a AsyncTools.ChannelReader for handling such situation.
        final AsyncTools.ChannelReader reader = new AsyncTools.ChannelReader(
            new DownloadTools.DownloadOutputBuffersIterator(dc.Target),
            () -> DownloadTools.canProceedProcessing(dc.Target),
            (read, nc) -> onProcResponded(dc, nc),
            procErrorHandler
        );
        reader.setLog(LOG);

        // 4.2 Before reading we need to send request for the data
        //     We have AsyncTools.ChannelWriter for that
        final AsyncTools.ChannelWriter writer = new AsyncTools.ChannelWriter(
            () -> DownloadTools.canProceedProcessing(dc.Target),
            (written, nc) -> {
                LOG.info(String.format("%s request \"%s\" is sent", dc.OperationInfo,
                    AsyncTools.extractString(nc.RequestBytes, HttpTools.DEFAULT_CONTENT_CHARSET)));
                nc.Channel.read(nc.ResponseBytes, nc, reader);
            },
            procErrorHandler
        );
        writer.setLog(LOG);

        // 4.3 To Send the request we need to connect to the remote host
        final CompletionHandler<Void, NetworkOperationContext> onConnect = AsyncTools.handlerFrom(
            (stub, nc) -> {
                LOG.info(String.format("%s domain \"%s\" connected", dc.OperationInfo, what.getHost()));
                nc.Channel.write(nc.RequestBytes, nc, writer);
            },
            procErrorHandler
        );

        // 5. All tings prepared.
        //    Start the workflow.
        LOG.info(String.format("%s start workflow", dc.OperationInfo));
        networkContext.Channel.connect(remote, networkContext, onConnect);
    }

    private void onDownloaderError(DownloaderContext dc, NetworkOperationContext nc, String status, Throwable exc) {
        assert dc != null && dc.Target != null;
        LOG.error(String.format("%s reason: %s", dc.OperationInfo, exc));
        dc.Target.interruptExceptionally(status);
        if (nc != null)
            nc.close();

    }

    private void onInitResponded(DownloaderContext dc, NetworkOperationContext nc, OutputBuffersCollector collector) {
        assert dc != null && dc.Target != null;
        assert collector != null;

        LOG.info(String.format("%s last read operation", dc.OperationInfo));
        if (nc != null)
            nc.close();
        byte[] response = collector.getCollectedBytes();
        String headers = new String(response, Charset.forName(HttpTools.DEFAULT_CONTENT_CHARSET));
        LOG.info(String.format("%s response content: \"%s\"", dc.OperationInfo, headers));

        dc.Target.completeInitialization(headers, HttpTools.DEFAULT_CONTENT_CHARSET);
        Throwable result = dc.Target.getLastError();
        if (result != null) {
            LOG.error(String.format("%s failed to complete", dc.OperationInfo));
            onDownloaderError(dc, nc, DownloadTools.INIT_ERROR_MESSAGE, result);
            return;
        }
        LOG.info(String.format("%s completed", dc.OperationInfo));
    }

    private void onProcResponded(DownloaderContext dc, NetworkOperationContext nc) {
        assert dc != null && dc.Target != null;

        LOG.info(String.format("%s last read operation", dc.OperationInfo));
        if (nc != null)
            nc.close();
        dc.Target.completeProcessing();
        Throwable result = dc.Target.getLastError();
        if (result != null) {
            LOG.error(String.format("%s failed to complete", dc.OperationInfo));
            onDownloaderError(dc, nc, DownloadTools.INIT_ERROR_MESSAGE, result);
            return;
        }
        LOG.info(String.format("%s completed", dc.OperationInfo));

    }

    private final ConcurrentLinkedQueue<Download> tasks;
    private final AsynchronousChannelGroup channels;
    private final Map<String, String> httpParams;

    private final Object mutex = new Object();
    private volatile boolean hasWork = true;
}
