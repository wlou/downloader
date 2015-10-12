package org.wlou.jbdownloader;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

public final class DownloadTools {
    private DownloadTools() {}

    public static class DownloadOutputBuffersIterator implements Iterator<ByteBuffer> {

        public DownloadOutputBuffersIterator(Download source) {
            this.source = source;
            this.next = new AtomicReference<>();
            if (this.source != null)
                next.set(source.nextOutputBuffer());
        }

        @Override
        public boolean hasNext() {
            return next.compareAndSet(null, null);
        }

        @Override
        public ByteBuffer next() {
            if (source != null) {
                return next.getAndSet(source.nextOutputBuffer());
            }
            return null;
        }

        private final Download source;
        private final AtomicReference<ByteBuffer> next;
    }
}
