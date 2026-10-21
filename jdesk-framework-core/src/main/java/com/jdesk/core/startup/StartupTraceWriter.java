package com.jdesk.core.startup;

import com.jdesk.core.app.PathManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes startup traces to the application log directory.
 */
public final class StartupTraceWriter {

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z").withZone(ZoneId.systemDefault());

    private StartupTraceWriter() {
    }

    public static void writeIfEnabled(StartupTrace trace) {
        if (trace == null || !trace.isReportEnabled()) {
            return;
        }
        Path logDir = PathManager.getLogDir();
        String timestamp = FILE_TIME.format(Instant.now());
        String json = toJson(trace);
        String html = toHtml(trace);
        try {
            Files.createDirectories(logDir);
            Files.writeString(logDir.resolve("startup-trace-latest.json"), json);
            Files.writeString(logDir.resolve("startup-trace-latest.html"), html);
            Files.writeString(logDir.resolve("startup-trace-" + timestamp + ".json"), json);
            Files.writeString(logDir.resolve("startup-trace-" + timestamp + ".html"), html);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write startup trace to " + logDir, e);
        }
    }

    public static String toJson(StartupTrace trace) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\n");
        appendField(sb, 1, "startedAtEpochMillis", trace.getStartedAtEpochMillis()).append(",\n");
        appendField(sb, 1, "totalMs", trace.getTotalMs()).append(",\n");
        appendField(sb, 1, "firstWindowVisibleMs", trace.getFirstWindowVisibleMs()).append(",\n");

        indent(sb, 1).append("\"counters\": {");
        boolean firstCounter = true;
        for (Map.Entry<String, Long> entry : trace.getCounters().entrySet()) {
            if (!firstCounter) {
                sb.append(",");
            }
            sb.append("\n");
            indent(sb, 2).append("\"").append(escape(entry.getKey())).append("\": ").append(entry.getValue());
            firstCounter = false;
        }
        if (!firstCounter) {
            sb.append("\n");
            indent(sb, 1);
        }
        sb.append("},\n");

