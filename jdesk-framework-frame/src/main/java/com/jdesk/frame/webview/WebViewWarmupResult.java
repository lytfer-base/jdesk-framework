package com.jdesk.frame.webview;

import java.util.concurrent.TimeUnit;

/**
 * Result of initializing the WebView backend before the first visible view.
 */
public final class WebViewWarmupResult {

    private final boolean success;
    private final long elapsedMillis;
    private final String failureSummary;

    private WebViewWarmupResult(boolean success, long elapsedMillis, String failureSummary) {
        this.success = success;
        this.elapsedMillis = elapsedMillis;
        this.failureSummary = failureSummary;
    }

    public static WebViewWarmupResult success(long startNanos, long endNanos) {
        return new WebViewWarmupResult(true, millisBetween(startNanos, endNanos), "");
    }

    public static WebViewWarmupResult failure(long startNanos, long endNanos, Throwable failure) {
        return new WebViewWarmupResult(false, millisBetween(startNanos, endNanos), describe(failure));
    }

    public boolean isSuccess() {
        return success;
    }

    public long elapsedMillis() {
        return elapsedMillis;
    }

    public String status() {
        return success ? "SUCCESS" : "FAILED";
    }

    public String failureSummary() {
        return failureSummary;
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
