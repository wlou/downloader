package org.wlou.jbdownloader.lib;

import org.apache.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.Channels;
import java.nio.channels.CompletionHandler;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class AsyncTools {
    private AsyncTools(){}

    /** Auxiliary method for converting {@link ByteBuffer} to String
     * @param buffer The buffer with bytes.
     * @param charset The encoding for representing bytes as character sequence.
     * @return String equivalence of <code>buffer</code> encoded with <code>charset</code>.
     */
    public static String extractString(ByteBuffer buffer, String charset) {
        buffer.flip();
        byte[] bytes = new byte[buffer.limit()];
        buffer.get(bytes);
        return new String(bytes, Charset.forName(charset));
    }

    /**
     * Intends to keep state of a network operation:
     *  - sending request
     *  - reading response
     */
    public static class NetworkOperationContext implements AutoCloseable
    {
        /**
         * Wraps required object into the context.
         * @param channel The channel throughout operation is performed.
         * @param requestBytes Buffer of request (http request for example).
         * @param responseBytes Buffer for keeping response.
         */
        public NetworkOperationContext(String opInfo, AsynchronousSocketChannel channel,
                                       ByteBuffer requestBytes, ByteBuffer responseBytes) {
            Channel = channel;
            RequestBytes = requestBytes;
            ResponseBytes = responseBytes;
            OperationInfo = opInfo;
        }

        /**
         * The operation is performed throughout this <code>Channel</code>.
         */
        public final AsynchronousSocketChannel Channel;
        /**
         * String to send via the {@link #Channel} if the operation is a request operation.
         */
        public final ByteBuffer RequestBytes;
        /**
         * Byte buffer to keep response read from the {@link #Channel} if the operation is a receive operation.
         */
        public final ByteBuffer ResponseBytes;
        /**
         * Additional info for logging purposes
         */
        public final String OperationInfo;

        /**
         * Closes associated {@link #Channel}
         */
        @Override
        public void close() {
            try {
                Channel.close();
            }
            catch (IOException ignored) {}
        }
    }

    /**
     * Auxiliary function to construct {@link CompletionHandler} required by the most
     * {@link java.nio.channels.AsynchronousChannel} operations from {@link BiConsumer}s.
     * @param regular The handler of successfully completed read/write operation.
     * @param exceptional The error handler.
     * @param <V> The type of the base operation's argument.
     * @param <A> The type of  the additional operation's argument.
     * @return The implementation of {@link CompletionHandler} which calls
     *          <code>regular</code> on succeed and
     *          <code>exceptional</code> on error.
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

    /**
     * Encapsulates the sequential reading logic for an asynchronous operation.
     * Handles data portion by portion and provides interface for interrupting the reading process.
     * Acts as following:
     *  1. receives result of previously initiated async reading
     *  2. checks continuation conditions
     *  3. stops or restarts async reading
     */
    public static class ChannelReader implements CompletionHandler<Integer, NetworkOperationContext> {

        /**
         * Constructs reader from it's parts
         * @param buffers is needed for keeping response
         * @param proceedReading is a callback to ask parent should the reader continue
         * @param completionHandler is a handler to call when reading has completed (all done or interrupt)
         * @param errorHandler is a reading error handler
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

        /**
         * See {@link CompletionHandler#completed(Object, Object)}
         * @param read The result of the I/O operation.
         * @param ctx The context of this reading operation.
         */
        @Override
        public void completed(Integer read, NetworkOperationContext ctx) {
            try {
                log.debug(String.format("%s Response portion has been received (%d bytes)", ctx.OperationInfo, read));
                if (proceedReading != null && !proceedReading.get()) {
                    log.info(String.format("%s Reader has been interrupted", ctx.OperationInfo));
                    runCompletionHandler(read, ctx);
                    return;
                }
                if (!ctx.ResponseBytes.hasRemaining())
                    ctx = new NetworkOperationContext(ctx.OperationInfo, ctx.Channel, ctx.RequestBytes, buffers.next());
                if (read == -1 || ctx.ResponseBytes == null) {
                    log.info(String.format("%s Completing reader cleanly", ctx.OperationInfo));
                    runCompletionHandler(read, ctx);
                    return;
                }
                log.debug(String.format("%s Continue reading response", ctx.OperationInfo));
                ctx.Channel.read(ctx.ResponseBytes, ctx, this);
            }
            catch (Exception e) {
                failed(e, ctx);
            }
        }

        private void runCompletionHandler(Integer read, NetworkOperationContext ctx) {
            if (completionHandler != null)
                completionHandler.accept(read, ctx);
        }

        /**
         * See {@link CompletionHandler#failed(Throwable, Object)}
         * @param exc The exception to indicate why the I/O operation failed
         * @param ctx The context of this reading operation.
         */
        @Override
        public void failed(Throwable exc, NetworkOperationContext ctx) {
            if (errorHandler != null)
                errorHandler.accept(exc, ctx);
        }

        /**
         * Sets logger for the reader.
         * @param log The logger to set.
         */
        public void setLog(Logger log) {
            this.log = log;
        }

        private final Iterator<ByteBuffer> buffers;
        private final BiConsumer<Integer, NetworkOperationContext> completionHandler;
        private final BiConsumer<Throwable, NetworkOperationContext> errorHandler;
        private final Supplier<Boolean> proceedReading;
        private volatile Logger log;
    }

    /**
     * Encapsulates the sequential writing logic for an asynchronous operation.
     * Similar to {@link org.wlou.jbdownloader.lib.AsyncTools.ChannelReader}
     */
    public static class ChannelWriter implements CompletionHandler<Integer, NetworkOperationContext> {
        public ChannelWriter(Supplier<Boolean> proceedWriting,
                             BiConsumer<Integer, NetworkOperationContext> completionHandler,
                             BiConsumer<Throwable, NetworkOperationContext> errorHandler) {
            this.proceedWriting = proceedWriting;
            this.errorHandler = errorHandler;
            this.completionHandler = completionHandler;
            log = Logger.getLogger(getClass());
        }

        /**
         * See {@link CompletionHandler#completed(Object, Object)}
         * @param written The result of the I/O operation.
         * @param ctx The context of this reading operation.
         */
        @Override
        public void completed(Integer written, NetworkOperationContext ctx) {
            try {
                log.debug(String.format("%s Request portion has been written (%d bytes)", ctx.OperationInfo, written));
                if (proceedWriting != null && !proceedWriting.get()) {
                    log.info(String.format("%s Writer has been interrupted", ctx.OperationInfo));
                    runCompletionHandler(written, ctx);
                    return;
                }
                if (written == 0 || ctx.RequestBytes == null) {
                    log.info(String.format("%s Completing writer cleanly", ctx.OperationInfo));
                    runCompletionHandler(written, ctx);
                    return;
                }
                log.debug(String.format("%s Continue writing response", ctx.OperationInfo));
                ctx.Channel.write(ctx.RequestBytes, ctx, this);
            }
            catch (Exception e) {
                failed(e, ctx);
            }
        }

        private void runCompletionHandler(Integer read, NetworkOperationContext ctx) {
            if (completionHandler != null)
                completionHandler.accept(read, ctx);
        }

        /**
         * See {@link CompletionHandler#failed(Throwable, Object)}
         * @param exc The exception to indicate why the I/O operation failed
         * @param ctx The context of this reading operation.
         */
        @Override
        public void failed(Throwable exc, NetworkOperationContext ctx) {
            if (errorHandler != null)
                errorHandler.accept(exc, ctx);
        }

        /**
         * Sets logger for the writer.
         * @param log The logger to set.
         */
        public void setLog(Logger log) {
            this.log = log;
        }

        private final BiConsumer<Integer, NetworkOperationContext> completionHandler;
        private final BiConsumer<Throwable, NetworkOperationContext> errorHandler;
        private final Supplier<Boolean> proceedWriting;
        private volatile Logger log;
    }

    /**
     * Provides "infinite" sequence of {@link ByteBuffer}s.
     */
    public static class OutputBuffersCollector implements Iterator<ByteBuffer> {
        OutputBuffersCollector(int size) {
            buffer = ByteBuffer.allocate(size);
            storage = new ByteArrayOutputStream(size);
            channel = Channels.newChannel(storage);
        }

        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public ByteBuffer next() {
            return flushBuffer();
        }

        public byte[] getCollectedBytes() {
            flushBuffer();
            return storage.toByteArray();
        }

        private ByteBuffer flushBuffer() {
            try {
                buffer.flip();
                channel.write(buffer);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return buffer.compact();
        }

        private final ByteBuffer buffer;
        private final ByteArrayOutputStream storage;
        private final WritableByteChannel channel;
    }
}
