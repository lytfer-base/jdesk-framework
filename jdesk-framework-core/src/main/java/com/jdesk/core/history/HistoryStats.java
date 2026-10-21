package com.jdesk.core.history;

import java.util.Objects;

/** Usage summary of a history store; a non-null {@code unavailableReason} means history is unavailable for now. */
public record HistoryStats(long objectBytes, long versionCount, HistoryUnavailableReason unavailableReason) {

    public static HistoryStats unavailable(HistoryUnavailableReason reason) {
        return new HistoryStats(0, 0, Objects.requireNonNull(reason, "reason"));
    }

    public boolean available() {
        return unavailableReason == null;
    }
}
