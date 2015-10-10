package org.wlou.jbdownloader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Download {

    public enum Status
    {
        NEW,
        INIT,
        ACTIVE,
        PAUSED,
        COMPLETED,
        ERROR
    }

    public final AtomicReference<Status> CurrentStatus;

    public volatile String Information;
    public volatile Integer HttpStatusCode;

    public Download(URL what, Path where) throws IOException {
        CurrentStatus = new AtomicReference<>(Status.NEW);
        this.what = what;
        this.where = where;
        fout = new RandomAccessFile(this.where.toFile(), "rw");
        offset = new AtomicInteger(0);
        length = 0;
    }

    public URL getWhat() {
        return what;
    }

    public Path getWhere() {
        return where;
    }

    public void prepareOutput(int length) throws IOException {
        this.length = length;
        this.fout.setLength(this.length);
        this.buffer = fout.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, this.length);
    }

    public ByteBuffer nextOutputBuffer() {
        int size = 100 * 1024; // TODO: 100 kb - default buffer size

        int newOffset = offset.addAndGet(size);
        int oldOffset = newOffset - size;
        if (length < oldOffset)
            return null;
        if (length < newOffset)
            size = length - oldOffset;

        ByteBuffer result = buffer.slice();
        result.position(oldOffset);
        result.limit(size);
        return result;
    }

    private final URL what;
    private final Path where;
    private final RandomAccessFile fout;
    private MappedByteBuffer buffer;
    private final AtomicInteger offset;
    private int length;
}
