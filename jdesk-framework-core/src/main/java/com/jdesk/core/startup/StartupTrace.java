package com.jdesk.core.startup;

import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.core.runtime.params.CoreRuntimeParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records startup timing spans and counters with low ceremony.
 */
public final class StartupTrace {

    private static final Logger LOG = LoggerFactory.getLogger(StartupTrace.class);
    private static final StartupTrace DISABLED = new StartupTrace(StartupTraceMode.OFF);

    private final StartupTraceMode mode;
    private final long startedAtEpochMillis;
    private final long startNanos;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final List<StartupEvent> events = new ArrayList<>();
    private final ConcurrentMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final AtomicLong firstWindowVisibleNanos = new AtomicLong(-1L);
    private final ThreadLocal<Integer> currentParentId = new ThreadLocal<>();

    public StartupTrace() {
        this(StartupTraceMode.TIME);
    }

    private StartupTrace(StartupTraceMode mode) {
        this.mode = mode == null ? StartupTraceMode.TIME : mode;
        this.startedAtEpochMillis = System.currentTimeMillis();
        this.startNanos = System.nanoTime();
    }

    public static StartupTrace create(StartupTraceMode mode) {
        return mode == StartupTraceMode.OFF ? disabled() : new StartupTrace(mode);
    }

    public static StartupTrace create() {
        String rawMode = RuntimeParamRegistry.getString(CoreRuntimeParams.STARTUP_TRACE_MODE, StartupTraceMode.TIME.configValue());
        try {
            return create(StartupTraceMode.parse(rawMode));
        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid startup trace mode '{}' from runtime parameters; using default '{}'",
                    rawMode, StartupTraceMode.TIME.configValue());
            return create(StartupTraceMode.TIME);
        }
    }

    public static StartupTrace report() {
        return new StartupTrace(StartupTraceMode.REPORT);
    }

    public static StartupTrace disabled() {
        return DISABLED;
    }

    public boolean isEnabled() {
        return !mode.isOff();
    }

    public boolean isReportEnabled() {
        return mode.outputsReport();
    }

    public boolean isTimeEnabled() {
        return mode.outputsTotalTime();
    }

    public boolean isOff() {
        return mode.isOff();
    }

    public StartupTraceMode getMode() {
        return mode;
    }

    public long getStartedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    public StartupSpan begin(String name) {
        return begin(name, Map.of());
    }

    public StartupSpan begin(String name, Map<String, String> details) {
        return begin(name, details, null);
    }

    public StartupSpan beginChild(Integer parentId, String name, Map<String, String> details) {
        return begin(name, details, parentId);
    }

    private StartupSpan begin(String name, Map<String, String> details, Integer explicitParentId) {
        if (!mode.recordsSpans()) {
            return StartupSpan.noop();
        }
        long now = System.nanoTime();
        Integer previousParent = currentParentId.get();
        Integer parentId = explicitParentId != null ? explicitParentId : previousParent;
        StartupEvent event = new StartupEvent(
                nextId.getAndIncrement(),
                parentId,
                name,
                now,
                (now - startNanos) / 1_000_000L,
                Thread.currentThread().getName(),
                details == null ? Map.of() : details);
        synchronized (events) {
            events.add(event);
        }
        currentParentId.set(event.getId());
        return new StartupSpan(this, event, previousParent);
    }

    void closeSpan(StartupSpan span, Throwable failure) {
        StartupEvent event = span.event();
        if (event == null) {
            return;
        }
        event.finish(failure);
        Integer previous = span.previousParentId();
        if (previous == null) {
            currentParentId.remove();
        } else {
            currentParentId.set(previous);
        }
    }

    public Integer getCurrentSpanId() {
        return mode.recordsSpans() ? currentParentId.get() : null;
    }

    Integer installCurrentSpanForCoroutine(Integer parentId) {
        Integer previousParent = currentParentId.get();
        if (parentId == null) {
            currentParentId.remove();
        } else {
            currentParentId.set(parentId);
        }
        return previousParent;
    }

    void restoreCurrentSpanForCoroutine(Integer previousParentId) {
        if (previousParentId == null) {
            currentParentId.remove();
        } else {
            currentParentId.set(previousParentId);
        }
    }

    public <T> T withParentSpan(Integer parentId, Callable<T> action) throws Exception {
        Integer previousParent = currentParentId.get();
        if (parentId == null) {
            currentParentId.remove();
        } else {
            currentParentId.set(parentId);
        }
        try {
            return action.call();
        } finally {
            if (previousParent == null) {
                currentParentId.remove();
            } else {
                currentParentId.set(previousParent);
            }
        }
    }

    public void withParentSpan(Integer parentId, Runnable action) {
        try {
            withParentSpan(parentId, () -> {
                action.run();
                return null;
            });
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public <T> T runSpan(String name, StartupSpanAction<T> action) throws Exception {
        return runSpan(name, Map.of(), action);
    }

    public <T> T runSpan(String name, Map<String, String> details, StartupSpanAction<T> action) throws Exception {
        try (StartupSpan span = begin(name, details)) {
            try {
                return action.run(span);
            } catch (Exception | Error e) {
                span.failed(e);
                throw e;
            }
        }
    }

    public void runSpan(String name, Runnable action) {
        runSpan(name, Map.of(), action);
    }

    public void runSpan(String name, Map<String, String> details, Runnable action) {
        try {
            runSpan(name, details, span -> {
                action.run();
                return null;
            });
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public <T> Callable<T> task(Integer parentId, String name, Callable<T> action) {
        return () -> {
            try (StartupSpan span = beginChild(parentId, name, Map.of())) {
                try {
                    return action.call();
                } catch (Exception | Error e) {
                    span.failed(e);
                    throw e;
                }
            }
        };
    }

    public Runnable task(Integer parentId, String name, Runnable action) {
        return () -> {
            try (StartupSpan span = beginChild(parentId, name, Map.of())) {
                try {
                    action.run();
                } catch (RuntimeException | Error e) {
                    span.failed(e);
                    throw e;
                }
            }
        };
    }

    public void incrementCounter(String name) {
        addCounter(name, 1L);
    }

    public void addCounter(String name, long delta) {
        if (!mode.recordsSpans() || name == null || name.isBlank()) {
            return;
        }
        counters.computeIfAbsent(name, key -> new AtomicLong()).addAndGet(delta);
    }

    public void markFirstWindowVisible() {
        if (mode.recordsSpans()) {
            firstWindowVisibleNanos.compareAndSet(-1L, System.nanoTime());
        }
    }

    public long getTotalMs() {
        return mode.isOff() ? 0L : (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public long getFirstWindowVisibleMs() {
        long nanos = firstWindowVisibleNanos.get();
        return nanos < 0L ? -1L : (nanos - startNanos) / 1_000_000L;
    }

    public List<StartupEvent> getEvents() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public Map<String, Long> getCounters() {
        Map<String, Long> result = new LinkedHashMap<>();
        counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().get()));
        return result;
    }

    public List<StartupEvent> getSlowestEvents(int limit) {
        return getEvents().stream()
                .filter(event -> !"RUNNING".equals(event.getStatus()))
                .sorted(Comparator.comparingLong(StartupEvent::getDurationMs).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    public String summary() {
        if (mode.isOff()) {
            return "startup trace disabled";
        }
        if (mode.outputsTotalTime()) {
            return "Startup time: total=" + getTotalMs() + "ms";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Startup trace: total=").append(getTotalMs()).append("ms");
        long visibleMs = getFirstWindowVisibleMs();
        if (visibleMs >= 0L) {
            sb.append(", firstWindowVisible=").append(visibleMs).append("ms");
        }
        Map<String, Long> countersSnapshot = getCounters();
        if (!countersSnapshot.isEmpty()) {
            sb.append(", counters=").append(countersSnapshot);
        }
        List<StartupEvent> slowest = getSlowestEvents(8);
        if (!slowest.isEmpty()) {
            sb.append(System.lineSeparator()).append("Slow startup spans:");
            for (StartupEvent event : slowest) {
                sb.append(System.lineSeparator())
                        .append("  ")
                        .append(event.getName())
                        .append(" ")
                        .append(event.getDurationMs())
                        .append("ms");
            }
        }
        return sb.toString();
    }
}
