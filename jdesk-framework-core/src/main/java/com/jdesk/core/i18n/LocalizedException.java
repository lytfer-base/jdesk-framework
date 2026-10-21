package com.jdesk.core.i18n;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * A user-facing failure: {@code reason} is a message key and {@code params} fill its placeholders, while
 * {@code getMessage()} is English text for developers and logs.
 */
public class LocalizedException extends RuntimeException {
    private final String reason;
    private final Map<String, Object> params;

    public LocalizedException(String reason, Map<String, ?> params, String developerMessage) {
        this(reason, params, developerMessage, null);
    }

    public LocalizedException(String reason, Map<String, ?> params, String developerMessage, Throwable cause) {
        super(developerMessage, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.params = copyParams(params);
    }

    public String reason() {
        return reason;
    }

    /**
     * Returns the cause-less LocalizedException inside any CompletionException or ExecutionException wrappers, or
     * null. Such a rejection follows from user state, such as a deleted file: log one WARN line, not a stack trace.
     */
    public static LocalizedException rejectionOf(Throwable failure) {
        Throwable actual = failure;
        while ((actual instanceof CompletionException || actual instanceof ExecutionException) && actual.getCause() != null) {
            actual = actual.getCause();
        }
        return actual instanceof LocalizedException localized && localized.getCause() == null ? localized : null;
    }

    /** Returns a read-only copy of the parameters: null values become empty strings and null names are dropped. */
    public Map<String, Object> params() {
        return params;
    }

    /**
     * Returns the message in the current UI language. Don't show the inherited {@link #getLocalizedMessage()} to
     * users: it still returns the English developer message.
     */
    public String localizedMessage() {
        return I18n.message(reason, params);
    }

    private static Map<String, Object> copyParams(Map<String, ?> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        params.forEach((name, value) -> {
            if (name != null) {
                copy.put(name, value == null ? "" : value);
            }
        });
        return Collections.unmodifiableMap(copy);
    }
}
