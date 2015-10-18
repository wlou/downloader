package org.wlou.jbdownloader.lib;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Observable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DownloadManager extends Observable implements AutoCloseable {

    public DownloadManager() throws IOException {
        downloads = new LinkedList<>();
        executors = new ThreadPoolExecutor(parallelCapacity, parallelCapacity, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        dispatcher = new Thread(new Downloader(downloads, executors));
        dispatcher.start();
    }

    public Download addDownload(URL url, Path base) {
        Download download;
        synchronized (downloads) {
            download = new Download(url, base);
            downloads.add(download);
            downloads.notify();
        }
        setChanged();
        notifyObservers();
        return download;
    }

    public void removeDownload(Download download) {
        synchronized (download) {
            download.setCurrentStatus(Download.Status.GHOST, null);
        }
        synchronized (downloads) {
            downloads.remove(download);
            downloads.notifyAll();
        }
        setChanged();
        notifyObservers();
    }

    public List<Download> getDownloads() {
        return downloads;
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
    private final List<Download> downloads;

    int parallelCapacity = 2;
}
