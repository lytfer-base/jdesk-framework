package com.jdesk.demo;

import java.util.concurrent.TimeUnit;

final class WebViewStartupBenchmarkResult {

    private final String label;
    private final long serviceLookupMillis;
    private final long createWebViewMillis;
    private final long loadHtmlCallMillis;
    private final long readyWaitMillis;
    private final long totalMillis;
    private final String status;
    private final String failureSummary;

    private WebViewStartupBenchmarkResult(String label,
                                          long serviceLookupMillis,
                                          long createWebViewMillis,
                                          long loadHtmlCallMillis,
                                          long readyWaitMillis,
                                          long totalMillis,
                                          String status,
                                          String failureSummary) {
        this.label = label;
        this.serviceLookupMillis = serviceLookupMillis;
        this.createWebViewMillis = createWebViewMillis;
        this.loadHtmlCallMillis = loadHtmlCallMillis;
        this.readyWaitMillis = readyWaitMillis;
        this.totalMillis = totalMillis;
        this.status = status;
        this.failureSummary = failureSummary;
    }

    static WebViewStartupBenchmarkResult success(String label,
                                                 long startNanos,
                                                 long serviceResolvedNanos,
                                                 long webViewCreatedNanos,
                                                 long loadHtmlReturnedNanos,
                                                 long readyNanos) {
        return new WebViewStartupBenchmarkResult(
                label,
                millisBetween(startNanos, serviceResolvedNanos),
                millisBetween(serviceResolvedNanos, webViewCreatedNanos),
                millisBetween(webViewCreatedNanos, loadHtmlReturnedNanos),
                millisBetween(loadHtmlReturnedNanos, readyNanos),
                millisBetween(startNanos, readyNanos),
                "SUCCESS",
                "");
    }

    static WebViewStartupBenchmarkResult failure(String label,
                                                 long startNanos,
                                                 long failedNanos,
                                                 Throwable failure) {
        return new WebViewStartupBenchmarkResult(
                label,
                0,
                0,
                0,
                0,
                millisBetween(startNanos, failedNanos),
                "FAILED",
                describe(failure));
    }

    long serviceLookupMillis() {
        return serviceLookupMillis;
    }

    long createWebViewMillis() {
        return createWebViewMillis;
    }

    long loadHtmlCallMillis() {
        return loadHtmlCallMillis;
    }

    long readyWaitMillis() {
        return readyWaitMillis;
    }

    long totalMillis() {
        return totalMillis;
    }

    String status() {
        return status;
    }

    String failureSummary() {
        return failureSummary;
    }

    String toSummaryLine() {
        StringBuilder line = new StringBuilder()
                .append(label)
                .append(": ")
                .append(status)
                .append(" total=")
                .append(totalMillis)
                .append(" ms");
        if ("SUCCESS".equals(status)) {
            line.append(" [service=")
                    .append(serviceLookupMillis)
                    .append(" ms, create=")
                    .append(createWebViewMillis)
                    .append(" ms, loadHtml=")
                    .append(loadHtmlCallMillis)
                    .append(" ms, readyWait=")
                    .append(readyWaitMillis)
                    .append(" ms]");
        } else if (!failureSummary.isBlank()) {
            line.append(" [").append(failureSummary).append("]");
        }
        return line.toString();
    }

    private static long millisBetween(long startNanos, long endNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, endNanos - startNanos));
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
