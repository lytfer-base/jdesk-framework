package com.jdesk.core.history;

import java.util.Objects;

/** Groups versions under a name and source with start and end times; a null {@code endedAt} means it is still open. */
public record HistoryActivity(long activityId, String name, HistorySource source, long startedAt, Long endedAt) {

    public HistoryActivity {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
    }
}
