package org.wlou.jbdownloader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.function.BiConsumer;

public final class AsyncTools {
    private AsyncTools(){}

    public static class NetworkOperationContext implements AutoCloseable
    {
        public NetworkOperationContext(AsynchronousSocketChannel channel, ByteBuffer requestBytes, ByteBuffer responseBytes) {
            Channel = channel;
            RequestBytes = requestBytes;
            ResponseBytes = responseBytes;
        }

        public final AsynchronousSocketChannel Channel;
        public final ByteBuffer RequestBytes;
        public final ByteBuffer ResponseBytes;

        @Override
        public void close() throws IOException {
            Channel.close();
        }
    }

    public static <V, A> CompletionHandler<V, A> handlerFrom(BiConsumer<V, A> regular, BiConsumer<Throwable, A> exceptional) {
        return new CompletionHandler<V, A>() {
            @Override
            public void completed(V result, A attachment) {
                regular.accept(result, attachment);
            }

            @Override
            public void failed(Throwable exc, A attachment) {
                exceptional.accept(exc, attachment);
            }
        };
    }
}
