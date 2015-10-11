package org.wlou.jbdownloader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Download {

    public enum Status
    {
        NEW,
        INITIALIZING,
        INITIALIZED,
        DOWNLOADING,
        PAUSED,
        STOPED,
        COMPLETED,
        ERROR
    }

    public Download(URL what, Path base) {
        this.what = what;
        Path name = Paths.get(this.what.getFile()).getFileName();
        this.where = Paths.get(base.toString(), name.toString());
        currentStatus = new AtomicReference<>(Status.NEW);
        skipped = new AtomicBoolean(false);
        length = 0;
        skip = 0;
    }

    public URL getWhat() {
        return what;
    }

    public Path getWhere() {
        return where;
    }

    public Status getCurentStatus() {
        return currentStatus.get();
    }

    public boolean changeStatus(Status from, Status to, String info) {
        boolean result = false;
        while (currentStatus.get() == from)
            result = currentStatus.compareAndSet(from, to);
        if (result)
            information = info;
        return result;
    }

    public void prepareOutput(int skip, int payload) throws IOException {
        this.length = payload;
        this.skip = skip;
        this.fout = new RandomAccessFile(this.where.toFile(), "rw");
        this.fout.setLength(this.length);
        this.buffer = fout.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, this.length);
    }

    public ByteBuffer nextOutputBuffer() {
        boolean skipper = false;
        while (!skipped.get())
            skipper = skipped.compareAndSet(false, true);
        if (skipper)
            return ByteBuffer.allocate(skip);
        if (this.buffer.hasRemaining())
            return this.buffer;
        return null;
    }

    public void complete(Download.Status status, String info) {
        try {
            if (fout != null)
                fout.close();
            currentStatus.set(status);
            information = info;
            synchronized (this) {this.notify();}
        } catch (IOException ignored) { }
    }

    public int progress() {
        return 100 * (length - buffer.remaining())/length;
    }

    private final URL what;
    private final Path where;

    private final AtomicReference<Status> currentStatus;
    private volatile String information;


    private RandomAccessFile fout;
    private MappedByteBuffer buffer;
    private int length;

    private final AtomicBoolean skipped;
    private int skip;
}
