package com.jdesk.frame.webview;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.app.ApplicationTaskScope;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.extensions.ExtensionRegistry;
import com.jdesk.core.message.MessageBus;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.core.runtime.RuntimeParamSource;
import com.jdesk.core.service.ServiceContainer;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.frame.boot.ApplicationContext;
import com.jdesk.frame.boot.JdeskApplication;
import com.jdesk.frame.boot.JdeskApplicationBuilder;
import com.jdesk.frame.runtime.params.FrameRuntimeParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

final class WebViewWarmupActivityTest {

    @TempDir
    Path homeDir;

    @AfterEach
    void clearGlobalState() {
        System.clearProperty(FrameRuntimeParams.WEBVIEW_WARMUP_KEY);
        Application app = ApplicationManager.getApplicationIfCreated();
        if (app != null && !app.isDisposed()) {
            app.shutdown();
        }
        if (app != null) {
            ApplicationManager.clearApplication(app);
        }
        Disposer.clearAll();
        PathManager.resetForTests();
        RuntimeParamRegistry.reset();
        RecordingWebViewService.reset();
    }

    @Test
    void defaultConfigurationDoesNotCreateWebViewService() throws Exception {
        PathManager.setHomePathForTests(homeDir);

        JdeskApplication app = JdeskApplicationBuilder.create("No WebView Warmup")
                .postStartupActivity(context -> {
                })
                .launch();

        try {
            Thread.sleep(100);

            assertNull(app.getServiceContainer().getServiceIfCreated(WebViewService.class));
        } finally {
            app.shutdown();
        }
    }

    @Test
    void viewWarmupConfigurationUsesWebViewServiceWithoutJxBrowserImplementation() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        writeApplicationProperties(FrameRuntimeParams.WEBVIEW_WARMUP_KEY + "=view\n");

        JdeskApplication app = JdeskApplicationBuilder.create("WebView Warmup")
                .applicationClassLoader(new WebViewServiceConfigClassLoader(
                        WebViewWarmupActivityTest.class.getClassLoader()))
                .postStartupActivity(context -> {
                })
                .launch();

