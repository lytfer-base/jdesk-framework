package com.jdesk.frame.webview.bridge.impl;

import com.jdesk.frame.webview.bridge.WebBridgeError;
import com.jdesk.frame.webview.bridge.WebBridgeErrorCode;
import com.jdesk.frame.webview.bridge.WebBridgeEvent;
import com.jdesk.frame.webview.bridge.WebBridgeRequest;
import com.jdesk.frame.webview.bridge.WebBridgeResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebBridgeCodecTest {

    private final WebBridgeCodec codec = new WebBridgeCodec();

    @Test
    void decodesRequestJson() throws IOException {
        WebBridgeRequest request = codec.decodeRequest("""
                {"id":"1","method":"records.create","params":{"body":"hello"}}
                """, "app://records", "http://localhost:5173");

        assertEquals("1", request.id());
        assertEquals("records.create", request.method());
        assertEquals("hello", request.params().get("body").asText());
        assertEquals("app://records", request.origin());
        assertEquals("http://localhost:5173", request.url());
    }

    @Test
    void malformedRequestBecomesInvalidRequest() {
        WebBridgeResponse response = codec.decodeFailure("broken", new IOException("bad json"));

        assertFalse(response.ok());
        assertEquals(WebBridgeErrorCode.INVALID_REQUEST, response.error().code());
    }

    /** A decode failure keeps the request id; with an empty id the page's Promise would never settle. */
    @Test
    void decodeFailureKeepsTheRequestId() {
        String truncated = "{\"id\":\"jdesk-7\",\"method\":\"files.write\",\"params\":{\"text\":\"abc";

        WebBridgeResponse response = codec.decodeFailure(truncated, new IOException("unexpected end of input"));

        assertEquals("jdesk-7", response.id());
    }

    /** A request over the string length limit fails with its own id instead of hanging; the test shrinks the limit. */
    @Test
    void oversizeRequestFailsWithItsOwnId() {
        WebBridgeCodec tiny = new WebBridgeCodec(64);
        String huge = "{\"id\":\"jdesk-9\",\"method\":\"files.write\",\"params\":{\"text\":\""
                + "x".repeat(200) + "\"}}";

        IOException failure = assertThrows(IOException.class,
                () -> tiny.decodeRequest(huge, "", "http://myapp.localhost/"));
        WebBridgeResponse response = tiny.decodeFailure(huge, failure);

        assertEquals("jdesk-9", response.id());
        assertFalse(response.ok());
        assertTrue(response.error().message().contains("too large"), response.error().message());
    }

    @Test
    void encodesSuccessAndFailureResponses() {
        WebBridgeResponse success = WebBridgeResponse.success("1",
                codec.objectMapper().createObjectNode().put("id", 1001));
        WebBridgeResponse failure = WebBridgeResponse.failure("2",
                WebBridgeError.of(WebBridgeErrorCode.METHOD_NOT_FOUND, "missing"));

        String successJson = codec.encodeResponse(success);
        String failureJson = codec.encodeResponse(failure);

        assertTrue(successJson.contains("\"ok\":true"));
        assertTrue(successJson.contains("\"id\":1001"));
        assertTrue(failureJson.contains("\"ok\":false"));
        assertTrue(failureJson.contains("\"METHOD_NOT_FOUND\""));
    }

    @Test
    void encodesEventsForFrontendListeners() {
        WebBridgeEvent event = new WebBridgeEvent("records.changed",
                codec.objectMapper().createObjectNode().put("count", 3));

        String eventJson = codec.encodeEvent(event);

        assertTrue(eventJson.contains("\"event\":\"records.changed\""));
        assertTrue(eventJson.contains("\"count\":3"));
    }
}
