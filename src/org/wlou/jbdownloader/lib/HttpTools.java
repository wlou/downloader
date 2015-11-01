package org.wlou.jbdownloader.lib;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** The <code>HttpTools</code> provides auxiliary functions to work with Http protocol.
 *
 *  <p>Class contains set of frequently used constants and static
 *  methods for handling Http requests and responses.
 *
 **/
public final class HttpTools {
    public static final int CR = 13;
    public static final int LF = 10;
    public static final int SPACE = 32;
    public static final int COLON = 58;

    public static final int DEFAULT_PORT = 80;

    public static final String DEFAULT_VERION = "HTTP/1.1";

    public static final String CODE_KEY = "Response-Code";
    public static final String CONTENT_LENGTH_KEY = "Content-Length";

    public static final String TARGET_HOST = "Host";
    public static final String CONNECTION_DIRECTIVE = "Connection";
    public static final String CONNECTION_CLOSE = "Close";
    public static final String CONNECTION_KEEP_ALIVE = "Keep-Alive";

    public static final String ISO_8859_1 = "ISO-8859-1";

    public static final String DEFAULT_CONTENT_CHARSET = ISO_8859_1;

    /**
     * Prepares Http GET request from set of parameters.
     * @param url   a <code>Url</code> target url for requesting
     * @param params    a <code>Map<String, String></code> custom Http headers
     * @return  <code>String</code> containing formatted Http GET request
     */
    public static String makeGetRequest(URL url, Map<String, String> params) {
        final String METHOD_NAME = "GET";
        String protocol = url.getProtocol();
        if (!protocol.equals("http")) // TODO:
            throw new UnsupportedOperationException("Only http protocol is supported");
        return formHtpRequest(METHOD_NAME, url, params).toString();
    }

    /**
     * Prepares Http HEAD request from set of parameters.
     * @param url a <code>Url</code> target url for requesting
     * @param params a <code>Map<String, String></code> custom Http headers
     * @return  <code>String</code> containing formatted Http HEAD request
     * @throws MalformedURLException when the protocol of the <code>url</code> is unsupported (not http)
     */
    public static String makeHeadRequest(URL url, Map<String, String> params) throws MalformedURLException {
        final String METHOD_NAME = "HEAD";
        String protocol = url.getProtocol();
        if (!protocol.equals("http")) // TODO:
            throw new MalformedURLException("Only http protocol is supported");
        return formHtpRequest(METHOD_NAME, url, params).toString();
    }

    private static StringBuilder formHtpRequest(String METHOD, URL url, Map<String, String> params) {
        StringBuilder request = new StringBuilder();
        request.append(String.format("%s %s %s", METHOD, url.toString(), HttpTools.DEFAULT_VERION));
        request.append((char) HttpTools.CR);
        request.append((char) HttpTools.LF);
        request.append(String.format("%s: %s", HttpTools.TARGET_HOST, url.getAuthority()));
        request.append((char) HttpTools.CR);
        request.append((char) HttpTools.LF);
        String connDirectiveValue = HttpTools.CONNECTION_KEEP_ALIVE;
        if (params != null && params.containsKey(HttpTools.CONNECTION_DIRECTIVE))
            connDirectiveValue = params.get(HttpTools.CONNECTION_DIRECTIVE);
        request.append(String.format("%s: %s", HttpTools.CONNECTION_DIRECTIVE, connDirectiveValue));
        request.append((char) HttpTools.CR);
        request.append((char) HttpTools.LF);
        request.append((char) HttpTools.CR);
        request.append((char) HttpTools.LF);
        return request;
    }

    /**
     * Parses Http HEAD response from string to set of Key&lt-&gtValue pairs of headers.
     * @param response  a <code>String</code> containing Http HEAD response from a server
     * @return  parsed Http headers as Key&lt-&gtValue pairs
     * @throws ParseException if error the response is ill-formatted
     */
    public static Map<String, String> parseHeadResponse(String response) throws ParseException {
        assert response != null;

        String[] headers = lineSplitter.split(response);
        int parsePos = headers[0].length();

        String[] status = spaceSplitter.split(headers[0].trim());
        if (status.length < 3) // Status-Line = HTTP-Version SP Status-Code SP Reason-Phrase CRLF
            throw new ParseException(String.format("Can't parse status line: \"%s\"", headers[0].trim()), parsePos);

        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        result.put(HttpTools.CODE_KEY, status[1]);

        for (int i = 1; i < headers.length; ++i) {
            parsePos += headers[i].length();

            if (i == headers.length - 1 && headers[i].trim().isEmpty())
                continue;

            String[] kv = kvSplitter.split(headers[i].trim());
            if (kv.length < 2) // "Content-Length" ":" 1*DIGIT
                throw new ParseException(String.format("Can't parse header[%d]: \"%s\"", i, headers[i].trim()), parsePos);

            String key = kv[0].trim();
            String value = String.join("", Arrays.copyOfRange(kv, 1, kv.length)).trim();
            result.put(key, value);
        }
        return result;
    }

    private static final Pattern spaceSplitter = Pattern.compile(String.valueOf((char) HttpTools.SPACE), Pattern.LITERAL);
    private static final Pattern lineSplitter = Pattern.compile(String.valueOf((char) HttpTools.LF), Pattern.LITERAL);
    private static final Pattern kvSplitter = Pattern.compile(String.valueOf((char) HttpTools.COLON), Pattern.LITERAL);
}
