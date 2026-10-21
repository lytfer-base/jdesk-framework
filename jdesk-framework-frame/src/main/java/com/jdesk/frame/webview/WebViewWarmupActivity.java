package com.jdesk.frame.webview;

import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.core.app.Application;
import com.jdesk.frame.boot.ApplicationContext;
import com.jdesk.frame.boot.PostStartupActivity;
import com.jdesk.frame.runtime.params.FrameRuntimeParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Optional post-startup WebView warmup driven by application runtime config.
 */
public final class WebViewWarmupActivity implements PostStartupActivity {

    private static final Logger LOG = LoggerFactory.getLogger(WebViewWarmupActivity.class);

    private static final String NONE = "none";
    private static final String ENGINE = "engine";
    private static final String VIEW = "view";

    @Override
    public void runActivity(ApplicationContext context) {
        String mode = configuredMode();
        if (NONE.equals(mode)) {
            return;
        }

        Application application = context.getApplication();
        try {
            application.getTaskScope().launchDefault("webview.warmup." + mode,
                    () -> startWarmup(application, mode));
        } catch (RuntimeException failure) {
            LOG.warn("WebView warmup could not be started for mode '{}'.", mode, failure);
        }
    }

    private static void startWarmup(Application application, String mode) {
        if (application.isDisposed()) {
            return;
        }
        try {
            WebViewService webViewService = application.getService(WebViewService.class);
            CompletableFuture<WebViewWarmupResult> warmup = ENGINE.equals(mode)
                    ? webViewService.warmUpAsync()
                    : webViewService.warmUpViewAsync();
            warmup.whenComplete((result, failure) -> logResult(mode, result, failure));
        } catch (RuntimeException failure) {
            LOG.warn("WebView warmup could not be started for mode '{}'.", mode, failure);
        }
    }

    private static String configuredMode() {
        String raw = System.getProperty(FrameRuntimeParams.WEBVIEW_WARMUP_KEY);
        if (raw == null) {
            raw = RuntimeParamRegistry.getString(FrameRuntimeParams.WEBVIEW_WARMUP, NONE);
        }
        String mode = raw == null ? NONE : raw.trim().toLowerCase(Locale.ROOT);
        if (mode.isEmpty() || NONE.equals(mode)) {
            return NONE;
        }
        if (ENGINE.equals(mode) || VIEW.equals(mode)) {
            return mode;
        }
        LOG.warn("Unknown WebView warmup mode '{}'; expected none, engine, or view.", raw);
        return NONE;
    }

    private static void logResult(String mode, WebViewWarmupResult result, Throwable failure) {
        if (failure != null) {
            LOG.warn("WebView {} warmup failed.", mode, failure);
        } else if (result.isSuccess()) {
            LOG.info("WebView {} warmup completed in {} ms.", mode, result.elapsedMillis());
        } else {
            LOG.warn("WebView {} warmup finished with {} after {} ms: {}",
                    mode,
                    result.status(),
                    result.elapsedMillis(),
                    result.failureSummary());
        }
    }
}
