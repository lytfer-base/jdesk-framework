package com.jdesk.frame.webview.jcef;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.i18n.I18n;
import com.jdesk.frame.laf.JdfColors;
import com.jdesk.frame.webview.JdfWebView;
import com.jdesk.frame.webview.NavigationGuardLog;
import com.jdesk.frame.webview.WebViewBackendStatus;
import com.jdesk.frame.webview.WebViewEvent;
import com.jdesk.frame.webview.WebViewException;
import com.jdesk.frame.webview.WebViewOptions;
import com.jdesk.frame.webview.WebViewRenderingMode;
import com.jdesk.frame.webview.WebViewRuntimeConfig;
import com.jdesk.frame.webview.WebViewService;
import com.jdesk.frame.webview.WebViewWarmupResult;
import com.jdesk.frame.webview.resource.http.WebResourceServer;
import com.jdesk.frame.webview.resource.http.WebResourceSession;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.browser.CefRendering;
import org.cef.callback.CefCommandLine;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefAppHandlerAdapter;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JCEF implementation of {@link WebViewService}: the JetBrains Runtime supplies {@code org.cef} at run time and
 * {@code jdesk-jcef-api} the types at compile time.
 * {@link CefApp} is a process singleton that reads settings and switches only on first use, so anything the engine
 * command line depends on, such as the resource server port mapping, must be ready before that.
 */
