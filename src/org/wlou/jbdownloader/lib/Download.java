package org.wlou.jbdownloader.lib;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.Observable;
import java.util.Queue;

/**
 * Core class representing the download itself.
 * Provides base functionality for initializing, preparing, processing,
 * keeping track and finalizing one download.
 * All the methods are NOT thread safe and requires synchronization on the top level.
 * Implements {@link Observable} interface for signaling changes of the download state.
 */
public class Download extends Observable {

    /**
     * Represents states of the {@link Download} objects
     * Valid chains of states:
     *    NEW -> INITIALIZING -> INITIALIZED -> DOWNLOADING -> DOWNLOADED
     *    INITIALIZING -> ERROR
     *    DOWNLOADING -> ERROR
     *    [ANY] -> GHOST
     */
    public enum Status
    {
        /**
         * Represents recently created download,
         * which have never been seen by {@link Downloader}
         */
        NEW,
        /**
         * Represents the download under initialization
         * by {@link Downloader#initialize(Download)} method
         */
        INITIALIZING,
        /**
         * Represents successfully initialized download,
         * which is waiting for starting downloading process
         */
        INITIALIZED,
        /**
         * Represents the active processing download
         * by {@link Downloader#process(Download)} method
         */
        DOWNLOADING,
        /**
         * Represents successfully processed download
         */
        DOWNLOADED,
        /**
         * Represents broken download without no distinguishing the
         * failed stage (initializing or processing)
         */
        ERROR,
        /**
         * Represents service state o the download. It is used before
         * removing it from the {@link DownloadManager}
         */
        GHOST
    }

    /**
     * Initializes the download with url and target directory path.
     * Checks target directory for existing filename and generates unique file name.
     * Sets the {@link Download#currentStatus} of the download to {@link org.wlou.jbdownloader.lib.Download.Status#NEW}
     * @param what  a source url of a network resource
     * @param base  base directory to save the <code>what</code> resource
     */
    public Download(URL what, Path base) {
        this.what = what;
        String baseName = this.what.getFile().replaceAll(DownloadTools.RESERVED, "_");
        Path name = Paths.get(baseName).getFileName();
        Path where = Paths.get(base.toString(), name.toString());
        // FIXME: make thread safe: some another download of the same resource can rival for the same base directory
        for (int i = 1; where.toFile().exists(); ++i) {
            name = Paths.get(String.format("%s.%d", baseName, i)).getFileName();
            where = Paths.get(base.toString(), name.toString());
        }
        this.where = where;
        currentStatus = Status.NEW;
    }

    /**
     * Getter for source url.
     * @return downloading url
     */
    public URL getWhat() {
        return what;
    }

    /**
     * Getter for target file path.
     * @return path to the downloaded resource
     */
    public Path getWhere() {
        return where;
    }

    /**
     * Getter for additional information about downloading process.
     * @return text representation of {@link Download#currentStatus}
     */
    public String getInformation() {
        return information;
    }

    /**
     * Gets current downloading status.
     * @return {@link org.wlou.jbdownloader.lib.Download.Status} of the download.
     */
    public Status getCurrentStatus() {
        return currentStatus;
    }

    /**
     * Sets current downloading status.
     * Notifies observers about the change.
     * @param status New {@link org.wlou.jbdownloader.lib.Download.Status} of the download.
     * @param information Textual representation of the <code>status</code>.
     */
    public void setCurrentStatus(Download.Status status, String information) {
        this.currentStatus = status;
        this.information = information;
        setChanged();
        notifyObservers();
    }

    /**
     * Creates target file for the downloading resource.
     * Maps the file content to the memory.
     * Creates queue of buffers for the memory.
     * @param skip The number of bytes to skip before start writing to the target memory.
     *             It is useful for skipping http headers and writing only content.
     * @param payload a number of bytes in resource content (Content-Length http parameter)
     * @throws IOException when
     *  {@link RandomAccessFile#RandomAccessFile(File, String)} or
     *  {@link RandomAccessFile#setLength(long)} or
     *  {@link FileChannel#map(FileChannel.MapMode, long, long)} or
     *  {@link FileChannel#close()} throw exception
     */
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

    /**
     * Hacky workaround to overcome {@link MappedByteBuffer} limitations which
     * disallow to safely unmap previously mapped memory.
     */
    public void releaseBuffers() {
        // FIXME: workaround http://bugs.java.com/view_bug.do?bug_id=4724038
        try {
            Method unmapMethod = sun.nio.ch.FileChannelImpl.class.getDeclaredMethod("unmap", MappedByteBuffer.class);
            unmapMethod.setAccessible(true);
            unmapMethod.invoke(null, mainBuffer);
        } catch (Exception ignored) { }
    }

    /**
     * Provides byte buffers for writing downloaded parts of the resource
     * @return next output buffer or null if nothing left
     */
    public ByteBuffer nextOutputBuffer() {
        return outputs.poll();
    }

    /**
     * Reports te downloading progress to the registered {@link java.util.Observer} objects
     */
    public void invalidateProgress() {
        setChanged();
        notifyObservers();
    }

    /**
     * Getter for the current downloading progress
     * @return progress in range [0.0; 1.0]
     */
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
