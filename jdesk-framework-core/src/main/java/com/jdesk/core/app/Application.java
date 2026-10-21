package com.jdesk.core.app;

import com.jdesk.core.message.MessageBus;
import com.jdesk.core.service.ServiceContainer;
import com.jdesk.core.extensions.ExtensionRegistry;

/**
 * The application singleton of the current process, modeled on IntelliJ Platform's {@code Application}: it exposes
 * the lifecycle, the service container, the extension registry and the application message bus. The frame module
 * starts the UI; core only defines the kernel capabilities.
 */
public interface Application {

    /** Application lifecycle states, which limit what may be done in each phase. */
    enum State {
        NOT_INITIALIZED, INITIALIZING, INITIALIZED, STARTING, RUNNING, SHUTTING_DOWN, DISPOSED
    }

    // --- Lifecycle ---

    /** Initializes the kernel: registers base services and extension points and loads the framework configuration. */
    void init();

    /** Starts the application; upper modules provide the UI and the event loop. */
    void start();

    /** Shuts down gracefully, saving state and releasing services, extensions, plugins and other kernel resources. */
    void shutdown();

    /**
     * Registers a lifecycle listener directly, so it also receives early events sent before the application
     * {@link MessageBus} exists; subscribe through the bus for ordinary runtime events.
     */
    void addLifecycleListener(ApplicationLifecycleListener listener);

    /** Removes a listener registered with {@link #addLifecycleListener}. */
    void removeLifecycleListener(ApplicationLifecycleListener listener);

    /** Returns whether the application has been disposed. */
    boolean isDisposed();

    /** Returns the current lifecycle state. */
    State getState();

    /**
     * Returns the host application's class loader, which plugins use as their parent to share the framework API and
     * the extension point contracts the host exports.
     */
    ClassLoader getApplicationClassLoader();

    /**
     * Returns the scope for background tasks that may outlive startup; implementations must cancel its unfinished
     * tasks on shutdown or startup rollback.
     */
    ApplicationTaskScope getTaskScope();

    // --- Service container ---

    /** Returns the application service container. */
    ServiceContainer getServiceContainer();

    /** Returns the service for {@code serviceClass}; one registered by descriptor may be created on this call. */
    <T> T getService(Class<T> serviceClass);

    // --- Extensions ---

    /** Returns the application extension registry. */
    ExtensionRegistry getExtensionRegistry();

    // --- Message bus ---

    /** Returns the application message bus. */
    MessageBus getMessageBus();

}
