package org.wlou.jbdownloader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

public class Download {

    public enum Status
    {
        NEW,
        INITIALIZING,
        INITIALIZED,
        DOWNLOADING,
        DOWNLOADED,
        ERROR,
        GHOST
    }

    public Download(URL what, Path base) {
        this.what = what;
        Path name = Paths.get(this.what.getFile()).getFileName();
        this.where = Paths.get(base.toString(), name.toString());
        currentStatus = new AtomicReference<>(Status.NEW);
        outputs = new ConcurrentLinkedQueue<>();
        length = 0;
        skip = 0;

    }

    public URL getWhat() {
        return what;
    }

    public Path getWhere() {
        return where;
    }

    public String getInformation() {
        return information;
    }

    public Status getCurrentStatus() {
        return currentStatus.get();
    }

    public boolean changeCurrentStatus(Status from, Status to, String information) {
        boolean result = false;
        if (from == Status.GHOST)
            return false;
        while (currentStatus.get() == from)
            result = currentStatus.compareAndSet(from, to);
        if (result) {
            this.information = information;
            synchronized (this) { this.notify(); }
        }
        return result;
    }

    public void setCurrentStatus(Download.Status status, String information) {
        while (!changeCurrentStatus(currentStatus.get(), status, information)) {
            if (currentStatus.get() == Status.GHOST)
                break;
        }
    }

    public void prepareOutput(int skip, int payload) throws IOException {
        this.skip = skip;
        if (this.skip > 0)
            outputs.add(ByteBuffer.allocate(this.skip));

        this.length = payload;
        RandomAccessFile file = new RandomAccessFile(this.where.toFile(), "rw");
        file.setLength(this.length);
        mainBuffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, this.length);
        outputs.add(mainBuffer);
    }

    public ByteBuffer nextOutputBuffer() {
        return outputs.poll();
    }

    public int progress() {
        return (100 * (length - mainBuffer.remaining()))/length;
    }

    private final URL what;
    private final Path where;

    private final AtomicReference<Status> currentStatus;
    private volatile String information;

    private MappedByteBuffer mainBuffer;
    private final ConcurrentLinkedQueue<ByteBuffer> outputs;
    private int length;
    private int skip;
}
