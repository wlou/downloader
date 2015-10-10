package org.wlou.jbdownloader.gui;

import org.wlou.jbdownloader.Download;
import org.wlou.jbdownloader.Downloader;

import java.net.URL;
import java.nio.file.FileSystems;
import java.util.concurrent.ConcurrentLinkedQueue;


public class Application {
    public static final String F = "http://apache-mirror.rbc.ru/pub/apache//httpcomponent/httpclient/binary/httpcomponents-client-4.5.1-bin.zip";
    public static final String U = "http://apache-mirror.rbc.ru/pub/apache//httpcomponents/httpclient/binary/httpcomponents-client-4.5.1-bin.zip";
    public static void main(String[] args) {
        try {

            Download test = new Download(
                new URL(U),
                FileSystems.getDefault().getPath("test")
            );
            Downloader d = new Downloader(new ConcurrentLinkedQueue<>());
            d.initialize(test);
            System.out.println(test.CurrentStatus.get());
            Thread.sleep(2000);
            System.out.println(test.CurrentStatus.get());
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
}
