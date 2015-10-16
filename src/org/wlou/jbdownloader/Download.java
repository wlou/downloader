package org.wlou.jbdownloader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.Observable;
import java.util.Queue;

public class Download extends Observable {

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
        currentStatus = Status.NEW;
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
        return currentStatus;
    }

    public void setCurrentStatus(Download.Status status, String information) {
        this.currentStatus = status;
        this.information = information;
        setChanged();
        notifyObservers();
    }

    public void prepareOutput(int skip, int payload) throws IOException {
        outputs = new LinkedList<>();
        if (skip > 0)
            outputs.add(ByteBuffer.allocate(skip));
        if (payload > 0) {
            RandomAccessFile file = new RandomAccessFile(this.where.toFile(), "rw");
            file.setLength(payload);
            mainBuffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, payload);
            outputs.add(mainBuffer);
        }
    }

    public ByteBuffer nextOutputBuffer() {
        return outputs.poll();
    }

    public int progress() {
        return (100 * (mainBuffer.capacity() - mainBuffer.remaining()))/mainBuffer.capacity();
    }

    private final URL what;
    private final Path where;

    private volatile Status currentStatus;
    private volatile String information;

    private MappedByteBuffer mainBuffer;
    private Queue<ByteBuffer> outputs;
}
