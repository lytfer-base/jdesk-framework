package com.jdesk.frame.acp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Envelope of a {@code session/update} notification: the session id, the update kind (its {@code sessionUpdate}
 * field) and the update itself.
 */
public record AcpSessionUpdate(String sessionId, String kind, JsonNode update) {

    /** Parses the notification params; missing fields become empty values instead of errors, since agents vary. */
    public static AcpSessionUpdate from(JsonNode params) {
        JsonNode update = params.path("update");
        return new AcpSessionUpdate(
                params.path("sessionId").asText(""),
                update.path("sessionUpdate").asText(""),
                update);
    }
}
