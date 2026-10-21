package com.jdesk.frame.webview.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Objects;

/** A request from the page: id, method and params, plus the origin and URL of the requesting page. */
public final class WebBridgeRequest {
    private final String id;
    private final String method;
    private final JsonNode params;
    private final String origin;
    private final String url;

    public WebBridgeRequest(String id, String method, JsonNode params, String origin, String url) {
        this.id = normalize(id);
        this.method = normalize(method);
        this.params = params == null ? JsonNodeFactory.instance.objectNode() : params;
        this.origin = normalize(origin);
        this.url = normalize(url);
    }

    public String id() {
        return id;
    }

    public String method() {
        return method;
    }

    public JsonNode params() {
        return params;
    }

    public String origin() {
        return origin;
    }

    public String url() {
        return url;
    }

    /** Checks the required protocol fields; handlers validate their own params. */
    public void validate() {
        if (id.isBlank()) {
            throw new IllegalArgumentException("WebBridge request id is required.");
        }
        if (method.isBlank()) {
            throw new IllegalArgumentException("WebBridge method is required.");
        }
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").trim();
    }
}
