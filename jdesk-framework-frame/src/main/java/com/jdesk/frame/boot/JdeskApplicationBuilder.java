package com.jdesk.frame.boot;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationLifecycleListener;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.config.ComponentStore;
import com.jdesk.core.startup.StartupSpan;
import com.jdesk.core.startup.StartupTrace;
import com.jdesk.core.startup.StartupTraceWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recommended way to start a JDesk Swing application, through the standard startup sequence: create the
 * {@link JdeskApplication} and load the startup configuration, set up UI scaling early, initialize core and frame
 * services, optionally install the default {@link ComponentStore}, apply the theme, create the main window context,
 * run the startup activities, then start the application and show the window on the EDT.
 * A failed startup rolls back everything created so far.
 */
public final class JdeskApplicationBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(JdeskApplicationBuilder.class);
    private static final int STARTUP_EXCEPTION_EXIT_CODE = 3;

    private final String title;

    /** Critical activities that must finish before the first window shows. */
    private final CopyOnWriteArrayList<ApplicationActivity> applicationActivities = new CopyOnWriteArrayList<>();

    /** Non-critical activities run after the first window shows; their failures are only logged. */
    private final CopyOnWriteArrayList<PostStartupActivity> postStartupActivities = new CopyOnWriteArrayList<>();
    private ClassLoader applicationClassLoader = Thread.currentThread().getContextClassLoader();
    private boolean installDefaultComponentStore = true;
    private boolean showMainFrameOnLaunch = true;

    private JdeskApplicationBuilder(String title) {
        this.title = title;
    }

    /** Creates a builder with the main window title. */
    public static JdeskApplicationBuilder create(String title) {
        return new JdeskApplicationBuilder(Objects.requireNonNull(title, "title"));
    }

    /** Creates a builder without a title; the startup configuration or the window context then picks the name. */
    public static JdeskApplicationBuilder create() {
        return new JdeskApplicationBuilder(null);
    }

    /** Sets the host application's class loader; if none is available, startup uses the builder's own loader. */
    public JdeskApplicationBuilder applicationClassLoader(ClassLoader applicationClassLoader) {
        this.applicationClassLoader = Objects.requireNonNull(applicationClassLoader, "applicationClassLoader");
        return this;
    }

    /**
     * Adds a critical activity, run after the {@link ApplicationContext} is created and before the first window
     * shows; a failure aborts startup and rolls it back.
     */
    public JdeskApplicationBuilder applicationActivity(ApplicationActivity activity) {
        this.applicationActivities.add(Objects.requireNonNull(activity, "activity"));
        return this;
    }

    /**
     * Adds a non-critical activity, posted to the EDT through the application's {@code ApplicationTaskScope} once the
     * window shows; a failure is only logged.
     */
    public JdeskApplicationBuilder postStartupActivity(PostStartupActivity activity) {
        this.postStartupActivities.add(Objects.requireNonNull(activity, "activity"));
        return this;
    }

    /** Sets whether to install the default {@link ComponentStore}; if not, the host must register its own. */
    public JdeskApplicationBuilder installDefaultComponentStore(boolean installDefaultComponentStore) {
        this.installDefaultComponentStore = installDefaultComponentStore;
        return this;
    }

    /**
     * Sets whether startup ends by showing the main window; an embedded or hosted application can keep it hidden
     * until an outer shell asks for it.
     */
    public JdeskApplicationBuilder showMainFrameOnLaunch(boolean showMainFrameOnLaunch) {
        this.showMainFrameOnLaunch = showMainFrameOnLaunch;
        return this;
    }

    /** Runs the standard startup sequence (init, start, main window) to completion and returns the application. */
    public JdeskApplication launch() {
        return launchBlocking();
    }

    /** Java entry point that blocks the current thread until the coroutine-based startup finishes. */
    public JdeskApplication launchBlocking() {
        return launchBlocking(false);
    }

    JdeskApplication launchBlocking(boolean exitOnStartupFailure) {
        return JdeskApplicationCoroutines.launchBlocking(this, exitOnStartupFailure);
    }

    /** Returns the configured or context class loader, or the builder's own when neither is available. */
    ClassLoader resolveApplicationClassLoader() {
        return applicationClassLoader != null
                ? applicationClassLoader
                : JdeskApplicationBuilder.class.getClassLoader();
    }

    String getConfiguredTitle() {
        return title;
    }

    List<ApplicationActivity> getApplicationActivitiesSnapshot() {
        return List.copyOf(applicationActivities);
    }

    List<PostStartupActivity> getPostStartupActivitiesSnapshot() {
        return List.copyOf(postStartupActivities);
    }

    boolean shouldInstallDefaultComponentStore() {
        return installDefaultComponentStore;
    }

    boolean shouldShowMainFrameOnLaunch() {
        return showMainFrameOnLaunch;
    }

    /** Rolls back a failed startup; cleanup failures are added to {@code failure} as suppressed exceptions. */
    void rollbackStartup(JdeskApplication frameApplication, Throwable failure) {
        try {
            frameApplication.disposeAfterStartupFailure(failure);
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            LOG.error("Failed to dispose application after startup failure", cleanupFailure);
        }
    }

    /**
     * Reports a startup failure and exits the process with a fixed code.
     * Use it only from command-line entry points; an embedding host must get the exception instead.
     */
    static void reportStartupFailureAndExit(Throwable failure) {
        StartupErrorReporter.showError(failure);
        System.exit(STARTUP_EXCEPTION_EXIT_CODE);
    }

    /** Logs the startup trace summary; in report mode it also writes the trace file, logging any failure. */
    void finishStartupTrace(StartupTrace startupTrace) {
        if (startupTrace.isOff()) {
            return;
        }
        if (startupTrace.isReportEnabled()) {
            LOG.info("{}{}", System.lineSeparator(), startupTrace.summary());
            try {
                StartupTraceWriter.writeIfEnabled(startupTrace);
            } catch (RuntimeException traceWriteError) {
                LOG.warn("Failed to write startup trace", traceWriteError);
            }
            return;
        }
        LOG.info(startupTrace.summary());
    }

    /**
     * Installs the default {@link ComponentStore} unless disabled or already registered; it saves the registered
     * components on {@link ApplicationLifecycleListener#onApplicationSaving(Application)}.
     */
    void installComponentStoreIfNeeded(Application application) {
        if (!installDefaultComponentStore || application.getServiceContainer().hasService(ComponentStore.class)) {
            return;
        }
        StartupTrace startupTrace = application.getService(StartupTrace.class);
        try (StartupSpan span = startupTrace.begin("componentStore.installDefault")) {
            try {
                ComponentStore componentStore = new ComponentStore(PathManager.getConfigDir());
                application.getServiceContainer().registerInstance(ComponentStore.class, componentStore);
                componentStore.registerExistingServices(application.getServiceContainer());
                application.getMessageBus().connect().subscribe(ApplicationLifecycleListener.class, new ApplicationLifecycleListener() {
                    @Override
                    public void onApplicationSaving(Application app) {
                        componentStore.saveAll();
                    }
                });
            } catch (RuntimeException | Error e) {
                span.failed(e);
                throw e;
            }
        }
    }

    /**
     * Starts the application on a background thread, moving only the Swing UI phase to the EDT, and returns at once;
     * suits a plain {@code main} method. A startup failure is reported and exits the process.
     */
    public void launchOnEdt() {
        Thread startupThread = new Thread(() -> {
            try {
                launchBlocking(true);
            } catch (RuntimeException | Error e) {
                throw e;
            }
        }, "jdesk-startup");
        startupThread.setDaemon(false);
        startupThread.start();
    }
}
