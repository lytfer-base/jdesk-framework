package com.jdesk.frame.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Payload of an agent's {@code session/request_permission} request, and the builder of its answer. Option kinds are
 * allow_once, allow_always, reject_once and reject_always.
 */
public record AcpPermissionRequest(String sessionId, String toolCallTitle, JsonNode toolCall,
                                   List<Option> options) {

    /** One option the user can pick; {@code kind} is one of the kinds listed on the class. */
    public record Option(String optionId, String kind) {
    }

    /** Parses the request params; missing fields become empty values instead of errors, since agents vary. */
    public static AcpPermissionRequest from(JsonNode params) {
        List<Option> options = new ArrayList<>();
        JsonNode optionsNode = params.path("options");
        if (optionsNode.isArray()) {
            for (JsonNode option : optionsNode) {
                options.add(new Option(
                        option.path("optionId").asText(""),
                        option.path("kind").asText("")));
            }
        }
        JsonNode toolCall = params.path("toolCall");
        return new AcpPermissionRequest(
                params.path("sessionId").asText(""),
                toolCall.path("title").asText(""),
                toolCall.isMissingNode() ? null : toolCall,
                List.copyOf(options));
    }

    /** Builds the answer's result: {@code selected} with {@code optionId}, or {@code cancelled} when it is null. */
    public static ObjectNode outcomeSelected(String optionId, ObjectMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode outcome = result.putObject("outcome");
        if (optionId == null) {
            outcome.put("outcome", "cancelled");
        } else {
            outcome.put("outcome", "selected");
            outcome.put("optionId", optionId);
        }
        return result;
    }
}