        indent(sb, 1).append("\"events\": [");
        boolean firstEvent = true;
        for (StartupEvent event : trace.getEvents()) {
            if (!firstEvent) {
                sb.append(",");
            }
            sb.append("\n");
            appendEvent(sb, event);
            firstEvent = false;
        }
        if (!firstEvent) {
            sb.append("\n");
            indent(sb, 1);
        }
        sb.append("]\n");
        sb.append("}\n");
        return sb.toString();
    }

    public static String toHtml(StartupTrace trace) {
        List<StartupEvent> events = trace.getEvents();
        Map<Integer, StartupEvent> eventsById = new HashMap<>();
        for (StartupEvent event : events) {
            eventsById.put(event.getId(), event);
        }

        StringBuilder sb = new StringBuilder(16384);
        sb.append("<!doctype html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("  <meta charset=\"utf-8\">\n");
        sb.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("  <title>Startup Trace Report</title>\n");
        appendStyles(sb);
        sb.append("</head>\n");
        sb.append("<body>\n");
        sb.append("<main>\n");
        sb.append("  <header>\n");
        sb.append("    <p class=\"eyebrow\">JDesk Startup Trace</p>\n");
        sb.append("    <h1>Startup Trace Report</h1>\n");
        sb.append("    <p class=\"hint\"><strong>startup-trace-latest.html/json</strong> is overwritten by each run. ");
        sb.append("<strong>startup-trace-&lt;timestamp&gt;.html/json</strong> is the archived copy for comparing runs.</p>\n");
        sb.append("  </header>\n");

        appendSummaryTable(sb, trace, events.size());
        appendCountersTable(sb, trace.getCounters());
        appendThreadSummaryTable(sb, events);
        appendSlowestTable(sb, trace.getSlowestEvents(20));
        appendEventTable(sb, events, eventsById);

        sb.append("</main>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");
        return sb.toString();
    }

    private static void appendStyles(StringBuilder sb) {
        sb.append("  <style>\n");
        sb.append("    :root { color-scheme: light; --bg: #f7f8fa; --panel: #ffffff; --text: #1f2937; --muted: #667085; --line: #d8dee8; --accent: #2563eb; --ok: #047857; --run: #b45309; --err: #b91c1c; }\n");
        sb.append("    * { box-sizing: border-box; }\n");
        sb.append("    body { margin: 0; background: var(--bg); color: var(--text); font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif; line-height: 1.45; }\n");
        sb.append("    main { width: min(1400px, calc(100% - 48px)); margin: 32px auto 56px; }\n");
        sb.append("    header { margin-bottom: 24px; }\n");
        sb.append("    h1 { margin: 0 0 8px; font-size: 32px; line-height: 1.15; letter-spacing: 0; }\n");
        sb.append("    h2 { margin: 28px 0 10px; font-size: 20px; letter-spacing: 0; }\n");
        sb.append("    .eyebrow { margin: 0 0 6px; color: var(--accent); font-size: 12px; font-weight: 700; text-transform: uppercase; }\n");
        sb.append("    .hint { margin: 0; color: var(--muted); max-width: 900px; }\n");
        sb.append("    .table-wrap { overflow-x: auto; background: var(--panel); border: 1px solid var(--line); border-radius: 8px; }\n");
        sb.append("    table { width: 100%; border-collapse: collapse; font-size: 13px; }\n");
        sb.append("    th, td { padding: 9px 12px; border-bottom: 1px solid var(--line); text-align: left; vertical-align: top; }\n");
        sb.append("    th { background: #eef2f7; color: #344054; font-weight: 700; position: sticky; top: 0; }\n");
        sb.append("    tr:last-child td { border-bottom: 0; }\n");
        sb.append("    code { font-family: \"JetBrains Mono\", Consolas, monospace; font-size: 12px; }\n");
        sb.append("    .num { text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; }\n");
        sb.append("    .status { font-weight: 700; white-space: nowrap; }\n");
        sb.append("    .status-ok { color: var(--ok); }\n");
        sb.append("    .status-running { color: var(--run); }\n");
        sb.append("    .status-error { color: var(--err); }\n");
        sb.append("    .details { color: var(--muted); }\n");
        sb.append("    .empty { padding: 16px; color: var(--muted); background: var(--panel); border: 1px solid var(--line); border-radius: 8px; }\n");
        sb.append("    @media (max-width: 720px) { main { width: min(100% - 24px, 1400px); margin-top: 20px; } h1 { font-size: 26px; } th, td { padding: 8px 10px; } }\n");
        sb.append("  </style>\n");
    }

    private static void appendSummaryTable(StringBuilder sb, StartupTrace trace, int eventCount) {
        sb.append("  <section>\n");
        sb.append("    <h2>Summary</h2>\n");
        sb.append("    <div class=\"table-wrap\"><table>\n");
        sb.append("      <thead><tr><th>Metric</th><th>Value</th></tr></thead>\n");
        sb.append("      <tbody>\n");
        appendKeyValueRow(sb, "Started at", DISPLAY_TIME.format(Instant.ofEpochMilli(trace.getStartedAtEpochMillis())));
        appendKeyValueRow(sb, "Total startup", formatMillis(trace.getTotalMs()));
        appendKeyValueRow(sb, "First window visible", formatMillis(trace.getFirstWindowVisibleMs()));
        appendKeyValueRow(sb, "Event count", String.valueOf(eventCount));
        sb.append("      </tbody>\n");
        sb.append("    </table></div>\n");
        sb.append("  </section>\n");
    }

    private static void appendCountersTable(StringBuilder sb, Map<String, Long> counters) {
        sb.append("  <section>\n");
        sb.append("    <h2>Counters</h2>\n");
        if (counters.isEmpty()) {
            sb.append("    <div class=\"empty\">No counters were recorded.</div>\n");
        } else {
            sb.append("    <div class=\"table-wrap\"><table>\n");
            sb.append("      <thead><tr><th>Counter</th><th class=\"num\">Value</th></tr></thead>\n");
            sb.append("      <tbody>\n");
            for (Map.Entry<String, Long> entry : counters.entrySet()) {
                sb.append("        <tr><td><code>").append(html(entry.getKey())).append("</code></td><td class=\"num\">")
                        .append(entry.getValue()).append("</td></tr>\n");
            }
            sb.append("      </tbody>\n");
            sb.append("    </table></div>\n");
        }
        sb.append("  </section>\n");
    }

    private static void appendThreadSummaryTable(StringBuilder sb, List<StartupEvent> events) {
        sb.append("  <section>\n");
        sb.append("    <h2>Thread Summary</h2>\n");
        List<ThreadSummary> summaries = summarizeThreads(events);
        if (summaries.isEmpty()) {
            sb.append("    <div class=\"empty\">No startup events were recorded.</div>\n");
        } else {
            sb.append("    <div class=\"table-wrap\"><table>\n");
            sb.append("      <thead><tr><th>Thread</th><th class=\"num\">Events</th><th class=\"num\">Total Duration</th><th class=\"num\">Max Span</th><th class=\"num\">Running</th><th class=\"num\">Errors</th></tr></thead>\n");
            sb.append("      <tbody>\n");
            for (ThreadSummary summary : summaries) {
                sb.append("        <tr>")
                        .append("<td>").append(html(summary.threadName())).append("</td>")
                        .append("<td class=\"num\">").append(summary.eventCount()).append("</td>")
                        .append("<td class=\"num\">").append(formatMillis(summary.totalDurationMs())).append("</td>")
                        .append("<td class=\"num\">").append(formatMillis(summary.maxDurationMs())).append("</td>")
                        .append("<td class=\"num\">").append(summary.runningCount()).append("</td>")
                        .append("<td class=\"num\">").append(summary.errorCount()).append("</td>")
                        .append("</tr>\n");
            }
            sb.append("      </tbody>\n");
            sb.append("    </table></div>\n");
        }
        sb.append("  </section>\n");
    }

    private static void appendSlowestTable(StringBuilder sb, List<StartupEvent> slowest) {
        sb.append("  <section>\n");
        sb.append("    <h2>Slowest Spans</h2>\n");
        if (slowest.isEmpty()) {
            sb.append("    <div class=\"empty\">No completed spans were recorded.</div>\n");
        } else {
            sb.append("    <div class=\"table-wrap\"><table>\n");
            sb.append("      <thead><tr><th class=\"num\">Rank</th><th>Span</th><th class=\"num\">Duration</th><th class=\"num\">Start</th><th>Thread</th><th>Status</th></tr></thead>\n");
            sb.append("      <tbody>\n");
            int rank = 1;
            for (StartupEvent event : slowest) {
                sb.append("        <tr>")
                        .append("<td class=\"num\">").append(rank++).append("</td>")
                        .append("<td><code>").append(html(event.getName())).append("</code></td>")
                        .append("<td class=\"num\">").append(formatMillis(event.getDurationMs())).append("</td>")
                        .append("<td class=\"num\">").append(formatMillis(event.getStartMs())).append("</td>")
                        .append("<td>").append(html(event.getThreadName())).append("</td>")
                        .append("<td>").append(status(event.getStatus())).append("</td>")
                        .append("</tr>\n");
            }
            sb.append("      </tbody>\n");
            sb.append("    </table></div>\n");
        }
        sb.append("  </section>\n");
    }

    private static void appendEventTable(StringBuilder sb,
                                         List<StartupEvent> events,
                                         Map<Integer, StartupEvent> eventsById) {
        sb.append("  <section>\n");
        sb.append("    <h2>Full Event Tree</h2>\n");
        if (events.isEmpty()) {
            sb.append("    <div class=\"empty\">No startup events were recorded.</div>\n");
        } else {
            sb.append("    <div class=\"table-wrap\"><table>\n");
            sb.append("      <thead><tr><th class=\"num\">ID</th><th>Span</th><th class=\"num\">Start</th><th class=\"num\">Duration</th><th>Thread</th><th>Status</th><th>Details</th></tr></thead>\n");
            sb.append("      <tbody>\n");
            for (StartupEvent event : events) {
                sb.append("        <tr>")
                        .append("<td class=\"num\">").append(event.getId()).append("</td>")
                        .append("<td><code>").append(indent(depth(event, eventsById))).append(html(event.getName())).append("</code></td>")
                        .append("<td class=\"num\">").append(formatMillis(event.getStartMs())).append("</td>")
                        .append("<td class=\"num\">").append(formatMillis(event.getDurationMs())).append("</td>")
                        .append("<td>").append(html(event.getThreadName())).append("</td>")
                        .append("<td>").append(status(event.getStatus())).append("</td>")
                        .append("<td class=\"details\">").append(details(event)).append("</td>")
                        .append("</tr>\n");
            }
            sb.append("      </tbody>\n");
            sb.append("    </table></div>\n");
        }
        sb.append("  </section>\n");
    }

    private static void appendKeyValueRow(StringBuilder sb, String key, String value) {
        sb.append("        <tr><td>").append(html(key)).append("</td><td>").append(html(value)).append("</td></tr>\n");
    }

    private static String details(StartupEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getError() != null) {
            sb.append("<strong>").append(html(event.getError())).append("</strong>");
        }
        for (Map.Entry<String, String> detail : event.getDetails().entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("<br>");
            }
            sb.append("<code>").append(html(detail.getKey())).append("</code>=").append(html(detail.getValue()));
        }
        return sb.isEmpty() ? "" : sb.toString();
    }

    private static List<ThreadSummary> summarizeThreads(List<StartupEvent> events) {
        Map<String, ThreadAccumulator> accumulators = new HashMap<>();
        for (StartupEvent event : events) {
            ThreadAccumulator accumulator = accumulators.computeIfAbsent(
                    event.getThreadName(), ignored -> new ThreadAccumulator());
            accumulator.add(event);
        }
        List<ThreadSummary> summaries = new ArrayList<>(accumulators.size());
        for (Map.Entry<String, ThreadAccumulator> entry : accumulators.entrySet()) {
            summaries.add(entry.getValue().toSummary(entry.getKey()));
        }
        summaries.sort(Comparator.comparingLong(ThreadSummary::totalDurationMs)
                .reversed()
                .thenComparing(ThreadSummary::threadName));
        return summaries;
    }

    private record ThreadSummary(String threadName,
                                 int eventCount,
                                 int runningCount,
                                 int errorCount,
                                 long totalDurationMs,
                                 long maxDurationMs) {
    }

    private static final class ThreadAccumulator {
        private int eventCount;
        private int runningCount;
        private int errorCount;
        private long totalDurationMs;
        private long maxDurationMs;

        private void add(StartupEvent event) {
            eventCount++;
            long durationMs = event.getDurationMs();
            totalDurationMs += durationMs;
            maxDurationMs = Math.max(maxDurationMs, durationMs);
            if ("RUNNING".equals(event.getStatus())) {
                runningCount++;
            } else if ("ERROR".equals(event.getStatus())) {
                errorCount++;
            }
        }

        private ThreadSummary toSummary(String threadName) {
            return new ThreadSummary(threadName, eventCount, runningCount, errorCount, totalDurationMs, maxDurationMs);
        }
    }

    private static int depth(StartupEvent event, Map<Integer, StartupEvent> eventsById) {
        int depth = 0;
        Integer parentId = event.getParentId();
        while (parentId != null && depth < 32) {
            StartupEvent parent = eventsById.get(parentId);
            if (parent == null) {
                break;
            }
            depth++;
            parentId = parent.getParentId();
        }
        return depth;
    }

    private static String indent(int depth) {
        return "&nbsp;&nbsp;&nbsp;".repeat(Math.max(0, depth));
    }

    private static String status(String status) {
        String css = switch (status) {
            case "OK" -> "status-ok";
            case "ERROR" -> "status-error";
            default -> "status-running";
        };
        return "<span class=\"status " + css + "\">" + html(status) + "</span>";
    }

    private static String formatMillis(long ms) {
        return ms < 0L ? "N/A" : ms + " ms";
    }

    private static String html(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static void appendEvent(StringBuilder sb, StartupEvent event) {
        indent(sb, 2).append("{\n");
        appendField(sb, 3, "id", event.getId()).append(",\n");
        if (event.getParentId() == null) {
            appendNullField(sb, 3, "parentId").append(",\n");
        } else {
            appendField(sb, 3, "parentId", event.getParentId()).append(",\n");
        }
        appendField(sb, 3, "name", event.getName()).append(",\n");
        appendField(sb, 3, "startMs", event.getStartMs()).append(",\n");
        appendField(sb, 3, "durationMs", event.getDurationMs()).append(",\n");
        appendField(sb, 3, "thread", event.getThreadName()).append(",\n");
        appendField(sb, 3, "status", event.getStatus()).append(",\n");
        if (event.getError() == null) {
            appendNullField(sb, 3, "error").append(",\n");
        } else {
            appendField(sb, 3, "error", event.getError()).append(",\n");
        }
        indent(sb, 3).append("\"details\": {");
        boolean firstDetail = true;
        for (Map.Entry<String, String> detail : event.getDetails().entrySet()) {
            if (!firstDetail) {
                sb.append(",");
            }
            sb.append("\n");
            appendField(sb, 4, detail.getKey(), detail.getValue());
            firstDetail = false;
        }
        if (!firstDetail) {
            sb.append("\n");
            indent(sb, 3);
        }
        sb.append("}\n");
        indent(sb, 2).append("}");
    }

    private static StringBuilder appendField(StringBuilder sb, int level, String key, String value) {
        return indent(sb, level)
                .append("\"")
                .append(escape(key))
                .append("\": \"")
                .append(escape(value))
                .append("\"");
    }

    private static StringBuilder appendField(StringBuilder sb, int level, String key, long value) {
        return indent(sb, level)
                .append("\"")
                .append(escape(key))
                .append("\": ")
                .append(value);
    }

    private static StringBuilder appendNullField(StringBuilder sb, int level, String key) {
        return indent(sb, level)
                .append("\"")
                .append(escape(key))
                .append("\": null");
    }

    private static StringBuilder indent(StringBuilder sb, int level) {
        return sb.append("  ".repeat(Math.max(0, level)));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}
