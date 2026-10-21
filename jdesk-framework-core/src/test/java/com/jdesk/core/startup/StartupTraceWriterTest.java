package com.jdesk.core.startup;

import com.jdesk.core.app.PathManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupTraceWriterTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearPathState() {
        PathManager.resetForTests();
    }

    @Test
    void jsonAndHtmlContainSpansCountersAndWindowTiming() {
        StartupTrace trace = StartupTrace.report();
        try (StartupSpan span = trace.begin("root")) {
            span.detail("phase", "test");
            trace.incrementCounter("sample.counter");
        }
        trace.markFirstWindowVisible();

        String json = StartupTraceWriter.toJson(trace);

        assertTrue(json.contains("\"name\": \"root\""));
        assertTrue(json.contains("\"phase\": \"test\""));
        assertTrue(json.contains("\"sample.counter\": 1"));
        assertTrue(json.contains("\"firstWindowVisibleMs\""));

        String html = StartupTraceWriter.toHtml(trace);

        assertTrue(html.contains("<h2>Summary</h2>"));
        assertTrue(html.contains("<h2>Counters</h2>"));
        assertTrue(html.contains("<h2>Thread Summary</h2>"));
        assertTrue(html.contains("<h2>Slowest Spans</h2>"));
        assertTrue(html.contains("<h2>Full Event Tree</h2>"));
        assertTrue(html.contains("root"));
        assertTrue(html.contains("sample.counter"));
        assertTrue(html.contains(Thread.currentThread().getName()));
    }

    @Test
    void writerCreatesLatestAndArchivedFilesInLogDirectory() throws Exception {
        PathManager.setHomePathForTests(tempDir);
        StartupTrace trace = StartupTrace.report();
        try (StartupSpan ignored = trace.begin("write-test")) {
            trace.incrementCounter("writes");
        }

        StartupTraceWriter.writeIfEnabled(trace);

        Path logDir = tempDir.resolve("log");
        assertTrue(Files.exists(logDir.resolve("startup-trace-latest.json")));
        assertTrue(Files.exists(logDir.resolve("startup-trace-latest.html")));
        assertTrue(hasArchivedTrace(logDir, ".json"));
        assertTrue(hasArchivedTrace(logDir, ".html"));
    }

    private static boolean hasArchivedTrace(Path logDir, String suffix) throws Exception {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(logDir, "startup-trace-*" + suffix)) {
            for (Path file : files) {
                if (!file.getFileName().toString().equals("startup-trace-latest" + suffix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
