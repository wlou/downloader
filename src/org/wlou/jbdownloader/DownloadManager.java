package org.wlou.jbdownloader;

import java.util.concurrent.ConcurrentLinkedQueue;

public class DownloadManager {
    private final ConcurrentLinkedQueue<Download> downloads = new ConcurrentLinkedQueue<>();
}
