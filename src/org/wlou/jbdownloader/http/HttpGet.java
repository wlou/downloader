package org.wlou.jbdownloader.http;

import java.net.URL;

public class HttpGet {

    public final static String METHOD_NAME = "GET";

    /**
     * @param url
     * @return
     */
    public static String makeRequest(URL url) {
        StringBuilder request = new StringBuilder();

        String protocol = url.getProtocol();
        if (!protocol.equals("http")) {
            //TODO:
            return "";
        }

        request.append(String.format("%s %s %s", METHOD_NAME, url.getPath(), Http.DEFAULT_VERION));
        request.append((char)Http.CR);
        request.append((char)Http.LF);
        request.append(String.format("%s: %s",Http.TARGET_HOST, url.getAuthority()));
        request.append((char)Http.CR);
        request.append((char)Http.LF);
        request.append(String.format("%s: %s",Http.CONNECTION_DIRECTIVE, Http.CONNECTION_KEEP_ALIVE));
        request.append((char)Http.CR);
        request.append((char)Http.LF);
        request.append((char)Http.CR);
        request.append((char)Http.LF);

        return request.toString();
    }
}
