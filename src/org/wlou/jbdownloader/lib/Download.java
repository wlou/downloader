package org.wlou.jbdownloader.lib;

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
        String baseName = this.what.getFile();
        Path name = Paths.get(baseName).getFileName();
        Path where = Paths.get(base.toString(), name.toString());
        for (int i = 1; where.toFile().exists() && i < 100; ++i) {
            name = Paths.get(String.format("%s.%d", baseName, i)).getFileName();
            where = Paths.get(base.toString(), name.toString());
        }
        this.where = where;
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
            FileChannel channel = file.getChannel();
            mainBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, payload);
            channel.close();
            outputs.add(mainBuffer);
        }
    }

    public void releaseBuffers() {
        // FIXME: workaround http://bugs.java.com/view_bug.do?bug_id=4724038
        try {
            java.lang.reflect.Method unmapMethod = FileChannel.class.getDeclaredMethod("unmap", MappedByteBuffer.class);
            unmapMethod.setAccessible(true);
            unmapMethod.invoke(null, mainBuffer);
        } catch (Exception ignored) { }
    }

    public ByteBuffer nextOutputBuffer() {
        return outputs.poll();
    }

    public void updateProgress() {
        setChanged();
        notifyObservers();
    }

    public double getProgress() {
        if (mainBuffer != null)
            return (double)(mainBuffer.capacity() - mainBuffer.remaining())/(double)mainBuffer.capacity();
        return 0;
    }

    private final URL what;
    private final Path where;

    private volatile Status currentStatus;
    private volatile String information;

    private MappedByteBuffer mainBuffer;
    private Queue<ByteBuffer> outputs;
}
