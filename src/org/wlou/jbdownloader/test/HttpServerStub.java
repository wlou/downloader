package org.wlou.jbdownloader.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

class HttpHandlerStub implements HttpHandler {

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {

    }
}

public class HttpServerStub {

    private HttpServer httpServer;

    public HttpServerStub(int port) {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.createContext("JBDownloaderTest", new HttpHandlerStub());
            httpServer.setExecutor(null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void start() {
        httpServer.start();
    }
}

