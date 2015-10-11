package org.wlou.jbdownloader.gui;

import org.wlou.jbdownloader.Download;
import org.wlou.jbdownloader.DownloadManager;
import org.wlou.jbdownloader.http.Http;

import java.net.URL;
import java.nio.file.Paths;
import java.util.regex.Pattern;


public class Application {
    public static final String F = "http://apache-mirror.rbc.ru/pub/apache//httpcomponent/httpclient/binary/httpcomponents-client-4.5.1-bin.zip";
    public static final String U = "http://apache-mirror.rbc.ru/pub/apache//httpcomponents/httpclient/binary/httpcomponents-client-4.5.1-bin.zip";

    public static final String T = "HTTP/1.1 200 OK\r\n" +
            "Server: nginx/1.4.7\r\n" +
            "Date: Sun, 11 Oct 2015 12:14:47 GMT\r\n" +
            "Content-Type: application/zip\r\n" +
            "Content-Length: 3043313\r\n" +
            "Last-Modified: Tue, 15 Sep 2015 12:17:24 GMT\r\n" +
            "Connection: close\r\n" +
            "ETag: \"55f80c54-2e6ff1\"\r\n" +
            "Accept-Ranges: bytes\r\n" +
            "\r\n";

    public static void main(String[] args) {
        try (DownloadManager mgr = new DownloadManager()){
            Download test = new Download(new URL(U),Paths.get(System.getProperty("user.dir")));
            mgr.addDownload(test);
            synchronized (test) {
                test.wait();
            }
            System.out.println(test.getCurentStatus());
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static final Pattern lineSplitter = Pattern.compile(String.valueOf((char)Http.LF), Pattern.LITERAL);
}
