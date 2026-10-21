package com.jdesk.frame.webview.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jdesk.core.i18n.LocalizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * Error of a failed WebBridge response: {@code code} is the stable error type for pages, {@code message} is for
 * developers, and {@code details} carries a {@link LocalizedException}'s {@code reason} and {@code params}, from which
 * pages pick their text.
 */
public final class WebBridgeError {
    private static final Logger LOG = LoggerFactory.getLogger(WebBridgeError.class);
    private static final ObjectMapper DETAILS_MAPPER = new ObjectMapper();

    private final WebBridgeErrorCode code;
    private final String message;
    private final JsonNode details;

    public WebBridgeError(WebBridgeErrorCode code, String message, JsonNode details) {
        this.code = Objects.requireNonNull(code, "code");
        this.message = message == null ? "" : message;
        this.details = details == null ? JsonNodeFactory.instance.objectNode() : details;
    }

    public WebBridgeErrorCode code() {
        return code;
    }

    public String message() {
        return message;
    }

    public JsonNode details() {
        return details;
    }

    /** Creates an error without details. */
    public static WebBridgeError of(WebBridgeErrorCode code, String message) {
        return new WebBridgeError(code, message, JsonNodeFactory.instance.objectNode());
    }

    /**
     * Creates an error from an exception, unwrapping CompletionException: a LocalizedException's reason and params go
     * into details, dropped with a WARN if building them fails; the message is the exception's own, or empty.
     * Don't fill an empty message with the class name: pages fall back to their own text.
     */
    public static WebBridgeError of(WebBridgeErrorCode code, Throwable failure) {
        Throwable actual = Objects.requireNonNull(failure, "failure");
        while (actual instanceof CompletionException && actual.getCause() != null) {
            actual = actual.getCause();
        }
        String message = actual.getMessage() == null || actual.getMessage().isBlank() ? "" : actual.getMessage();
        if (actual instanceof LocalizedException localized) {
            try {
                ObjectNode details = JsonNodeFactory.instance.objectNode();
                details.put("reason", localized.reason());
                details.set("params", paramsNode(localized.params()));
                return new WebBridgeError(code, message, details);
            } catch (RuntimeException detailsFailure) {
                LOG.warn("webbridge.error.details.failed reason={}", localized.reason(), detailsFailure);
            }
        }
        return of(code, message);
    }

    /**
     * Keeps numbers and booleans as they are, since plurals select by count, and turns the rest into display text.
     * Don't hand the whole map to Jackson: a type it can't serialize, such as LocalDate, loses the entire reason.
     */
    private static ObjectNode paramsNode(Map<String, Object> params) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        params.forEach((name, value) -> {
            if (value instanceof Number || value instanceof Boolean) {
                node.set(name, DETAILS_MAPPER.valueToTree(value));
            } else {
                node.put(name, String.valueOf(value));
            }
        });
        return node;
    }
}