public final class JcefWebViewService implements WebViewService, Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(JcefWebViewService.class);

    /** Logger for forwarded page console output; other WebView backends use the same name, so keep it. */
    private static final Logger WEB_CONSOLE = LoggerFactory.getLogger("com.jdesk.frame.webview.console");

    private static final long INITIALIZATION_TIMEOUT_MILLIS = 45_000L;

    /** Upper bound on waiting for shutdown; past it, exit goes on, since leftovers beat a hung window close. */
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000L;

    /** Page-side WebBridge entry function; the bootstrap script sends requests through this name. */
    private static final String BRIDGE_QUERY_FUNCTION = "jdeskQuery";

    private final Object lock = new Object();
    private final Application application;
    private final ExecutorService jcefExecutor = Executors.newSingleThreadExecutor(new JcefThreadFactory());
    private final Set<JdfWebView> views = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<CefBrowser, JcefWebView> viewsByBrowser = Collections.synchronizedMap(new IdentityHashMap<>());

    /** Completes when {@code onContextInitialized} arrives. */
    private final CompletableFuture<Void> contextReady = new CompletableFuture<>();

    private CefApp cefApp;
    private CefClient cefClient;
    private CefMessageRouter bridgeRouter;
    private WebViewRenderingMode engineRenderingMode;
    private WebViewBackendStatus backendStatus = WebViewBackendStatus.NOT_STARTED;
    private CompletableFuture<WebViewWarmupResult> warmupFuture;
    private CompletableFuture<WebViewWarmupResult> viewWarmupFuture;
    private boolean disposed;
    private boolean viewWarmedUp;

    public JcefWebViewService(Application application) {
        this.application = application;
    }

    @Override
    public JdfWebView createWebView() {
        return createWebView(WebViewOptions.defaults());
    }

    @Override
    public JdfWebView createWebView(WebViewOptions options) {
        WebViewOptions effectiveOptions = options == null ? WebViewOptions.defaults() : options;
        ensureReady(effectiveOptions);
        return callOnEdt(() -> createWebViewOnEdt(effectiveOptions));
    }

    @Override
    public CompletableFuture<JdfWebView> createWebViewAsync(WebViewOptions options) {
        WebViewOptions effectiveOptions = options == null ? WebViewOptions.defaults() : options;
        return CompletableFuture
                .runAsync(() -> ensureReadyOnBackground(effectiveOptions), jcefExecutor)
                .thenCompose(ignored -> callOnEdtAsync(() -> createWebViewOnEdt(effectiveOptions)));
    }

    @Override
    public CompletableFuture<WebViewWarmupResult> warmUpAsync() {
        return warmUpAsync(WebViewOptions.defaults());
    }

    @Override
    public CompletableFuture<WebViewWarmupResult> warmUpViewAsync() {
        return warmUpViewAsync(WebViewOptions.defaults());
    }

    @Override
    public boolean isEngineReady() {
        synchronized (lock) {
            return cefClient != null && !disposed;
        }
    }

    @Override
    public WebViewBackendStatus getBackendStatus() {
        synchronized (lock) {
            if (disposed) {
                return WebViewBackendStatus.DISPOSED;
            }
            if (cefClient != null) {
                return WebViewBackendStatus.READY;
            }
            return backendStatus;
        }
    }

    @Override
    public String getBackendName() {
        return "jcef";
    }

    /** Disposes views, message router, client and app in that order; JCEF can't start again in this process. */
    @Override
    public void dispose() {
        disposed = true;
        for (JdfWebView view : Set.copyOf(views)) {
            Disposer.dispose(view);
        }
        views.clear();
        viewsByBrowser.clear();
        synchronized (lock) {
            CefClient activeClient = cefClient;
            CefApp activeApp = cefApp;
            CefMessageRouter activeRouter = bridgeRouter;
            cefClient = null;
            cefApp = null;
            bridgeRouter = null;
            backendStatus = WebViewBackendStatus.DISPOSED;
            RuntimeException failure = null;
            if (activeRouter != null && activeClient != null) {
                failure = runDisposeStep(failure, () -> activeClient.removeMessageRouter(activeRouter));
                failure = runDisposeStep(failure, activeRouter::dispose);
            }
            if (activeClient != null) {
                failure = runDisposeStep(failure, activeClient::dispose);
            }
            if (activeApp != null) {
                failure = runDisposeStep(failure, activeApp::dispose);
                failure = runDisposeStep(failure, JcefWebViewService::awaitTerminated);
            }
            if (failure != null) {
                throw failure;
            }
        }
        jcefExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------ Warm-up

    private CompletableFuture<WebViewWarmupResult> warmUpAsync(WebViewOptions options) {
        WebViewOptions effectiveOptions = options == null ? WebViewOptions.defaults() : options;
        synchronized (lock) {
            if (disposed || applicationDisposed()) {
                long now = System.nanoTime();
                return CompletableFuture.completedFuture(WebViewWarmupResult.failure(now, now,
                        new WebViewException("Cannot warm up WebView after application disposal.")));
            }
            if (succeeded(warmupFuture) || (warmupFuture != null && !warmupFuture.isDone())) {
                return warmupFuture;
            }
            if (cefClient != null) {
                // Created elsewhere by a direct createWebView: no warm-up time to report.
                long now = System.nanoTime();
                return CompletableFuture.completedFuture(WebViewWarmupResult.success(now, now));
            }
            backendStatus = WebViewBackendStatus.WARMING_UP;
            warmupFuture = CompletableFuture.supplyAsync(() -> initializeForWarmup(effectiveOptions), jcefExecutor);
            return warmupFuture;
        }
    }

    private CompletableFuture<WebViewWarmupResult> warmUpViewAsync(WebViewOptions options) {
        WebViewOptions effectiveOptions = options == null ? WebViewOptions.defaults() : options;
        synchronized (lock) {
            if (succeeded(viewWarmupFuture) || (viewWarmupFuture != null && !viewWarmupFuture.isDone())) {
                return viewWarmupFuture;
            }
            // Warm up the engine only: a hidden view has no parent window, so its browser would never be created.
            viewWarmupFuture = warmUpAsync(effectiveOptions).whenComplete((result, failure) -> {
                if (failure == null && result != null && result.isSuccess()) {
                    synchronized (lock) {
                        viewWarmedUp = true;
                    }
                }
            });
            return viewWarmupFuture;
        }
    }

    /**
     * Returns whether a warm-up succeeded and can be reused as is; failures aren't reused, so later calls retry.
     * Don't fabricate a zero-duration result for "already warm": callers would lose the real timing.
     */
    private static boolean succeeded(CompletableFuture<WebViewWarmupResult> future) {
        if (future == null || !future.isDone() || future.isCompletedExceptionally()) {
            return false;
        }
        WebViewWarmupResult result = future.getNow(null);
        return result != null && result.isSuccess();
    }

    private WebViewWarmupResult initializeForWarmup(WebViewOptions options) {
        long startNanos = System.nanoTime();
        try {
            ensureReadyOnBackground(options);
            return WebViewWarmupResult.success(startNanos, System.nanoTime());
        } catch (RuntimeException failure) {
            synchronized (lock) {
                if (cefClient == null) {
                    engineRenderingMode = null;
                    backendStatus = WebViewBackendStatus.FAILED;
                }
            }
            return WebViewWarmupResult.failure(startNanos, System.nanoTime(), failure);
        }
    }

    // ------------------------------------------------------------------ Engine initialization

    /**
     * Makes sure the engine is ready; callable on any thread, and on the EDT it waits in a secondary loop.
     * Never block the EDT on the background task: JCEF initialization itself runs on the EDT, so they would deadlock.
     */
    private void ensureReady(WebViewOptions options) {
        if (!SwingUtilities.isEventDispatchThread()) {
            ensureReadyOnBackground(options);
            return;
        }
        CompletableFuture<Void> task =
                CompletableFuture.runAsync(() -> ensureReadyOnBackground(options), jcefExecutor);
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        AtomicBoolean finished = new AtomicBoolean();
        task.whenComplete((ignored, failure) -> {
            finished.set(true);
            loop.exit();
        });
        while (!finished.get() && loop.enter()) {
            // enter() returned because of exit(); check finished before waiting again.
        }
        try {
            task.join();
        } catch (CompletionException failure) {
            throw unwrap(failure.getCause() == null ? failure : failure.getCause());
        }
    }

    private void ensureReadyOnBackground(WebViewOptions options) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new WebViewException("Cannot create WebView in headless environment.");
        }
        long startNanos = System.nanoTime();
        WebResourceSession session;
        synchronized (lock) {
            if (disposed || applicationDisposed()) {
                throw new WebViewException("Cannot create WebView after application disposal.");
            }
            if (cefClient != null) {
                ensureCompatibleRenderingMode(options.getRenderingMode());
                return;
            }
            engineRenderingMode = options.getRenderingMode();
            LOG.info("jcef.engine.create.start renderingMode={}", options.getRenderingMode());
            // The resource server must run first: its port goes on the command line, which CefApp reads once.
            session = WebResourceServer.find(application)
                    .filter(WebResourceServer::isRunning)
                    .map(WebResourceServer::session)
                    .orElse(null);
            try {
                cefApp = obtainCefApp(options, session);
            } catch (RuntimeException failure) {
                markStartupFailed();
                throw failure;
            }
        }
        // Must wait outside the lock, or getBackendStatus and createWebView on the EDT would hang too.
        awaitContextReady();
        synchronized (lock) {
            if (disposed) {
                throw new WebViewException("Cannot create WebView after application disposal.");
            }
            if (cefClient != null) {
                return;
            }
            try {
                if (session != null) {
                    JcefResourceSessionInstaller.seedSessionCookie(session);
                }
                cefClient = cefApp.createClient();
                installHandlers();
                JcefResourceSessionInstaller.installRequestHandler(cefClient, session, this::viewFor);
                backendStatus = WebViewBackendStatus.READY;
                LOG.info("jcef.engine.create.done elapsedMs={}", (System.nanoTime() - startNanos) / 1_000_000L);
            } catch (RuntimeException failure) {
                markStartupFailed();
                throw failure;
            } catch (Exception failure) {
                markStartupFailed();
                throw new WebViewException("Failed to initialize JCEF.", failure);
            }
        }
    }

    /**
     * Waits, bounded, until CEF has terminated.
     * Don't skip it: {@code CefApp.dispose()} only starts the shutdown, and exiting the JVM early orphans helpers.
     */
    private static void awaitTerminated() {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadlineNanos) {
            if (CefApp.getState() == CefApp.CefAppState.TERMINATED) {
                LOG.info("jcef.engine.terminated");
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.warn("jcef.engine.terminate.timeout state={} child processes may linger", CefApp.getState());
    }

    private void markStartupFailed() {
        engineRenderingMode = null;
        backendStatus = WebViewBackendStatus.FAILED;
    }

    private CefApp obtainCefApp(WebViewOptions options, WebResourceSession session) {
        CefApp existing = CefApp.getInstanceIfAny();
        if (existing != null) {
            return existing;
        }
        // Reap orphans of an earlier crash now, before this process has helpers of its own.
        JcefOrphanReaper.reapOrphans();
        CefApp.addAppHandler(new JdeskAppHandler(session));
        CefSettings settings = createSettings(options);
        // Clear caches now: between reaping and startup, no process is using this profile.
        JcefGpuCaches.clear(Path.of(settings.cache_path));
        if (!CefApp.startup(new String[0])) {
            throw new WebViewException("JCEF startup returned false.");
        }
        return CefApp.getInstance(settings);
    }

    private CefSettings createSettings(WebViewOptions options) {
        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = options.getRenderingMode() == WebViewRenderingMode.OFF_SCREEN;
        settings.cache_path = userDataDirectory(options).toString();
        settings.persist_session_cookies = true;
        settings.locale = switch (I18n.current()) {
            case JA -> "ja";
            case ZH_CN -> "zh-CN";
            case EN, PSEUDO -> "en-US";
        };
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
        settings.background_color = themedBackgroundColor(settings);
        // Remote debugging is off unless configured; when on, log the port so users know where to connect.
        WebViewRuntimeConfig.resolveRemoteDebuggingPort().ifPresent(port -> {
            settings.remote_debugging_port = port;
            LOG.info("webview.jcef.remoteDebugging.enabled port={} url=http://127.0.0.1:{}", port, port);
        });
        configureJbrPaths(settings);
        return settings;
    }

    /** Returns the theme background Chromium paints before the first frame; without it, dark themes flash white. */
    private static CefSettings.ColorType themedBackgroundColor(CefSettings settings) {
        Color background = JdfColors.background();
        return settings.new ColorType(255, background.getRed(), background.getGreen(), background.getBlue());
    }

    /**
     * Points CEF at the helper process and Chromium resources inside the JBR.
     * Must be set, or helpers start another JVM ({@code ClassNotFoundException: /prefetch:2}) and the GPU crashes.
     */
    private static void configureJbrPaths(CefSettings settings) {
        JcefRuntime.helper().ifPresent(path -> settings.browser_subprocess_path = path.toString());
        JcefRuntime.resourcesDir().ifPresent(path -> settings.resources_dir_path = path.toString());
        JcefRuntime.localesDir().ifPresent(path -> settings.locales_dir_path = path.toString());
        LOG.info("jcef.paths helper={} resources={}", settings.browser_subprocess_path, settings.resources_dir_path);
    }

    /**
     * Waits for the CEF context on the {@code onContextInitialized} signal, also polling the state in case another
     * CefApp in this process got the callback. Call it off the EDT only: initialization needs the EDT free.
     */
    private void awaitContextReady() {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(INITIALIZATION_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadlineNanos) {
            CefApp.CefAppState state = CefApp.getState();
            if (state == CefApp.CefAppState.INITIALIZED) {
                return;
            }
            if (state == CefApp.CefAppState.TERMINATED || state == CefApp.CefAppState.SHUTTING_DOWN) {
                throw new WebViewException("JCEF cannot be used in state: " + state);
            }
            try {
                contextReady.get(50L, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException retry) {
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WebViewException("Interrupted while waiting for JCEF initialization.", e);
            } catch (ExecutionException e) {
                throw unwrap(e);
            }
        }
        throw new WebViewException("Timed out waiting for JCEF initialization; "
                + "it runs on the EDT, so check whether something is blocking the EDT.");
    }

    private void installHandlers() {
        bridgeRouter = CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig(
                BRIDGE_QUERY_FUNCTION, BRIDGE_QUERY_FUNCTION + "Cancel"));
        bridgeRouter.addHandler(new BridgeRouterHandler(), true);
        cefClient.addMessageRouter(bridgeRouter);
        cefClient.addLifeSpanHandler(new LifeSpanHandler());
        cefClient.addLoadHandler(new LoadHandler());
        cefClient.addDisplayHandler(new DisplayHandler());
        cefClient.addDialogHandler(JcefDesktopHandlers.fileDialogs());
        cefClient.addDownloadHandler(JcefDesktopHandlers.downloads());
        cefClient.addContextMenuHandler(JcefDesktopHandlers.contextMenu());
    }

    // ------------------------------------------------------------------ Views

    private JdfWebView createWebViewOnEdt(WebViewOptions options) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new WebViewException("WebView Swing component must be created on the EDT.");
        }
        LOG.info("jcef.webview.create.start");
        CefRendering rendering = options.getRenderingMode() == WebViewRenderingMode.OFF_SCREEN
                ? CefRendering.OFFSCREEN
                : CefRendering.DEFAULT;
        CefBrowser browser = cefClient.createBrowser("about:blank", rendering, false);
        Component component = browser.getUIComponent();
        JcefWebView webView = new JcefWebView(browser, component, this::viewDisposed);
        views.add(webView);
        viewsByBrowser.put(browser, webView);
        webView.navigationPolicy(options.getNavigationPolicy());
        Disposer.register(this, webView);
        // Don't call createImmediately(): with no native parent yet the page stays blank; addNotify creates it.
        LOG.info("jcef.webview.create.done");
        options.getInitialUrl().ifPresent(webView::loadUrl);
        options.getInitialHtml().ifPresent(webView::loadHtml);
        return webView;
    }

    private JcefWebView viewFor(CefBrowser browser) {
        return viewsByBrowser.get(browser);
    }

    private void viewDisposed(JcefWebView webView) {
        views.remove(webView);
        viewsByBrowser.remove(webView.browser());
    }

    private Path userDataDirectory(WebViewOptions options) {
        Path directory = options.getUserDataDirectory()
                .orElseGet(() -> PathManager.getSystemDir().resolve("jcef").resolve("user-data"));
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new WebViewException("Failed to create JCEF user data directory: " + directory, e);
        }
        return directory;
    }

    private void ensureCompatibleRenderingMode(WebViewRenderingMode requestedMode) {
        if (engineRenderingMode != null && engineRenderingMode != requestedMode) {
            throw new WebViewException("The shared WebView engine already uses "
                    + engineRenderingMode + " rendering mode.");
        }
    }

    // ------------------------------------------------------------------ Callbacks

    /** Changes the browser process's command line only; subprocesses carry CEF's own switches, which must stay. */
    private final class JdeskAppHandler extends CefAppHandlerAdapter {
        private final WebResourceSession session;

        private JdeskAppHandler(WebResourceSession session) {
            super(new String[0]);
            this.session = session;
        }

        @Override
        public void onContextInitialized() {
            contextReady.complete(null);
        }

        @Override
        public void onBeforeCommandLineProcessing(String processType, CefCommandLine commandLine) {
            if (processType != null && !processType.isEmpty()) {
                return;
            }
            // Disable Windows occlusion detection: a covered browser stops painting and the first-frame cover breaks.
            commandLine.appendSwitchWithValue("disable-features", "CalculateNativeWinOcclusion");
            if (session != null) {
                JcefResourceSessionInstaller.applyCommandLine(commandLine, session);
            }
        }
    }

    private final class BridgeRouterHandler extends CefMessageRouterHandlerAdapter {
        @Override
        public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request,
                               boolean persistent, CefQueryCallback callback) {
            // Main frame only: the renderer registers jdeskQuery in every V8 context, so subframes can call it too.
            if (frame == null || !frame.isMain()) {
                LOG.warn("jcef.bridge.rejected reason=notMainFrame url={}", frame == null ? null : frame.getURL());
                callback.failure(-1, "WebBridge only accepts queries from the main frame.");
                return true;
            }
            JcefWebView view = viewFor(browser);
            if (view != null && view.handleBridgeQuery(request, callback::success)) {
                return true;
            }
            callback.failure(-1, "No WebBridge session is attached to this JCEF view.");
            return true;
        }
    }

    private final class LifeSpanHandler extends CefLifeSpanHandlerAdapter {
        @Override
        public void onAfterCreated(CefBrowser browser) {
            JcefWebView view = viewFor(browser);
            if (view != null) {
                view.nativeCreated();
            }
        }

        @Override
        public void onBeforeClose(CefBrowser browser) {
            JcefWebView view = viewFor(browser);
            if (view != null) {
                viewDisposed(view);
            }
        }

        /** Blocks every popup: windows the engine creates are unknown to the window manager, even same-origin ones. */
        @Override
        public boolean onBeforePopup(CefBrowser browser, CefFrame frame, String targetUrl, String targetFrameName) {
            JcefWebView view = viewFor(browser);
            String baseline = view == null ? null : view.navigationBaseline().origin();
            NavigationGuardLog.blocked("jcef", targetUrl, baseline, false, true);
            return true;
        }
    }

    private final class LoadHandler extends CefLoadHandlerAdapter {
        @Override
        public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            JcefWebView view = frame != null && frame.isMain() ? viewFor(browser) : null;
            if (view != null) {
                view.navigationBaseline().documentCommitted(frame.getURL());
                view.fire(WebViewEvent.loadFinished(view, text(frame.getURL())));
            }
        }

        @Override
        public void onLoadError(CefBrowser browser, CefFrame frame, CefLoadHandler.ErrorCode errorCode,
                                String errorText, String failedUrl) {
            JcefWebView view = frame != null && frame.isMain() ? viewFor(browser) : null;
            if (view != null) {
                // Must set the baseline on failure too, or this view never gets one and stays unguarded.
                view.navigationBaseline().documentFailed(failedUrl);
                view.fire(WebViewEvent.loadFailed(view, text(failedUrl) + " (" + errorCode + ")"));
            }
        }
    }

    private final class DisplayHandler extends CefDisplayHandlerAdapter {
        @Override
        public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
            JcefWebView view = frame != null && frame.isMain() ? viewFor(browser) : null;
            if (view != null) {
                view.fire(WebViewEvent.urlChanged(view, text(url)));
            }
        }

        @Override
        public void onTitleChange(CefBrowser browser, String title) {
            JcefWebView view = viewFor(browser);
            if (view != null) {
                view.updateTitle(title);
                view.fire(WebViewEvent.titleChanged(view, view.title()));
            }
        }

        /** Forwards page console output without ever throwing; returns false so CEF still logs it as well. */
        @Override
        public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level,
                                        String message, String source, int line) {
            try {
                String text = text(message)
                        + (source == null || source.isBlank() ? "" : " @" + source + ":" + line);
                switch (level) {
                    case LOGSEVERITY_ERROR, LOGSEVERITY_FATAL -> WEB_CONSOLE.error(text);
                    case LOGSEVERITY_WARNING -> WEB_CONSOLE.warn(text);
                    case LOGSEVERITY_VERBOSE -> WEB_CONSOLE.debug(text);
                    default -> WEB_CONSOLE.info(text);
                }
            } catch (RuntimeException ignored) {
                // Console forwarding is diagnostic and must never disturb the view.
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ Helpers

    private <T> T callOnEdt(Callable<T> callable) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return callable.call();
            } catch (Exception e) {
                throw unwrap(e);
            }
        }
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = callable.call();
                } catch (Throwable e) {
                    failure[0] = e;
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebViewException("Interrupted while creating WebView on EDT.", e);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
        if (failure[0] != null) {
            throw unwrap(failure[0]);
        }
        @SuppressWarnings("unchecked")
        T typed = (T) result[0];
        return typed;
    }

    private <T> CompletableFuture<T> callOnEdtAsync(Callable<T> callable) {
        CompletableFuture<T> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                result.complete(callable.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private boolean applicationDisposed() {
        return application != null && application.isDisposed();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /** Runs one disposal step, keeping the first failure and adding later ones to it as suppressed. */
    static RuntimeException runDisposeStep(RuntimeException previousFailure, Runnable step) {
        try {
            step.run();
            return previousFailure;
        } catch (RuntimeException failure) {
            if (previousFailure != null) {
                previousFailure.addSuppressed(failure);
                return previousFailure;
            }
            return failure;
        }
    }

    private static WebViewException unwrap(Throwable failure) {
        Throwable cause = failure;
        if (cause instanceof java.util.concurrent.ExecutionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof WebViewException webViewException) {
            return webViewException;
        }
        return new WebViewException("JCEF WebView operation failed.", cause);
    }

    private static final class JcefThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "jdesk-jcef-engine");
            thread.setDaemon(true);
            return thread;
        }
    }
}
