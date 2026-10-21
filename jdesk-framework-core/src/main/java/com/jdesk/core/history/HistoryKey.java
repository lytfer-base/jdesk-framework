package com.jdesk.core.history;

import java.util.Objects;

/**
 * Identity of a versioned object in history: {@code kind} names the namespace of the {@code id}, such as files; the
 * framework treats both as plain strings.
 */
public record HistoryKey(String kind, String id) {

    public HistoryKey {
        kind = requireText("kind", kind);
        id = requireText("id", id);
    }

    public static HistoryKey of(String kind, String id) {
        return new HistoryKey(kind, id);
    }

    static String requireText(String label, String value) {
        String cleaned = Objects.requireNonNull(value, label).trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return cleaned;
    }
}
