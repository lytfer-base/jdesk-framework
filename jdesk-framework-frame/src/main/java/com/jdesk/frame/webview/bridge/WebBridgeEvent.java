package com.jdesk.frame.webview.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Objects;

/** One-way event from Java to the page: {@code event} is the name pages subscribe to, {@code payload} its data. */
public final class WebBridgeEvent {
    private final String event;
    private final JsonNode payload;

    public WebBridgeEvent(String event, JsonNode payload) {
        this.event = normalizeEvent(event);
        this.payload = payload == null ? NullNode.instance : payload;
    }

    public String event() {
        return event;
    }

    public JsonNode payload() {
        return payload;
    }

    private static String normalizeEvent(String event) {
        String value = Objects.toString(event, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("WebBridge event is required.");
        }
        return value;
    }
}
