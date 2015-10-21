package org.wlou.jbdownloader.lib.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;

class HttpHandlerStub implements HttpHandler {

    public static final String SERVER_KEY = "Server";
    public static final String SERVER_VAL = "test_stub";
    public static final String CT_KEY = "Content-Type";
    public static final String CT_VAL = "application/octet-stream";
    public static final String CL_KEY = "Content-Length";
    public static final String ALLOW_KEY = "Allow";
    public static final String ALLOW_VAL = "HEAD,GET";

    public static final byte[] _1K_ZEROS = new byte[1024];

    public static final int _200_CODE = 200;
    public static final int _404_CODE = 404;
    public static final int _405_CODE = 405;

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        URI uri = httpExchange.getRequestURI();
        if (httpExchange.getRequestMethod().equalsIgnoreCase("head")) {
            if (hasResource(uri)) {
                make200Headers(httpExchange);
                httpExchange.sendResponseHeaders(_200_CODE, -1);
                httpExchange.getResponseBody().close();
            }
            else
                send404(httpExchange);
        }
        else if (httpExchange.getRequestMethod().equalsIgnoreCase("get")) {
            if (hasResource(uri)) {
                make200Headers(httpExchange);
                httpExchange.sendResponseHeaders(_200_CODE, _1K_ZEROS.length);
                httpExchange.getResponseBody().write(_1K_ZEROS);
                httpExchange.getResponseBody().close();
            }
            else
                send404(httpExchange);
        }
        else {
            httpExchange.getResponseHeaders().set(SERVER_KEY, SERVER_VAL);
            httpExchange.getResponseHeaders().set(ALLOW_KEY, ALLOW_VAL);
            httpExchange.sendResponseHeaders(_405_CODE, -1);
            httpExchange.getResponseBody().close();
        }
    }

    private void make200Headers(HttpExchange httpExchange) {
        httpExchange.getResponseHeaders().set(SERVER_KEY, SERVER_VAL);
        httpExchange.getResponseHeaders().set(CT_KEY, CT_VAL);
        httpExchange.getResponseHeaders().set(CL_KEY, String.format("%d", _1K_ZEROS.length));
    }

    private void send404(HttpExchange httpExchange) throws IOException {
        httpExchange.getResponseHeaders().set(SERVER_KEY, SERVER_VAL);
        httpExchange.sendResponseHeaders(_404_CODE, -1);
        httpExchange.getResponseBody().close();
    }

    private boolean hasResource(URI uri) {
        String query = uri.getQuery();
        return query.equalsIgnoreCase("q=1k_bytes");
    }
}

public class HttpServerStub {

    private HttpServer httpServer;

    public HttpServerStub(int port) {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.createContext("/JBDownloaderTest", new HttpHandlerStub());
            httpServer.setExecutor(null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void start() {
        httpServer.start();
    }
}

