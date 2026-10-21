package com.jdesk.frame.boot;

import com.jdesk.core.app.*;
import com.jdesk.core.extensions.ExtensionRegistry;
import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.I18nStartup;
import com.jdesk.core.i18n.UiLanguage;
import com.jdesk.core.message.MessageBus;
import com.jdesk.core.message.MessageBusKey;
import com.jdesk.core.message.MessageBusManager;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.core.runtime.RuntimeParamSource;
import com.jdesk.core.runtime.params.CoreRuntimeParams;
import com.jdesk.core.service.ServiceContainer;
import com.jdesk.core.startup.StartupTrace;
import com.jdesk.frame.i18n.SwingLocaleSupport;
import com.jdesk.frame.ui.MainFrame;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.ui.scale.JdfScale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Swing application runtime and the frame layer's {@link Application} implementation. It wraps the core
 * {@link ApplicationCore}: sets the global application, installs the look and feel, loads the frame and host XML
 * configuration, starts plugins and creates the main window context; {@link JdeskApplicationBuilder} drives the UI.
 * The {@link MessageBus} is created lazily: lifecycle events go to directly registered listeners and, once the bus
 * exists, to its subscribers as well.
 */
public final class JdeskApplication implements CoroutineApplication {
    private static final Logger LOG = LoggerFactory.getLogger(JdeskApplication.class);

    public static final String FRAME_PARENT_FIRST_PACKAGE = "com.jdesk.frame.";

    private volatile State state = State.NOT_INITIALIZED;
    /** Set from the start of shutdown, including asking listeners; nested or concurrent shutdowns return at once. */
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean();
    private final long startTime;
    private final ClassLoader applicationClassLoader;
    private final ApplicationTaskScope taskScope;
    private final ApplicationCore core;
    private final StartupTrace startupTrace;
    private final ApplicationInfo applicationInfo;

    /** Directly registered listeners, which also receive the events from before the message bus exists. */
    private final CopyOnWriteArrayList<ApplicationLifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();

    /** Created by the first {@link #getMessageBus()} call. */
    private volatile MessageBus messageBus;

    private MainFrame mainFrame;
    private ApplicationContext context;

    public JdeskApplication() {
        this(resolveJdeskApplicationClassLoader());
    }

    public JdeskApplication(ClassLoader applicationClassLoader) {
        this.startTime = System.currentTimeMillis();

        Objects.requireNonNull(applicationClassLoader, "applicationClassLoader");
        this.applicationClassLoader = applicationClassLoader;
        this.applicationInfo = ApplicationInfoLoader.load(this.applicationClassLoader);
        PathManager.setApplicationIdForCurrentProcess(this.applicationInfo.getId());

        // Loads application.properties.
        ApplicationStartupConfig.load();

        // UI language and framework strings.
        I18nStartup.initialize();
        I18n.registerRoot("jdesk/i18n", JdeskApplication.class.getClassLoader(), UiLanguage.EN);

        StartupTrace trace = StartupTrace.create();
        Objects.requireNonNull(trace, "startupTrace");
        this.startupTrace = trace;

        this.taskScope = new ApplicationTaskScope("application");

        this.core = new ApplicationCore(this, this.applicationClassLoader, this.startupTrace, this.taskScope, this.applicationInfo);
    }

    StartupTrace getStartupTrace() {
        return startupTrace;
    }

    ApplicationCore getCore() {
        return core;
    }

    long getStartTimeMillis() {
        return startTime;
    }

