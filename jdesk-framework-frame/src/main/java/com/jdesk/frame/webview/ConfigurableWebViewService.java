package com.jdesk.frame.webview;

import com.jdesk.core.app.Application;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.frame.webview.jcef.JcefRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * WebView service entry point that keeps concrete browser backends replaceable.
 */
public final class ConfigurableWebViewService implements WebViewService, Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurableWebViewService.class);

    /**
     * Backend implementations are loaded by class name.
     * Don't reference them directly: linking would pull in {@code org.cef.*} or {@code com.teamdev.*}, which a given
     * build or runtime may lack.
     */
    private static final String JCEF_SERVICE_CLASS = "com.jdesk.frame.webview.jcef.JcefWebViewService";

    private static final String JXBROWSER_SERVICE_CLASS =
            "com.jdesk.frame.webview.jxbrowser.JxBrowserWebViewService";

    private final Object lock = new Object();
    private final Application application;
    private final Supplier<WebViewBackendKind> backendConfig;
    private final BooleanSupplier jcefAvailable;
    private final BooleanSupplier jxBrowserAvailable;
    private final Function<WebViewBackendKind, WebViewService> backendFactory;

    private WebViewService delegate;
    private boolean disposed;

    public ConfigurableWebViewService(Application application) {
        this(application,
                WebViewRuntimeConfig::resolveBackend,
                ConfigurableWebViewService::isJcefAvailable,
                ConfigurableWebViewService::isJxBrowserAvailable,
                backend -> createBackend(application, backend));
    }

    ConfigurableWebViewService(Application application,
                               Supplier<WebViewBackendKind> backendConfig,
                               BooleanSupplier jcefAvailable,
                               BooleanSupplier jxBrowserAvailable,
                               Function<WebViewBackendKind, WebViewService> backendFactory) {
        this.application = application;
        this.backendConfig = Objects.requireNonNull(backendConfig, "backendConfig");
        this.jcefAvailable = Objects.requireNonNull(jcefAvailable, "jcefAvailable");
        this.jxBrowserAvailable = Objects.requireNonNull(jxBrowserAvailable, "jxBrowserAvailable");
        this.backendFactory = Objects.requireNonNull(backendFactory, "backendFactory");
    }

    @Override
    public JdfWebView createWebView() {
        return delegate().createWebView();
    }

    @Override
    public JdfWebView createWebView(WebViewOptions options) {
        return delegate().createWebView(options);
    }

    @Override
    public CompletableFuture<JdfWebView> createWebViewAsync(WebViewOptions options) {
        return delegate().createWebViewAsync(options);
    }

    @Override
    public CompletableFuture<WebViewWarmupResult> warmUpAsync() {
        return delegate().warmUpAsync();
    }

    @Override
    public CompletableFuture<WebViewWarmupResult> warmUpViewAsync() {
        return delegate().warmUpViewAsync();
    }

    @Override
    public boolean isEngineReady() {
        return delegate().isEngineReady();
    }

    @Override
    public WebViewBackendStatus getBackendStatus() {
        return delegate().getBackendStatus();
    }

    @Override
    public String getBackendName() {
        return delegate().getBackendName();
    }

    @Override
    public void dispose() {
        WebViewService activeDelegate;
        synchronized (lock) {
            if (disposed) {
                return;
            }
            disposed = true;
            activeDelegate = delegate;
            delegate = null;
        }
        if (activeDelegate instanceof Disposable disposable) {
            Disposer.dispose(disposable);
        }
    }

    WebViewService delegateForTests() {
        return delegate();
    }

    private WebViewService delegate() {
        synchronized (lock) {
            if (disposed || (application != null && application.isDisposed())) {
                throw new WebViewException("Cannot use WebView service after application disposal.");
            }
            if (delegate == null) {
                WebViewBackendKind requested = backendConfig.get();
                boolean jcef = jcefAvailable.getAsBoolean();
                boolean jx = jxBrowserAvailable.getAsBoolean();
                WebViewBackendKind selected = WebViewBackendSelector.select(requested, jcef, jx);
                LOG.info("webview.backend.selected requested={} jcefAvailable={} jxBrowserAvailable={} selected={}",
                        requested, jcef, jx, selected);
                delegate = backendFactory.apply(selected);
            }
            return delegate;
        }
    }

    private static WebViewService createBackend(Application application, WebViewBackendKind backend) {
        return switch (backend) {
            case JCEF -> newBackend(JCEF_SERVICE_CLASS, application, "JCEF");
            case JXBROWSER -> newBackend(JXBROWSER_SERVICE_CLASS, application, "JxBrowser");
            case AUTO -> throw new WebViewException("AUTO WebView backend must be resolved before creation.");
        };
    }

    private static WebViewService newBackend(String className, Application application, String backendName) {
        try {
            Class<?> type = Class.forName(className, true, ConfigurableWebViewService.class.getClassLoader());
            return (WebViewService) type.getConstructor(Application.class).newInstance(application);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new WebViewException(backendName + " backend is not available in this runtime.", failure);
        }
    }

    private static boolean isJcefAvailable() {
        return JcefRuntime.isAvailable() && isPresent(JCEF_SERVICE_CLASS);
    }

    private static boolean isJxBrowserAvailable() {
        return isPresent("com.teamdev.jxbrowser.engine.Engine") && isPresent(JXBROWSER_SERVICE_CLASS);
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, ConfigurableWebViewService.class.getClassLoader());
            return true;
        } catch (LinkageError | ClassNotFoundException ignored) {
            return false;
        }
    }
}
