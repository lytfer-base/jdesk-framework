package com.jdesk.frame.webview.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Objects;

/** A response to the page in the stable {@code ok/result/error} shape; transports hide backend differences. */
public final class WebBridgeResponse {
    private final String id;
    private final boolean ok;
    private final JsonNode result;
    private final WebBridgeError error;

    private WebBridgeResponse(String id, boolean ok, JsonNode result, WebBridgeError error) {
        this.id = Objects.toString(id, "");
        this.ok = ok;
        this.result = result == null ? NullNode.instance : result;
        this.error = error;
    }

    /** Creates a success response; the page's promise resolves with {@code result}. */
    public static WebBridgeResponse success(String id, JsonNode result) {
        return new WebBridgeResponse(id, true, result, null);
    }

    /** Creates a failure response; the page's promise rejects with {@code error}. */
    public static WebBridgeResponse failure(String id, WebBridgeError error) {
        return new WebBridgeResponse(id, false, NullNode.instance,
                Objects.requireNonNull(error, "error"));
    }

    public String id() {
        return id;
    }

    public boolean ok() {
        return ok;
    }

    public JsonNode result() {
        return result;
    }

    public WebBridgeError error() {
        return error;
    }
}
