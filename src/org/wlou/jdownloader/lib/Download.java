package org.wlou.jdownloader.lib;

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
import java.util.Observable;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Core class representing the download itself.
 * Provides base functionality for initializing, preparing, processing, keeping track and finalizing one download.
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
         * by {@link Downloader#initialize(Downloader.DownloaderContext)} method
         */
        INITIALIZING,
        /**
         * Represents successfully initialized download,
         * which is waiting for starting downloading process
         */
        INITIALIZED,
        /**
         * Represents the active processing download
         * by {@link Downloader#process(Downloader.DownloaderContext)}  method
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
     * Sets the {@link Download#currentStatus} of the download to {@link org.wlou.jdownloader.lib.Download.Status#NEW}
     * @param what  a source url of a network resource
     * @param base  base directory to save the <code>what</code> resource
     */
    public Download(URL what, Path base) {
        this.what = what;
        String baseName = this.what.getFile().replaceAll(DownloadTools.RESERVED, "_");
        Path name = Paths.get(baseName).getFileName();
        Path where = Paths.get(base.toString(), name.toString());
        // FIXME: make thread safe: some another download of the same resource can race for the same file name
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
     * @return {@link org.wlou.jdownloader.lib.Download.Status} of the download.
     */
    public Status getCurrentStatus() {
        return currentStatus;
    }

    /**
     * Gets last error arisen during calls
     * @return last error or (null by default)
     */
    public Throwable getLastError() {
        return lastError;
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

    /**
     * Reports te downloading progress to the registered {@link java.util.Observer} objects
     */
    public void invalidateProgress() {
        setChanged();
        notifyObservers();
    }

    /**
     * Provides byte buffers for writing downloaded parts of the resource
     * @return next output buffer or null if nothing left
     */
    public ByteBuffer nextOutputBuffer() {
        return outputs.poll();
    }

    /**
     * Tries to receive initialization exclusive rights in the current thread
     * @return true if succeeded
     */
    public synchronized boolean lockForInitialization() {
        if (Download.Status.NEW != currentStatus)
            return false;
        setCurrentStatus(Download.Status.INITIALIZING, DownloadTools.INITIALIZING_MESSAGE);
        return true;
    }

    /**
     * Implements initialization logic from HTTP response
     * @param headers HTTP HEAD response
     * @param charset encoding of the response
     * @return true if succeeded
     */
    public synchronized boolean completeInitialization(String headers, String charset) {
        assert headers != null;
        assert charset != null;
        if (Download.Status.INITIALIZING != currentStatus)
            return false;
        try {
            int headersLength = headers.getBytes(charset).length;
            int contentLength = DownloadTools.parseContentLength(headers);
            prepareOutput(headersLength, contentLength);
        } catch (Exception exc) {
            lastError = exc;
            return false;
        }
        setCurrentStatus(Download.Status.INITIALIZED, DownloadTools.SUCCESSFUL_INITIALIZED_MESSAGE);
        return true;
    }

    /**
     * Tries to receive processing exclusive rights in the current thread
     * @return true if succeeded
     */
    public synchronized boolean lockForProcessing() {
        if (Download.Status.INITIALIZED != currentStatus)
            return false;
        setCurrentStatus(Download.Status.DOWNLOADING, DownloadTools.SUCCESSFUL_INITIALIZED_MESSAGE);
        return true;
    }

    /**
     * Completes downloading in a regular way
     * @return true if succeeded
     */
    public synchronized boolean completeProcessing() {
        if (Download.Status.DOWNLOADING != currentStatus)
            return false;
        releaseBuffers();
        setCurrentStatus(Download.Status.DOWNLOADED, DownloadTools.SUCCESSFUL_COMPLETED_MESSAGE);
        return true;
    }

    /**
     * Completes the download with error and specific status message
     * @param statusInfo public information about the error
     */
    public synchronized void interruptExceptionally(String statusInfo) {
        if (!DownloadTools.isActiveDownload(this))
            return;
        releaseBuffers();
        setCurrentStatus(Download.Status.ERROR, statusInfo);
    }

    /**
     * Turns the download to a ghost
     */
    public synchronized void turnToGhost() {
        setCurrentStatus(Status.GHOST, "");
        releaseBuffers();
    }

    /**
     * Sets current downloading status.
     * Notifies observers about the change.
     * @param status New {@link org.wlou.jdownloader.lib.Download.Status} of the download.
     * @param info Textual representation of the <code>status</code>.
     */
    private void setCurrentStatus(Download.Status status, String info) {
        if (currentStatus == Status.GHOST)
            return;
        currentStatus = status;
        information = info;
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
    private void prepareOutput(int skip, int payload) throws IOException {
        outputs = new ConcurrentLinkedQueue<>();
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
    private void releaseBuffers() {
        // FIXME: workaround http://bugs.java.com/view_bug.do?bug_id=4724038
        try {
            Method unmapMethod = sun.nio.ch.FileChannelImpl.class.getDeclaredMethod("unmap", MappedByteBuffer.class);
            unmapMethod.setAccessible(true);
            unmapMethod.invoke(null, mainBuffer);
        } catch (Exception ignored) { }
    }

    private final URL what;
    private final Path where;

    private volatile Status currentStatus;
    private volatile String information;

    private MappedByteBuffer mainBuffer;
    private ConcurrentLinkedQueue<ByteBuffer> outputs;

    private volatile Throwable lastError;
}
