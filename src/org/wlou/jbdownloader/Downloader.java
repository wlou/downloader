package org.wlou.jbdownloader;

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
import java.nio.charset.Charset;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;


public class Downloader implements Runnable {

    public static final String INIT_ERROR_MESSAGE = "Error was occurred during initialization";
    public static final String PROC_ERROR_MESSAGE = "Error was occurred during downloading";
    public static final String SUCCESSFULL_INITIALIZED_MESSAGE = "Download is processing";
    public static final String SUCCESSFULL_COMPLETED_MESSAGE = "Download is successfully completed";
    public static final String PAUSED_MESSAGE = "Download is paused";

    public Downloader(ConcurrentLinkedQueue<Download> tasks) {
        stop = false;
        this.tasks = tasks;
    }

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
        } catch (Exception e) {
            //TODO: log error
        }
    }

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
        String requestStr = HttpHead.makeRequest(what);
        AsyncTools.NetworkOperationContext context = new AsyncTools.NetworkOperationContext(
            AsynchronousSocketChannel.open(),
            ByteBuffer.wrap(requestStr.getBytes(Charset.forName(Http.DEFAULT_CONTENT_CHARSET))),
            ByteBuffer.allocateDirect(1024)
        );

        // Prepare asynchronous initialization workflow.
        // [Connect] -> [Send HEAD request] -> [Handle HEAD response]
        // The process based on callbacks, so define them in the reversed order.

        BiConsumer<Throwable, AsyncTools.NetworkOperationContext> onError = (exc, ctx) -> {
            try {
                //TODO: log error
                ctx.close();
                download.CurrentStatus.set(Download.Status.ERROR);
                download.Information.set(INIT_ERROR_MESSAGE);
            } catch (IOException ignored) {} // Ignore ctx.close exception
        };

        CompletionHandler<Integer, AsyncTools.NetworkOperationContext> onReceived = AsyncTools.handlerFrom(
            (red, ctx) -> {
                try{
                    assert red == -1;
                    ctx.close();
                    //TODO: make initialization
                    download.Information.set(SUCCESSFULL_INITIALIZED_MESSAGE);
                    download.CurrentStatus.set(Download.Status.ACTIVE);
                    synchronized (tasks) { tasks.notifyAll(); }
                } catch (IOException ignored) {} // Ignore ctx.close exception
                  catch (Exception e) {
                      onError.accept(e, ctx);
                  }
            },
            onError
        );

        CompletionHandler<Integer, AsyncTools.NetworkOperationContext> onSent = AsyncTools.handlerFrom(
            (written, ctx) -> {
                assert ctx.RequestBytes.limit() == written;
                ctx.Channel.read(ctx.ResponseBytes, ctx, onReceived);
            },
            onError
        );

        CompletionHandler<Void, AsyncTools.NetworkOperationContext> onConnect = AsyncTools.handlerFrom(
            (stub1, ctx) -> ctx.Channel.write(ctx.RequestBytes, ctx, onSent),
            onError
        );

        // Start the workflow.
        context.Channel.connect(remote, context, onConnect);
    }

    private boolean process(Download download) {
        return false;
    }

    private volatile boolean stop;
    private final ConcurrentLinkedQueue<Download> tasks;
}
