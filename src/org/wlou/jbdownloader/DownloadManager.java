package org.wlou.jbdownloader;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Observable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DownloadManager extends Observable implements AutoCloseable {

    public DownloadManager() throws IOException {
        downloads = new ConcurrentLinkedQueue<>();
        executors = new ThreadPoolExecutor(parallelCapacity, parallelCapacity, 0, TimeUnit.NANOSECONDS, new LinkedBlockingQueue<>());
        dispatcher = new Thread(new Downloader(downloads, executors));
        dispatcher.start();
    }

    public void addDownload(Download download) {
        downloads.add(download);
        setChanged();
        notifyObservers();
        synchronized (downloads) { downloads.notify(); }
    }

    public Download addDownload(URL url, Path base) {
        Download download = new Download(url, base);
        addDownload(download);
        return download;
    }

    public void removeDownload(Download download) {
        download.setCurrentStatus(Download.Status.GHOST, null);
        downloads.remove(download);
        setChanged();
        notifyObservers();
        synchronized (downloads) { downloads.notify(); }
    }

    public void setParallelCapacity(int capacity) {
        executors.setCorePoolSize(capacity);
        executors.setMaximumPoolSize(capacity);
    }

    @Override
    public void close() throws Exception {
        executors.shutdown();
        dispatcher.interrupt();
        downloads.clear();
    }

    private final Thread dispatcher;
    private final ThreadPoolExecutor executors;
    private final ConcurrentLinkedQueue<Download> downloads;

    int parallelCapacity = 2;
}
