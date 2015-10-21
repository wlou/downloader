package org.wlou.jbdownloader.lib.test;

import junit.framework.TestCase;
import org.wlou.jbdownloader.lib.Download;
import org.wlou.jbdownloader.lib.DownloadTools;
import org.wlou.jbdownloader.lib.Downloader;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.Executors;

public class DownloaderTest extends TestCase {

    public static final String _1K_BYTES_URL = "http://localhost:8080/JBDownloaderTest?q=1k_bytes";
    public static final String _404_URL = "http://localhost:8080/JBDownloaderTest?q=404";

    private static  HttpServerStub testServer;
    private static Downloader downloader;

    static  {
        try {
            LinkedList<Download> testQueue = new LinkedList<>();
            downloader = new Downloader(testQueue, Executors.newFixedThreadPool(1));
            testServer = new HttpServerStub(8080);
            testServer.start();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void testInitialize() throws Exception {
        Path defaultBasePath = Paths.get(".").toAbsolutePath().normalize();

        // let's test 404 response
        Download d = new Download(new URL(_404_URL), defaultBasePath);
        try {

            downloader.initialize(d);
            for (int i = 0; d.getCurrentStatus() == Download.Status.INITIALIZING && i < 3; ++i)
                synchronized (d) { d.wait(500); }
            assertTrue(d.getCurrentStatus() == Download.Status.ERROR);
            assertEquals(d.getInformation(), DownloadTools.INIT_ERROR_MESSAGE);
            assertFalse(d.getWhere().toFile().exists());
        }
        catch (Exception e) {
            throw e;
        }
        finally {
            d.releaseBuffers();
            Files.deleteIfExists(d.getWhere());
        }

        // let's test 200 response
        d = new Download(new URL(_1K_BYTES_URL), defaultBasePath);
        try {

            downloader.initialize(d);
            for (int i = 0; d.getCurrentStatus() == Download.Status.INITIALIZING && i < 3; ++i)
                synchronized (d) { d.wait(500); }
            assertTrue(d.getCurrentStatus() == Download.Status.INITIALIZED);
            assertEquals(d.getInformation(), DownloadTools.SUCCESSFUL_INITIALIZED_MESSAGE);
            assertTrue(d.getWhere().toFile().exists());
        }
        catch (Exception e) {
            throw e;
        }
        finally {
            d.releaseBuffers();
            Files.deleteIfExists(d.getWhere());
        }

        // TODO: add more test cases with various server responses with critical parameters to test stability and etc...
        // two above cases are enough because it's just a test work
    }

    public void testProcess() throws Exception {
        Path defaultBasePath = Paths.get(".").toAbsolutePath().normalize();

        // let's test regular case
        Download d = new Download(new URL(_1K_BYTES_URL), defaultBasePath);
        try {
            //FIXME: make static initialization. Now it's impossible without full control on HttpExchange in HttpHandler
            downloader.initialize(d);
            for (int i = 0; d.getCurrentStatus() == Download.Status.INITIALIZING && i < 3; ++i)
                synchronized (d) { d.wait(500); }

            downloader.process(d);
            for (int i = 0; d.getCurrentStatus() == Download.Status.DOWNLOADING && i < 3; ++i)
                synchronized (d) { d.wait(500); }
            assertTrue(d.getCurrentStatus() == Download.Status.DOWNLOADED);
            assertEquals(d.getInformation(), DownloadTools.SUCCESSFUL_COMPLETED_MESSAGE);
            assertTrue(d.getWhere().toFile().exists());
            assertTrue(Arrays.equals(Files.readAllBytes(d.getWhere()), HttpHandlerStub._1K_ZEROS));
        }
        catch (Exception e){
            throw e;
        }
        finally {
            d.releaseBuffers();
            Files.deleteIfExists(d.getWhere());
        }

        // TODO: add more test cases, for example interrupt and etc..
        // this is enough because it's just a test work
    }
}