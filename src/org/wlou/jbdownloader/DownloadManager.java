package org.wlou.jbdownloader;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DownloadManager implements AutoCloseable {

    public DownloadManager() throws IOException {
        downloads = new ConcurrentLinkedQueue<>();
        executors = new ThreadPoolExecutor(parallelCapacity, parallelCapacity, 0, TimeUnit.NANOSECONDS, new LinkedBlockingQueue<>());
        worker = new Thread(new Downloader(downloads, executors));
        worker.start();
    }

    public void addDownload(Download download) {
        downloads.add(download);
        synchronized (downloads) { downloads.notify(); }
    }

    public void addDownload(URL url, Path base) {
        addDownload(new Download(url, base));
    }

    public void removeDownload(Download download) {
        while (download.getCurentStatus() != Download.Status.STOPED)
            download.changeStatus(download.getCurentStatus(), Download.Status.STOPED, "");
        downloads.remove(download);
    }

    public void setParallelCapacity(int capacity) {
        executors.setCorePoolSize(capacity);
        executors.setMaximumPoolSize(capacity);
    }

    @Override
    public void close() throws Exception {
        executors.shutdown();
        worker.interrupt();
        downloads.clear();
    }

    private final Thread worker;
    private final ThreadPoolExecutor executors;
    private final ConcurrentLinkedQueue<Download> downloads;

    int parallelCapacity = 1;
}
