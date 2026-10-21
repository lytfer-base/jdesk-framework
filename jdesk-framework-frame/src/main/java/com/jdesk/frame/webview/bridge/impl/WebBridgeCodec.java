package com.jdesk.frame.webview.bridge.impl;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jdesk.frame.webview.bridge.WebBridgeError;
import com.jdesk.frame.webview.bridge.WebBridgeErrorCode;
import com.jdesk.frame.webview.bridge.WebBridgeEvent;
import com.jdesk.frame.webview.bridge.WebBridgeRequest;
import com.jdesk.frame.webview.bridge.WebBridgeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * JSON codec for the WebBridge protocol: turns the strings transports carry into requests and responses, and keeps
 * the response shape stable for pages.
 */
public final class WebBridgeCodec {

    private static final Logger LOG = LoggerFactory.getLogger(WebBridgeCodec.class);

    /** Longest accepted JSON string, which caps a request; pinned since Jackson's default varies by version. */
    static final int MAX_STRING_LENGTH = 20_000_000;

    private final ObjectMapper objectMapper;

    public WebBridgeCodec() {
        this(MAX_STRING_LENGTH);
    }

    /** For tests: a small limit reaches the too-large path without building huge strings. */
    WebBridgeCodec(int maxStringLength) {
        this.objectMapper = new ObjectMapper(JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxStringLength(maxStringLength)
                        .build())
                .build());
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    /** Decodes a page request, adding the origin and URL the browser backend reports. */
    public WebBridgeRequest decodeRequest(String json, String origin, String url) throws IOException {
        JsonNode root = objectMapper.readTree(json == null ? "" : json);
        WebBridgeRequest request = new WebBridgeRequest(
                text(root, "id"),
                text(root, "method"),
                root.path("params").isMissingNode() ? JsonNodeFactory.instance.objectNode() : root.path("params"),
                origin,
                url);
        request.validate();
        return request;
    }

    /**
     * Builds the error response for a request that failed to decode, recovering its id if possible, since with an
     * empty id the page's promise would never settle.
     * Don't log the raw request: it may hold user content or secrets.
     */
    public WebBridgeResponse decodeFailure(String rawRequest, Throwable failure) {
        String id = recoverId(rawRequest);
        int length = rawRequest == null ? 0 : rawRequest.length();
        LOG.warn("webbridge.decode.failed length={} id={} reason={}", length,
                id.isEmpty() ? "<unknown>" : id, failure == null ? "unknown" : failure.getClass().getSimpleName());
        LOG.debug("webbridge.decode.failed detail", failure);
        boolean tooLarge = failure instanceof StreamConstraintsException;
        return WebBridgeResponse.failure(id, WebBridgeError.of(
                WebBridgeErrorCode.INVALID_REQUEST,
                tooLarge ? "WebBridge request is too large (" + length + " chars)." : "Invalid WebBridge request."));
    }

    /** Streams until the top-level id, which comes first, so an oversized string later on doesn't matter. */
    private String recoverId(String rawRequest) {
        if (rawRequest == null || rawRequest.isEmpty()) {
            return "";
        }
        try (JsonParser parser = objectMapper.getFactory().createParser(rawRequest)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return "";
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("id".equals(field)) {
                    return value == JsonToken.VALUE_STRING ? parser.getText() : "";
                }
                if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                    parser.skipChildren();
                }
            }
        } catch (IOException | RuntimeException unrecoverable) {
            LOG.debug("webbridge.decode.idUnrecoverable", unrecoverable);
        }
        return "";
    }

    /** Encodes a response as JSON for the page. */
    public String encodeResponse(WebBridgeResponse response) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", response.id());
        root.put("ok", response.ok());
        if (response.ok()) {
            root.set("result", response.result());
        } else {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("code", response.error().code().name());
            error.put("message", response.error().message());
            error.set("details", response.error().details());
            root.set("error", error);
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode WebBridge response.", e);
        }
    }

    /** Encodes an event as JSON for the page's {@code __jdeskBridgeEmit}. */
    public String encodeEvent(WebBridgeEvent event) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("event", event.event());
        root.set("payload", event.payload());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode WebBridge event.", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : "";
    }
}