    private static ClassLoader resolveJdeskApplicationClassLoader() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null ? contextLoader : JdeskApplication.class.getClassLoader();
    }

    /**
     * Initializes the runtime: sets the global application, installs the base look and feel, registers core and frame
     * services, and loads configuration and plugins. Call it once; on failure the global reference in
     * {@link ApplicationManager} is cleared.
     */
    @Override
    public void init() {
        JdeskApplicationCoroutines.initBlocking(this);
    }

    static void installFrameParentFirstPackage() {
        String existing = "";
        if (RuntimeParamRegistry.contains(CoreRuntimeParams.PLUGIN_PARENT_FIRST_PACKAGES_KEY)) {
            existing = RuntimeParamRegistry.getString(CoreRuntimeParams.PLUGIN_PARENT_FIRST_PACKAGES_KEY);
        }
        for (String prefix : existing.split(",")) {
            if (FRAME_PARENT_FIRST_PACKAGE.equals(prefix.trim())) {
                return;
            }
        }

        String updated = existing.isBlank()
                ? FRAME_PARENT_FIRST_PACKAGE
                : existing + "," + FRAME_PARENT_FIRST_PACKAGE;
        RuntimeParamRegistry.setStartupValue(
                CoreRuntimeParams.PLUGIN_PARENT_FIRST_PACKAGES_KEY,
                updated,
                RuntimeParamSource.PROGRAMMATIC);
    }

    /**
     * Starts the initialized application: starts runtime parameters and plugins, and publishes the lifecycle events
     * around showing the UI.
     */
    @Override
    public void start() {
        JdeskApplicationCoroutines.startBlocking(this);
    }

    /**
     * Shuts the application down, disposing the core, plugins and message bus; any lifecycle listener can veto it
     * beforehand.
     */
    @Override
    public void shutdown() {
        if (state == State.DISPOSED || state == State.SHUTTING_DOWN || !shutdownInProgress.compareAndSet(false, true)) {
            return;
        }
        boolean proceed = false;
        try {
            proceed = prepareForShutdown();
        } finally {
            if (!proceed) {
                shutdownInProgress.set(false);
            }
        }
        if (!proceed) {
            LOG.info("Application shutdown cancelled.");
            return;
        }

        // An external launcher may kill the process if disposal takes too long, so log the elapsed time.
        long shutdownStartedAt = System.nanoTime();
        transitionTo(State.SHUTTING_DOWN);
        LOG.info("Shutting down application...");
        taskScope.cancelAll();

        MessageBus mb = messageBus;
        if (mb != null && !mb.isDisposed()) {
            mb.closeForNewConnections();
        }

        publishLifecycleEvent("onApplicationShuttingDown", listener -> listener.onApplicationShuttingDown(this));

        try {
            core.dispose();
        } catch (RuntimeException | Error e) {
            LOG.error("Failed to dispose application core during shutdown", e);
        }

        if (mb != null) {
            try {
                com.jdesk.core.disposer.Disposer.dispose(mb);
            } catch (RuntimeException | Error e) {
                LOG.error("Failed to dispose application message bus during shutdown", e);
            } finally {
                messageBus = null;
            }
        }

        transitionTo(State.DISPOSED);
        ApplicationManager.clearApplication(this);
        lifecycleListeners.clear();
        RuntimeParamRegistry.reset();
        LOG.info("Application shut down. elapsedMs={}",
                (System.nanoTime() - shutdownStartedAt) / 1_000_000L);
    }

    void disposeAfterStartupFailure(Throwable cause) {
        if (state == State.DISPOSED || state == State.SHUTTING_DOWN) {
            return;
        }

        LOG.error("Application startup failed. Disposing partially started application.", cause);
        transitionTo(State.SHUTTING_DOWN);
        taskScope.cancelAll();

        MessageBus mb = messageBus;
        if (mb != null && !mb.isDisposed()) {
            mb.closeForNewConnections();
        }

        publishLifecycleEvent("onApplicationShuttingDown", listener -> listener.onApplicationShuttingDown(this));

        try {
            core.dispose();
        } catch (RuntimeException | Error e) {
            LOG.error("Failed to dispose application core after startup failure", e);
        }

        if (mb != null) {
            try {
                com.jdesk.core.disposer.Disposer.dispose(mb);
            } catch (RuntimeException | Error e) {
                LOG.error("Failed to dispose application message bus after startup failure", e);
            } finally {
                messageBus = null;
            }
        }

        disposeMainFrameAfterStartupFailure();
        transitionTo(State.DISPOSED);
        ApplicationManager.clearApplication(this);
        lifecycleListeners.clear();
        RuntimeParamRegistry.reset();
        LOG.info("Application disposed after startup failure.");
    }

    private void disposeMainFrameAfterStartupFailure() {
        MainFrame frame = mainFrame;
        mainFrame = null;
        context = null;
        if (frame == null) {
            return;
        }

        Runnable disposeFrame = frame::dispose;
        if (SwingUtilities.isEventDispatchThread()) {
            disposeFrame.run();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(disposeFrame);
        } catch (Exception e) {
            LOG.warn("Failed to dispose main frame on EDT after startup failure", e);
            try {
                disposeFrame.run();
            } catch (RuntimeException disposeError) {
                LOG.warn("Failed to dispose main frame after startup failure", disposeError);
            }
        }
    }

    /** Creates the main window context; call it after init, at most once per application. */
    ApplicationContext createApplicationContext(String title) {
        if (mainFrame != null) {
            throw new IllegalStateException("Main frame already created");
        }
        this.mainFrame = new MainFrame(this, title);
        this.context = new ApplicationContext(this, mainFrame);
        return context;
    }

    public Application getApplication() {
        return this;
    }

    public MainFrame getMainFrame() {
        if (mainFrame == null) {
            throw new IllegalStateException("Main frame has not been created yet");
        }
        return mainFrame;
    }

    public ApplicationContext getContext() {
        if (context == null) {
            throw new IllegalStateException("Frame context has not been created yet");
        }
        return context;
    }

    @Override
    public void addLifecycleListener(ApplicationLifecycleListener listener) {
        lifecycleListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void removeLifecycleListener(ApplicationLifecycleListener listener) {
        lifecycleListeners.remove(listener);
    }

    @Override
    public boolean isDisposed() {
        return state == State.DISPOSED;
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public ClassLoader getApplicationClassLoader() {
        return applicationClassLoader;
    }

    @Override
    public ApplicationTaskScope getTaskScope() {
        return taskScope;
    }

    @Override
    public ServiceContainer getServiceContainer() {
        return core.getServiceContainer();
    }

    @Override
    public <T> T getService(Class<T> serviceClass) {
        return core.getService(serviceClass);
    }

    @Override
    public ExtensionRegistry getExtensionRegistry() {
        return core.getExtensionRegistry();
    }

    /**
     * Returns the application message bus, creating it on first use.
     *
     * @throws IllegalStateException if the bus must be created while the application is shutting down or disposed
     */
    @Override
    public MessageBus getMessageBus() {
        MessageBus mb = messageBus;
        if (mb != null) {
            return mb;
        }

        synchronized (this) {
            mb = messageBus;
            if (mb != null) {
                return mb;
            }

            checkNotDisposed();
            mb = getService(MessageBusManager.class).getOrCreateBus(MessageBusKey.application(), null);
            messageBus = mb;
            return mb;
        }
    }

    XmlConfigurationDescriptor parseFrameConfiguration() {
        return new FrameConfigLoader(core.getExtensionRegistry(),
                core.getServiceContainer(),
                applicationClassLoader)
                .parseFrameConfiguration();
    }

    void applyFrameConfiguration(XmlConfigurationDescriptor descriptor) {
        new FrameConfigLoader(core.getExtensionRegistry(),
                core.getServiceContainer(),
                applicationClassLoader)
                .applyFrameConfiguration(descriptor);
    }

    XmlConfigurationDescriptor parseApplicationConfiguration() {
        return new ApplicationConfigLoader(core.getExtensionRegistry(),
                core.getServiceContainer(),
                applicationClassLoader)
                .parseApplicationConfiguration();
    }

    void applyApplicationConfiguration(XmlConfigurationDescriptor descriptor) {
        new ApplicationConfigLoader(core.getExtensionRegistry(),
                core.getServiceContainer(),
                applicationClassLoader)
                .applyApplicationConfiguration(descriptor);
    }

    private boolean prepareForShutdown() {
        publishLifecycleEvent("appClosing", listener -> listener.appClosing(this));
        if (!canExitApplication()) {
            return false;
        }
        publishLifecycleEvent("onApplicationSaving", listener -> listener.onApplicationSaving(this));
        publishLifecycleEvent("appWillBeClosed", listener -> listener.appWillBeClosed(this));
        return true;
    }

    private boolean canExitApplication() {
        boolean canExit = true;
        for (ApplicationLifecycleListener listener : lifecycleListeners) {
            try {
                if (!listener.canExitApplication(this)) {
                    canExit = false;
                }
            } catch (Exception e) {
                LOG.error("Application lifecycle listener failed during canExitApplication", e);
                canExit = false;
            }
        }

        MessageBus mb = getExistingMessageBus();
        if (mb != null) {
            try {
                if (!mb.syncPublisher(ApplicationLifecycleListener.class).canExitApplication(this)) {
                    canExit = false;
                }
            } catch (Exception e) {
                LOG.error("Message bus lifecycle listener failed during canExitApplication", e);
                canExit = false;
            }
        }
        return canExit;
    }

    private void checkNotDisposed() {
        if (state == State.DISPOSED || state == State.SHUTTING_DOWN) {
            throw new IllegalStateException(
                    "Application is shutting down or disposed - cannot create MessageBus");
        }
    }

    void publishLifecycleEvent(String eventName, Consumer<ApplicationLifecycleListener> event) {
        for (ApplicationLifecycleListener listener : lifecycleListeners) {
            try {
                event.accept(listener);
            } catch (Exception e) {
                LOG.error("Application lifecycle listener failed during {}", eventName, e);
            }
        }

        MessageBus mb = getExistingMessageBus();
        if (mb != null) {
            try {
                event.accept(mb.syncPublisher(ApplicationLifecycleListener.class));
            } catch (Exception e) {
                LOG.error("Message bus lifecycle listener failed during {}", eventName, e);
            }
        }
    }

    private MessageBus getExistingMessageBus() {
        MessageBus mb = messageBus;
        if (mb == null || mb.isDisposed()) {
            return null;
        }
        return mb;
    }

    void transitionTo(State newState) {
        State oldState = state;
        if (oldState == newState) {
            return;
        }
        state = newState;
        LOG.info("Application state changed: {} -> {}", oldState, newState);
    }

    static void ensureLookAndFeel() {
        ensureLookAndFeel(StartupTrace.disabled());
    }

    static void ensureLookAndFeel(StartupTrace startupTrace) {
        startupTrace.runSpan("frame.lookAndFeel.setSystem", () -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                LOG.warn("Failed to set system Look & Feel, using default", e);
            }
        });
        startupTrace.runSpan("frame.i18n.swingDefaults", () -> {
            UiLanguage language = I18n.current();
            SwingLocaleSupport.applyDefaults(language);
            JdfFonts.setLanguage(language);
        });
        startupTrace.runSpan("frame.scale.ensure", () -> {
            JdfScale.installEarly();
        });
        startupTrace.runSpan("frame.fonts.installDefaults", () -> {
            JdfFonts.installDefaults(startupTrace);
        });
    }
}
