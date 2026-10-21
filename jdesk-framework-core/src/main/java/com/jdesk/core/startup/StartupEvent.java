package com.jdesk.core.startup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable identity plus mutable completion data for one startup timing event.
 */
public final class StartupEvent {

    private final int id;
    private final Integer parentId;
    private final String name;
    private final long startNanos;
    private final long startMs;
    private final String threadName;
    private final Map<String, String> details;

    private volatile long endNanos;
    private volatile String status = "RUNNING";
    private volatile String error;

    StartupEvent(int id,
                 Integer parentId,
                 String name,
                 long startNanos,
                 long startMs,
                 String threadName,
                 Map<String, String> details) {
        this.id = id;
        this.parentId = parentId;
        this.name = name;
        this.startNanos = startNanos;
        this.startMs = startMs;
        this.threadName = threadName;
        this.details = new LinkedHashMap<>(details);
    }

    public int getId() {
        return id;
    }

    public Integer getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getDurationMs() {
        long end = endNanos == 0L ? System.nanoTime() : endNanos;
        return Math.max(0L, (end - startNanos) / 1_000_000L);
    }

    public String getThreadName() {
        return threadName;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public synchronized Map<String, String> getDetails() {
        return Map.copyOf(details);
    }

    synchronized void putDetail(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        details.put(key, String.valueOf(value));
    }

    synchronized void finish(Throwable failure) {
        if (endNanos != 0L) {
            return;
        }
        endNanos = System.nanoTime();
        if (failure == null) {
            status = "OK";
        } else {
            status = "ERROR";
            error = failure.getClass().getName() + ": " + failure.getMessage();
        }
    }
}
