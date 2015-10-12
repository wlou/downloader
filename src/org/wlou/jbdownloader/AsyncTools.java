package org.wlou.jbdownloader;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class AsyncTools {
    private AsyncTools(){}

    /**
     *
     */
    public static class NetworkOperationContext implements AutoCloseable
    {
        /**
         * @param channel
         * @param requestString
         * @param responseBytes
         */
        public NetworkOperationContext(AsynchronousSocketChannel channel, String requestString, ByteBuffer responseBytes) {
            Channel = channel;
            RequestString = requestString;
            ResponseBytes = responseBytes;
        }

        /**
         *
         */
        public final AsynchronousSocketChannel Channel;
        /**
         *
         */
        public final String RequestString;
        /**
         *
         */
        public final ByteBuffer ResponseBytes;

        /**
         * @param read
         * @param charset
         * @return
         */
        public String responseString(int read, String charset) {
            assert read > 0;
            ResponseBytes.flip();
            byte[] buffer = new byte[read];
            ResponseBytes.get(buffer);
            return new String(buffer, Charset.forName(charset));
        }

        /**
         * @param charset
         * @return
         */
        public ByteBuffer requestBytes(String charset) {
            byte[] requestBytes = RequestString.getBytes(Charset.forName(charset));
            return ByteBuffer.wrap(requestBytes);
        }

        @Override
        public void close() throws IOException {
            Channel.close();
        }
    }

    /**
     * @param regular
     * @param exceptional
     * @param <V>
     * @param <A>
     * @return
     */
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

    public static class ChannelReader implements CompletionHandler<Integer, NetworkOperationContext> {

        /**
         * @param buffers
         * @param completionHandler
         * @param errorHandler
         */
        public ChannelReader(Iterator<ByteBuffer> buffers,
                             Supplier<Boolean> proceedReading,
                             BiConsumer<Integer, NetworkOperationContext> completionHandler,
                             BiConsumer<Throwable, NetworkOperationContext> errorHandler) {
            this.buffers = buffers;
            this.proceedReading = proceedReading;
            this.errorHandler = errorHandler;
            this.completionHandler = completionHandler;
            log = Logger.getLogger(getClass());
        }

        @Override
        public void completed(Integer read, NetworkOperationContext ctx) {
            log.debug(String.format("Response portion has been received (%d bytes)", read));
            if (proceedReading != null && !proceedReading.get()) {
                log.debug("Reader has been interrupted");
                return;
            }
            if (!ctx.ResponseBytes.hasRemaining())
                ctx = new NetworkOperationContext(ctx.Channel, ctx.RequestString, buffers.next());
            if (read == -1 || ctx.ResponseBytes == null) {
                log.debug("Completing reader cleanly");
                if (completionHandler != null)
                    completionHandler.accept(read, ctx);
                return;
            }
            log.debug("Continue reading response");
            ctx.Channel.read(ctx.ResponseBytes, ctx, this);
        }

        @Override
        public void failed(Throwable exc, NetworkOperationContext ctx) {
            if (errorHandler != null)
                errorHandler.accept(exc, ctx);
        }

        public void setLog(Logger log) {
            this.log = log;
        }

        private final Iterator<ByteBuffer> buffers;
        private final BiConsumer<Integer, NetworkOperationContext> completionHandler;
        private final BiConsumer<Throwable, NetworkOperationContext> errorHandler;
        private final Supplier<Boolean> proceedReading;
        private volatile Logger log;
    }
}
