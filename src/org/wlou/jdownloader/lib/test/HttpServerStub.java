package org.wlou.jdownloader.lib.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executors;

class HttpHandlerStub implements HttpHandler {

    public static final String SERVER_KEY = "Server";
    public static final String SERVER_VAL = "test_stub";
    public static final String CT_KEY = "Content-Type";
    public static final String CT_VAL = "application/octet-stream";
    public static final String CL_KEY = "Content-Length";
    public static final String ALLOW_KEY = "Allow";
    public static final String ALLOW_VAL = "HEAD,GET";

    public static final byte[] _1K_ZEROS = new byte[1024];
    public static final byte[] _100K_ONES = new byte[100*1024];

    public static final int _200_CODE = 200;
    public static final int _404_CODE = 404;
    public static final int _405_CODE = 405;

    static {
        for (int i = 0; i < _100K_ONES.length; ++i)
            _100K_ONES[i] = 1;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        URI uri = httpExchange.getRequestURI();
        byte[] resource = getResource(uri);
        if (httpExchange.getRequestMethod().equalsIgnoreCase("head")) {
            if (resource != null) {
                make200Headers(httpExchange, resource);
                httpExchange.sendResponseHeaders(_200_CODE, -1);
                httpExchange.getResponseBody().close();
            }
            else
                send404(httpExchange);
        }
        else if (httpExchange.getRequestMethod().equalsIgnoreCase("get")) {
            if (resource != null) {
                make200Headers(httpExchange, resource);
                httpExchange.sendResponseHeaders(_200_CODE, resource.length);
                httpExchange.getResponseBody().write(resource);
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

    private byte[] getResource(URI uri) {
        String query = uri.getQuery();
        if (query.equalsIgnoreCase("q=1k_bytes_0"))
            return _1K_ZEROS;
        if (query.equalsIgnoreCase("q=100k_bytes_1"))
            return _100K_ONES;
        return null;
    }

    private void make200Headers(HttpExchange httpExchange, byte[] resource) {
        httpExchange.getResponseHeaders().set(SERVER_KEY, SERVER_VAL);
        httpExchange.getResponseHeaders().set(CT_KEY, CT_VAL);
        httpExchange.getResponseHeaders().set(CL_KEY, String.format("%d", resource.length));
    }

    private void send404(HttpExchange httpExchange) throws IOException {
        httpExchange.getResponseHeaders().set(SERVER_KEY, SERVER_VAL);
        httpExchange.sendResponseHeaders(_404_CODE, -1);
        httpExchange.getResponseBody().close();
    }
}

public class HttpServerStub {

    private HttpServer httpServer;

    public HttpServerStub(int port) {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.createContext("/JBDownloaderTest", new HttpHandlerStub());
            httpServer.setExecutor(Executors.newFixedThreadPool(2));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void start() {
        httpServer.start();
    }
}

