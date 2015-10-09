package org.wlou.jbdownloader.http;

import java.net.URL;
import java.nio.Buffer;
import java.util.Map;

public final class HttpHead {

    public final static String METHOD_NAME = "HEAD";

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
        request.append(String.format("%s: %s",Http.CONNECTION_DIRECTIVE, Http.CONNECTION_CLOSE));
        request.append((char)Http.CR);
        request.append((char)Http.LF);
        request.append((char)Http.CR);
        request.append((char)Http.LF);

        return request.toString();
    }

    public static Map<String, String> parseResponse(Buffer response) {
        return null;
    }


}
