package org.wlou.jbdownloader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.Charset;
import java.util.function.BiConsumer;

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
}
