package org.wlou.jbdownloader;

import org.apache.log4j.Logger;
import org.wlou.jbdownloader.http.Http;
import org.wlou.jbdownloader.http.HttpHead;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;


/**
 *
 */
public class Downloader implements Runnable {

    private static Logger LOG = Logger.getLogger(Downloader.class.getName());

    public Downloader(ConcurrentLinkedQueue<Download> tasks) {
        stop = false;
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
                    switch (download.CurrentStatus.get()) {
                        case NEW:
                            initialize(download);
                            hasWork = true;
                            break;
                        case ACTIVE:
                            hasWork = process(download);
                            break;
                        case INIT:
                        case PAUSED:
                        case COMPLETED:
                        case ERROR:
                    }
                }
                if (!hasWork)
                    tasks.wait();
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
        while (Download.Status.NEW == download.CurrentStatus.get())
            acquired = download.CurrentStatus.compareAndSet(Download.Status.NEW, Download.Status.INIT);

        if (!acquired)
            return;

        // Prepare endpoint parameters.
        final URL what = download.getWhat();
        SocketAddress remote = new InetSocketAddress(InetAddress.getByName(what.getHost()), Http.DEFAULT_PORT);

        // Prepare context.
        AsyncTools.NetworkOperationContext context = new AsyncTools.NetworkOperationContext(
            AsynchronousSocketChannel.open(),
            HttpHead.makeRequest(what),
            ByteBuffer.allocate(1024) // FIXME: for now only short headers are supported
        );

        // Prepare asynchronous initialization workflow.
        // [Connect] -> [Send HEAD request] -> [Handle HEAD response]
        // The process based on callbacks, so define them in the reversed order.

        BiConsumer<Throwable, AsyncTools.NetworkOperationContext> onError = (exc, ctx) -> {
            try {
                LOG.error(exc);
                DownloadTools.setFailed(download, DownloadTools.INIT_ERROR_MESSAGE);
                ctx.close();
            } catch (IOException ignored) {} // Ignore ctx.close exception
        };

        CompletionHandler<Integer, AsyncTools.NetworkOperationContext> onReceived = AsyncTools.handlerFrom(
            (red, ctx) -> {
                try{
                    String responseString = ctx.responseString(red, Http.DEFAULT_CONTENT_CHARSET);
                    LOG.debug(String.format("Head response is received: [%s]", responseString));
                    DownloadTools.prepareDownload(download, HttpHead.parseResponse(responseString));
                    synchronized (tasks) { tasks.notify(); }
                    ctx.close();
                } catch (IOException ignored) {} // Ignore ctx.close exception
                  catch (Exception e) {
                      onError.accept(e, ctx);
                  }
            },
            onError
        );

        CompletionHandler<Integer, AsyncTools.NetworkOperationContext> onSent = AsyncTools.handlerFrom(
            (written, ctx) -> {
                assert ctx.requestBytes(Http.DEFAULT_CONTENT_CHARSET).limit() == written;
                LOG.debug(String.format("Request of %d bytes is sent", written));
                LOG.debug("Start reading response");
                ctx.Channel.read(ctx.ResponseBytes, ctx, onReceived);
            },
            onError
        );

        CompletionHandler<Void, AsyncTools.NetworkOperationContext> onConnect = AsyncTools.handlerFrom(
            (stub1, ctx) -> {
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
     * @return
     */
    public boolean process(Download download) {
        return false;
    }

    private volatile boolean stop;
    private final ConcurrentLinkedQueue<Download> tasks;
}
