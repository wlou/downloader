package org.wlou.jdownloader.lib.test;

import junit.framework.TestCase;
import org.wlou.jdownloader.lib.Download;
import org.wlou.jdownloader.lib.DownloadTools;
import org.wlou.jdownloader.lib.Downloader;
import org.wlou.jdownloader.lib.HttpTools;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloaderTest extends TestCase {

    public static final String _1K_ZEROS_URL = "http://localhost:8080/JBDownloaderTest?q=1k_bytes_0";
    public static final String _10K_ONES_URL = "http://localhost:8080/JBDownloaderTest?q=100k_bytes_1";
    public static final String _404_URL = "http://localhost:8080/JBDownloaderTest?q=404";

    public static final String initResponse =
        "HTTP/1.1 200 OK\r\n" +
        "Server: test_stub\r\n" +
        "Date: DAY, dd MON YEAR hh:mm:ss GMT\r\n" +
        "Content-type: application/octet-stream\r\n" +
        "Content-length: 1024\r\n" +
        "\r\n";

    private static  HttpServerStub testServer;

    static  {
        try {
            testServer = new HttpServerStub(8080);
            testServer.start();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void testInitialize() throws Exception {
        ConcurrentLinkedQueue<Download> testQueue = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Path defaultBasePath = Paths.get(".").toAbsolutePath().normalize();

        try {
            Downloader downloader = new Downloader(testQueue, pool);

            // let's test 404 response
            Download d = new Download(new URL(_404_URL), defaultBasePath);
            try {
                downloader.initialize(new Downloader.DownloaderContext(d, "initialize"), () -> true);
                for (int i = 0; d.getCurrentStatus() == Download.Status.INITIALIZING && i < 3; ++i)
                    synchronized (d) { d.wait(500); }
                assertTrue(d.getCurrentStatus() == Download.Status.ERROR);
                assertEquals(d.getInformation(), DownloadTools.INIT_ERROR_MESSAGE);
                assertFalse(d.getWhere().toFile().exists());
            } finally {
                d.turnToGhost();
                Files.deleteIfExists(d.getWhere());
            }

            // let's test 200 response
            d = new Download(new URL(_1K_ZEROS_URL), defaultBasePath);
            try {

                downloader.initialize(new Downloader.DownloaderContext(d, "initialize"), () -> true);
                for (int i = 0; d.getCurrentStatus() == Download.Status.INITIALIZING && i < 3; ++i)
                    synchronized (d) { d.wait(500); }
                assertTrue(d.getCurrentStatus() == Download.Status.INITIALIZED);
                assertEquals(d.getInformation(), DownloadTools.SUCCESSFUL_INITIALIZED_MESSAGE);
                assertTrue(d.getWhere().toFile().exists());
            } finally {
                d.turnToGhost();
                Files.deleteIfExists(d.getWhere());
            }
        } finally {
            pool.shutdown();
        }

        // TODO: add more test cases with various server responses with critical parameters to test stability and etc...
    }

    public void testProcess() throws Exception {
        ConcurrentLinkedQueue<Download> testQueue = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Path defaultBasePath = Paths.get(".").toAbsolutePath().normalize();

        try {
            Downloader downloader = new Downloader(testQueue, pool);

            // let's test regular case
            Download d = new Download(new URL(_1K_ZEROS_URL), defaultBasePath);
            d.lockForInitialization();
            d.completeInitialization(initResponse, HttpTools.DEFAULT_CONTENT_CHARSET);
            try {
                downloader.process(new Downloader.DownloaderContext(d, "process"), () -> true);
                for (int i = 0; d.getCurrentStatus() == Download.Status.DOWNLOADING && i < 3; ++i)
                    synchronized (d) { d.wait(500); }
                assertTrue(d.getCurrentStatus() == Download.Status.DOWNLOADED);
                assertEquals(d.getInformation(), DownloadTools.SUCCESSFUL_COMPLETED_MESSAGE);
                assertTrue(d.getWhere().toFile().exists());
                assertTrue(Arrays.equals(Files.readAllBytes(d.getWhere()), HttpHandlerStub._1K_ZEROS));
            } finally {
                d.turnToGhost();
                Files.deleteIfExists(d.getWhere());
            }
        } finally {
            pool.shutdown();
        }

        // TODO: add more test cases, for example interrupt and etc..
    }

    public void testRun() throws Exception {
        ConcurrentLinkedQueue<Download> testQueue = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Path defaultBasePath = Paths.get(".").toAbsolutePath().normalize();
        Downloader downloader = new Downloader(testQueue, pool);
        Thread worker = new Thread(downloader);

        try {
            // 1. let's test regular case:
            //    add 2 downloads and run downloader
            //    we expect that longer task will be completed after shorter
            // FIXME:
            //    it is "dirty" assumption (there are cases leading to false negative) but it makes the test simpler

            // the first download the downloader meets and processes is "large" download
            final Download large = new Download(new URL(_10K_ONES_URL), defaultBasePath);
            testQueue.add(large);
            // the second is "small" download
            final Download small = new Download(new URL(_1K_ZEROS_URL), defaultBasePath);
            testQueue.add(small);

            // queue to register downloads competitors
            class DownloadSnapshot {
                DownloadSnapshot(Download d, Download.Status status) {
                    Competitor = d;
                    Status = status;
                }
                public final Download Competitor;
                public final Download.Status Status;
            }
            final ConcurrentLinkedQueue<DownloadSnapshot> race = new ConcurrentLinkedQueue<>();
            Observer photo_finish = new Observer() {
                private final Object lock = new Object();
                @Override
                public void update(Observable o, Object arg) {
                    synchronized (lock) {
                        Download competitor = (o == large ? large : small);
                        race.add(new DownloadSnapshot(competitor, competitor.getCurrentStatus()));
                    }
                }
            };
            for (Download d : testQueue)
                d.addObserver(photo_finish);

            // start the race
            worker.start();

            // wait for results
            for (int i = 0; small.getCurrentStatus() != Download.Status.DOWNLOADED && i < 5; ++i)
                synchronized (small) { small.wait(500); }
            for (int i = 0; large.getCurrentStatus() != Download.Status.DOWNLOADED && i < 5; ++i)
                synchronized (large) { large.wait(500); }

            // check results
            for (DownloadSnapshot ds: race) {
                if(ds.Status == Download.Status.DOWNLOADED) {
                    assertTrue(ds.Competitor == small);
                    break;
                }
            }
            assertTrue(Arrays.equals(Files.readAllBytes(small.getWhere()), HttpHandlerStub._1K_ZEROS));
            assertTrue(Arrays.equals(Files.readAllBytes(large.getWhere()), HttpHandlerStub._100K_ONES));
        }
        finally {
            pool.shutdown();
            worker.interrupt();
            for (Download d: testQueue) {
                d.turnToGhost();
                Files.deleteIfExists(d.getWhere());
            }
        }
    }
}