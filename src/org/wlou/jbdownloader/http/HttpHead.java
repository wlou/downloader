package org.wlou.jbdownloader.http;

import java.net.URL;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class HttpHead {

    public final static String METHOD_NAME = "HEAD";

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
        request.append(String.format("%s: %s",Http.CONNECTION_DIRECTIVE, Http.CONNECTION_CLOSE));
        request.append((char)Http.CR);
        request.append((char)Http.LF);
        request.append((char)Http.CR);
        request.append((char)Http.LF);

        return request.toString();
    }

    /**
     * @param response
     * @return
     */
    public static Map<String, String> parseResponse(String response) throws ParseException {
        assert response != null;

        int parsePos = 0;
        String[] headers = lineSplitter.split(response);
        if (headers == null || headers.length < 1)
            throw new ParseException(response, parsePos);

        parsePos += headers[0].length();
        String[] status = spaceSplitter.split(headers[0]);
        if (status.length != 3) // Status-Line = HTTP-Version SP Status-Code SP Reason-Phrase CRLF
            throw new ParseException(response, parsePos);

        Map<String, String> result = new HashMap<>();
        result.put(Http.CODE_KEY, status[1]);

        for (int i = 1; i < headers.length; ++i) {
            parsePos += headers[i].length();
            String[] kv = kvSplitter.split(headers[i].trim());
            if (kv.length != 2) // "Content-Length" ":" 1*DIGIT
                throw new ParseException(response, parsePos);
            result.put(kv[0].trim(), kv[1].trim());
        }
        return result;
    }

    private static final Pattern spaceSplitter = Pattern.compile(String.valueOf(Http.SPACE), Pattern.LITERAL);
    private static final Pattern lineSplitter = Pattern.compile(String.valueOf(Http.LF), Pattern.LITERAL);
    private static final Pattern kvSplitter = Pattern.compile(String.valueOf(Http.COLON), Pattern.LITERAL);

}
