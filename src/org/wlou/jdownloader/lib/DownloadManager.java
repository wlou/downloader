package org.wlou.jdownloader.lib;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Observable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Provides main downloading API:
 *  - creating and starting downloads
 *  - interrupting and removing
 *  - controlling process by setting parameters
 */
public class DownloadManager extends Observable implements AutoCloseable {

    public DownloadManager() throws IOException {
        downloads = new ConcurrentLinkedQueue<>();
        executors = new ThreadPoolExecutor(parallelCapacity, parallelCapacity, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        downloader = new Downloader(downloads, executors);
        dispatcher = new Thread(downloader, String.format("Downloader-%02X", downloader.hashCode()));
        dispatcher.start();
    }

    /**
     * Creates new download and insert in the processing queue.
     * @param url The url of the resources.
     * @param base The directory in the local file system to save the network resource.
     * @return {@link Download} object representing the download in the library.
     */
    public Download addDownload(URL url, Path base) {
        Download download = new Download(url, base);
        downloads.add(download);
        downloader.checkForNewTasks();
        setChanged();
        notifyObservers();
        return download;
    }

    /**
     * Gracefully stops the download (if needed) and removes from the manager's queue.
     * @param download The download to remove
     */
    public void removeDownload(Download download) {
        download.turnToGhost();
        downloads.remove(download);
        setChanged();
        notifyObservers();

    }

    /**
     * Download queue accessor.
     * @return Current manager's queue of downloads.
     */
    public Download[] getDownloadsSnap() {
        return downloads.toArray(new Download[downloads.size()]);
    }

    /**
     * Sets number of thread-handlers of the downloads.
     * @param capacity The number of thread [1; Infinity)
     */
    public void setParallelCapacity(int capacity) {
        executors.setCorePoolSize(capacity);
        executors.setMaximumPoolSize(capacity);
    }

    /**
     * Stops all download threads.
     * Cleans downloading queue.
     * Object becomes useless
     * @throws Exception required by the base {@link AutoCloseable}
     */
    @Override
    public void close() throws Exception {
        executors.shutdown();
        dispatcher.interrupt();
        downloads.clear();
    }

    private final Thread dispatcher;
    private final Downloader downloader;
    private final ThreadPoolExecutor executors;
    private final ConcurrentLinkedQueue<Download> downloads;

    int parallelCapacity = 2;
}
