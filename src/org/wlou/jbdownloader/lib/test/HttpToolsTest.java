package org.wlou.jbdownloader.lib.test;

import junit.framework.TestCase;
import org.wlou.jbdownloader.lib.HttpTools;

import java.util.Map;

public class HttpToolsTest extends TestCase {

    public static final String _200_HEADER_1K_BYTES =
        "HTTP/1.1 200 OK\r\n" +
        String.format("%s: %s\r\n", HttpHandlerStub.SERVER_KEY, HttpHandlerStub.SERVER_VAL) +
        String.format("%s: %s\r\n", HttpHandlerStub.CT_KEY, HttpHandlerStub.CT_VAL) +
        String.format("%s: %s\r\n", HttpHandlerStub.CL_KEY, HttpHandlerStub._1K_ZEROS.length) +
        "\r\n";


    public void testMakeGetRequest() throws Exception {
        //TODO: missed, because of demo project
    }

    public void testMakeHeadRequest() throws Exception {
        //TODO: missed, because of demo project
    }

    public void testParseHeadResponse() throws Exception {
        Map<String, String> result = HttpTools.parseHeadResponse(_200_HEADER_1K_BYTES);
        assertTrue(result.containsKey(HttpHandlerStub.SERVER_KEY));
        assertTrue(result.containsKey(HttpHandlerStub.CT_KEY));
        assertTrue(result.containsKey(HttpHandlerStub.CL_KEY));
        assertEquals(result.get(HttpTools.CODE_KEY), "200");
    }
}