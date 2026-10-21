package com.jdesk.core.startup;

/**
 * Closeable timing span used by startup instrumentation.
 */
public final class StartupSpan implements AutoCloseable {

    private static final StartupSpan NOOP = new StartupSpan(null, null, null);

    private final StartupTrace trace;
    private final StartupEvent event;
    private final Integer previousParentId;
    private boolean closed;
    private Throwable failure;

    StartupSpan(StartupTrace trace, StartupEvent event, Integer previousParentId) {
        this.trace = trace;
        this.event = event;
        this.previousParentId = previousParentId;
    }

    static StartupSpan noop() {
        return NOOP;
    }

    public int getId() {
        return event == null ? -1 : event.getId();
    }

    public void detail(String key, Object value) {
        if (event != null) {
            event.putDetail(key, value);
        }
    }

    public void failed(Throwable failure) {
        this.failure = failure;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (trace != null) {
            trace.closeSpan(this, failure);
        }
    }

    StartupEvent event() {
        return event;
    }

    Integer previousParentId() {
        return previousParentId;
    }
}