        try {
            waitUntil(() -> RecordingWebViewService.viewWarmups.get() == 1);

            assertEquals(0, RecordingWebViewService.engineWarmups.get());
            assertEquals(1, RecordingWebViewService.viewWarmups.get());
        } finally {
            app.shutdown();
        }
    }

    @Test
    void engineWarmupConfigurationUsesEngineOnlyWarmup() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        writeApplicationProperties(FrameRuntimeParams.WEBVIEW_WARMUP_KEY + "=engine\n");

        JdeskApplication app = JdeskApplicationBuilder.create("WebView Engine Warmup")
                .applicationClassLoader(new WebViewServiceConfigClassLoader(
                        WebViewWarmupActivityTest.class.getClassLoader()))
                .postStartupActivity(context -> {
                })
                .launch();

        try {
            waitUntil(() -> RecordingWebViewService.engineWarmups.get() == 1);

            assertEquals(1, RecordingWebViewService.engineWarmups.get());
            assertEquals(0, RecordingWebViewService.viewWarmups.get());
        } finally {
            app.shutdown();
        }
    }

    @Test
    void systemPropertyWarmupConfigurationWinsOverApplicationProperties() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        writeApplicationProperties(FrameRuntimeParams.WEBVIEW_WARMUP_KEY + "=engine\n");
        System.setProperty(FrameRuntimeParams.WEBVIEW_WARMUP_KEY, "view");

        JdeskApplication app = JdeskApplicationBuilder.create("WebView Warmup")
                .applicationClassLoader(new WebViewServiceConfigClassLoader(
                        WebViewWarmupActivityTest.class.getClassLoader()))
                .postStartupActivity(context -> {
                })
                .launch();

        try {
            waitUntil(() -> RecordingWebViewService.viewWarmups.get() == 1);

            assertEquals(0, RecordingWebViewService.engineWarmups.get());
            assertEquals(1, RecordingWebViewService.viewWarmups.get());
        } finally {
            app.shutdown();
        }
    }

    @Test
    void runActivityReturnsBeforeWebViewServiceCreationCompletes() {
        RuntimeParamRegistry.setStartupValue(
                FrameRuntimeParams.WEBVIEW_WARMUP,
                "view",
                RuntimeParamSource.STARTUP_CONFIG);
        BlockingWarmupApplication app = new BlockingWarmupApplication();
        WebViewWarmupActivity activity = new WebViewWarmupActivity();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<Void> runActivity = CompletableFuture.runAsync(
                    () -> activity.runActivity(applicationContext(app)),
                    executor);

            assertTimeoutPreemptively(java.time.Duration.ofMillis(250), () -> runActivity.get(250, TimeUnit.MILLISECONDS));
        } finally {
            app.allowServiceCreation();
            app.getTaskScope().close();
            executor.shutdownNow();
        }
    }

    private void writeApplicationProperties(String content) throws Exception {
        Path systemDir = homeDir.resolve("system");
        Files.createDirectories(systemDir);
        Files.writeString(systemDir.resolve("application.properties"), content);
    }

    private static void waitUntil(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!check.ok() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }

    private static ApplicationContext applicationContext(Application application) {
        try {
            Constructor<ApplicationContext> constructor = ApplicationContext.class
                    .getDeclaredConstructor(Application.class, com.jdesk.frame.ui.MainFrame.class);
            constructor.setAccessible(true);
            return constructor.newInstance(application, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static final class RecordingWebViewService implements WebViewService {
        static final AtomicInteger engineWarmups = new AtomicInteger();
        static final AtomicInteger viewWarmups = new AtomicInteger();

        static void reset() {
            engineWarmups.set(0);
            viewWarmups.set(0);
        }

        @Override
        public JdfWebView createWebView() {
            throw new UnsupportedOperationException();
        }

        @Override
        public JdfWebView createWebView(WebViewOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<JdfWebView> createWebViewAsync(WebViewOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebViewWarmupResult> warmUpAsync() {
            engineWarmups.incrementAndGet();
            return CompletableFuture.completedFuture(WebViewWarmupResult.success(1_000_000L, 2_000_000L));
        }

        @Override
        public CompletableFuture<WebViewWarmupResult> warmUpViewAsync() {
            viewWarmups.incrementAndGet();
            return CompletableFuture.completedFuture(WebViewWarmupResult.success(1_000_000L, 2_000_000L));
        }

        @Override
        public boolean isEngineReady() {
            return true;
        }

        @Override
        public WebViewBackendStatus getBackendStatus() {
            return WebViewBackendStatus.READY;
        }
    }

    private static final class WebViewServiceConfigClassLoader extends ClassLoader {
        private final String appPluginsXml;

        WebViewServiceConfigClassLoader(ClassLoader parent) {
            super(parent);
            this.appPluginsXml = """
                    <?xml version="1.0"?>
                    <app-plugins>
                      <extensions>
                        <applicationService serviceInterface="%s"
                                            serviceImplementation="%s"/>
                      </extensions>
                    </app-plugins>
                    """.formatted(WebViewService.class.getName(), RecordingWebViewService.class.getName());
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if ("META-INF/app-plugins.xml".equals(name)) {
                return new ByteArrayInputStream(appPluginsXml.getBytes(StandardCharsets.UTF_8));
            }
            return super.getResourceAsStream(name);
        }
    }

    private static final class BlockingWarmupApplication implements Application {
        private final ApplicationTaskScope taskScope = new ApplicationTaskScope("webview-warmup-test");
        private final CountDownLatch allowServiceCreation = new CountDownLatch(1);

        void allowServiceCreation() {
            allowServiceCreation.countDown();
        }

        @Override
        public void init() {
        }

        @Override
        public void start() {
        }

        @Override
        public void shutdown() {
            taskScope.close();
        }

        @Override
        public void addLifecycleListener(com.jdesk.core.app.ApplicationLifecycleListener listener) {
        }

        @Override
        public void removeLifecycleListener(com.jdesk.core.app.ApplicationLifecycleListener listener) {
        }

        @Override
        public boolean isDisposed() {
            return false;
        }

        @Override
        public State getState() {
            return State.RUNNING;
        }

        @Override
        public ClassLoader getApplicationClassLoader() {
            return WebViewWarmupActivityTest.class.getClassLoader();
        }

        @Override
        public ApplicationTaskScope getTaskScope() {
            return taskScope;
        }

        @Override
        public ServiceContainer getServiceContainer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T getService(Class<T> serviceClass) {
            if (serviceClass == WebViewService.class) {
                try {
                    if (!allowServiceCreation.await(2, TimeUnit.SECONDS)) {
                        throw new TimeoutException("Timed out waiting for WebView service creation to be released.");
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return serviceClass.cast(new RecordingWebViewService());
            }
            throw new UnsupportedOperationException(serviceClass.getName());
        }

        @Override
        public ExtensionRegistry getExtensionRegistry() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MessageBus getMessageBus() {
            throw new UnsupportedOperationException();
        }
    }
}
