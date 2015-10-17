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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/**
 *
 */
public class Downloader implements Runnable {

    private static Logger LOG = Logger.getLogger(Downloader.class.getName());

    public Downloader(List<Download> tasks, ExecutorService executors) throws IOException {
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
        final Download snap = download;

        synchronized (snap) {
            if (snap.getCurrentStatus() != Download.Status.NEW)
                return;
            snap.setCurrentStatus(Download.Status.INITIALIZING, DownloadTools.INITIALIZING_MESSAGE);
            snap.notify();
        }

        // Initialization error handler
        final BiConsumer<Throwable, AsyncTools.NetworkOperationContext> onError = (exc, ctx) -> {
            try {
                LOG.error(exc);
                synchronized (snap) {
                    snap.setCurrentStatus(Download.Status.ERROR, DownloadTools.INIT_ERROR_MESSAGE);
                    snap.notify();
                }
                if (ctx != null)
                    ctx.close();
            } catch (IOException ignored) {} // Ignore ctx.close exception
        };

        // Prepare endpoint parameters.
        final URL what = snap.getWhat();
        SocketAddress remote = null;
        try {
            remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), Http.DEFAULT_PORT);
        }
        catch (Exception e) {
            onError.accept(e, null);
            return;
        }

        // Prepare context.
        AsyncTools.NetworkOperationContext context = new AsyncTools.NetworkOperationContext(
            AsynchronousSocketChannel.open(channels),
            HttpHead.makeRequest(what),
            // FIXME: for now only short headers are supported
            ByteBuffer.allocate(1024)
        );

        // Prepare asynchronous initialization workflow.
        // [Connect] -> [Send HEAD request] -> [Handle HEAD response]
        // The process based on callbacks, so define them in the reversed order.

        // FIXME: use channel reader
        final CompletionHandler<Integer, AsyncTools.NetworkOperationContext> onReceived = AsyncTools.handlerFrom(
            (read, ctx) -> {
                String responseString = ctx.responseString(read, Http.DEFAULT_CONTENT_CHARSET);
                LOG.debug(String.format("Head response has been received: [%s]", responseString));
                try {
                    DownloadTools.prepareDownload(snap, responseString);
                }
                catch (Exception exc) {
                    onError.accept(exc, ctx);
                }
                synchronized (tasks) { tasks.notifyAll(); }
                try {
                    if (ctx != null)
                        ctx.close();
                }
                catch (IOException ignored) {}
            },
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

    /**
     * @param download
     */
    public void process(Download download) throws IOException {
        synchronized (download) {
            if (download.getCurrentStatus() != Download.Status.INITIALIZED)
                return;
            download.setCurrentStatus(Download.Status.DOWNLOADING, DownloadTools.SUCCESSFUL_INITIALIZED_MESSAGE);
            download.notify();
        }

        // Prepare endpoint parameters.
        final Download snap = download;
        final URL what = snap.getWhat();
        SocketAddress remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), Http.DEFAULT_PORT);

        // Prepare context.
        AsyncTools.NetworkOperationContext context = new AsyncTools.NetworkOperationContext(
            AsynchronousSocketChannel.open(channels),
            HttpGet.makeRequest(what),
            snap.nextOutputBuffer()
        );

        // Prepare asynchronous download workflow.
        // [Connect] -> [Send Get request] -> [Read portion1] -> [Read portion2] ... -> [Complete]
        // The process based on callbacks, so define them in the reversed order.

        final BiConsumer<Throwable, AsyncTools.NetworkOperationContext> onError = (exc, ctx) -> {
            try {
                LOG.error(exc);
                synchronized (snap) {
                    snap.setCurrentStatus(Download.Status.ERROR, DownloadTools.PROC_ERROR_MESSAGE);
                    snap.notify();
                }
                if (ctx != null)
                    ctx.close();
            } catch (IOException ignored) {} // Ignore ctx.close exception
        };

        final BiConsumer<Integer, AsyncTools.NetworkOperationContext> onCompleteReading = (read, ctx) -> {
            try {
                LOG.debug(String.format("Reading \"%s\" channel successfully completed", what));
                synchronized (snap) {
                    snap.setCurrentStatus(Download.Status.DOWNLOADED, DownloadTools.SUCCESSFUL_COMPLETED_MESSAGE);
                    snap.notify();
                }
                if (ctx != null)
                    ctx.close();
            } catch (IOException ignored) {} // Ignore ctx.close exception
        };

        final AsyncTools.ChannelReader onReceived = new AsyncTools.ChannelReader(
            new DownloadTools.DownloadOutputBuffersIterator(snap),
            () -> {
                snap.updateProgress();
                return snap.getCurrentStatus() == Download.Status.DOWNLOADING;
            },
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
    private final List<Download> tasks;
    private final AsynchronousChannelGroup channels;
}
