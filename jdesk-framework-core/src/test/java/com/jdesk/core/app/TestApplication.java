package com.jdesk.core.app;

import com.jdesk.core.extensions.ExtensionRegistry;
import com.jdesk.core.message.MessageBus;
import com.jdesk.core.message.MessageBusKey;
import com.jdesk.core.message.MessageBusManager;
import com.jdesk.core.service.ServiceContainer;
import com.jdesk.core.startup.StartupTrace;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TestApplication implements Application {

    private final ClassLoader applicationClassLoader;
    private final ApplicationTaskScope taskScope;
    private final ApplicationCore core;
    private final CopyOnWriteArrayList<ApplicationLifecycleListener> lifecycleListeners =
            new CopyOnWriteArrayList<>();

    private volatile State state = State.NOT_INITIALIZED;
    private volatile MessageBus messageBus;

    public TestApplication() {
        this(Thread.currentThread().getContextClassLoader() != null
                ? Thread.currentThread().getContextClassLoader()
                : TestApplication.class.getClassLoader());
    }

    public TestApplication(ClassLoader applicationClassLoader) {
        this(applicationClassLoader, new StartupTrace());
    }

    public TestApplication(StartupTrace startupTrace) {
        this(Thread.currentThread().getContextClassLoader() != null
                ? Thread.currentThread().getContextClassLoader()
                : TestApplication.class.getClassLoader(), startupTrace);
    }

    public TestApplication(ClassLoader applicationClassLoader, StartupTrace startupTrace) {
        this.applicationClassLoader = Objects.requireNonNull(applicationClassLoader, "applicationClassLoader");
        this.taskScope = new ApplicationTaskScope("test-application");
        this.core = new ApplicationCore(this, this.applicationClassLoader, startupTrace, taskScope);
    }

    @Override
    public void init() {
        if (state != State.NOT_INITIALIZED) {
            throw new IllegalStateException("Application already initialized: " + state);
        }
        ApplicationManager.setApplication(this);
        state = State.INITIALIZING;
        core.initializeCoreServices();
        core.initializeContainers();
        core.loadAndInitializePlugins();
        state = State.INITIALIZED;
    }

    @Override
    public void start() {
        if (state != State.INITIALIZED) {
            throw new IllegalStateException("Must call init() before start(). Current: " + state);
        }
        state = State.STARTING;
        core.startPlugins();
        state = State.RUNNING;
    }

    @Override
    public void shutdown() {
        if (state == State.DISPOSED || state == State.SHUTTING_DOWN) {
            return;
        }
        state = State.SHUTTING_DOWN;
        taskScope.cancelAll();
        core.dispose();
        MessageBus mb = messageBus;
        if (mb != null) {
            com.jdesk.core.disposer.Disposer.dispose(mb);
            messageBus = null;
        }
        state = State.DISPOSED;
        lifecycleListeners.clear();
        ApplicationManager.clearApplication(this);
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
            mb = getService(MessageBusManager.class).getOrCreateBus(MessageBusKey.application(), null);
            messageBus = mb;
            return mb;
        }
    }
}
