package com.jdesk.core.app;

import com.jdesk.core.extensions.ExtensionRegistry;
import com.jdesk.core.extensions.impl.ExtensionRegistryImpl;
import com.jdesk.core.message.MessageBusManager;
import com.jdesk.core.message.impl.MessageBusManagerImpl;
import com.jdesk.core.plugins.PluginManager;
import com.jdesk.core.plugins.PluginManagerImpl;
import com.jdesk.core.service.AppServiceDeclaration;
import com.jdesk.core.service.ServiceContainer;
import com.jdesk.core.service.ServiceDescriptor;
import com.jdesk.core.service.impl.ServiceContainerImpl;
import com.jdesk.core.startup.StartupTrace;
import com.jdesk.core.vfs.LocalFileSystem;
import com.jdesk.core.vfs.VirtualFileManager;

import java.util.Objects;

/**
 * Application kernel runtime that owns and wires the subsystems built into core: the service container, the extension
 * registry and the built-in application infrastructure. It doesn't depend on Swing, the frame startup sequence or XML
 * configuration; upper modules decide which declarative descriptors to load.
 */
public final class ApplicationCore {

    private final Application application;

    /** Loads plugin service implementations and the class path resources of upper modules. */
    private final ClassLoader applicationClassLoader;

    private final ExtensionRegistryImpl extensionRegistry;

    private final ServiceContainerImpl serviceContainer;

    private final StartupTrace startupTrace;

    private final ApplicationInfo applicationInfo;

    /** Creates the kernel with the {@link ApplicationInfo} from {@link ApplicationInfoLoader#load(ClassLoader)}. */
    public ApplicationCore(Application application,
                           ClassLoader applicationClassLoader,
                           StartupTrace startupTrace,
                           ApplicationTaskScope taskScope) {
        this(application, applicationClassLoader, startupTrace, taskScope, ApplicationInfoLoader.load(applicationClassLoader));
    }

    /**
     * Creates the kernel without initializing services or loading plugins; all arguments must be non-null. Then call
     * {@link #initializeCoreServices()}, {@link #initializeContainers()} and the plugin methods in that order.
     */
    public ApplicationCore(Application application,
                           ClassLoader applicationClassLoader,
                           StartupTrace startupTrace,
                           ApplicationTaskScope taskScope,
                           ApplicationInfo applicationInfo) {
        this.application = Objects.requireNonNull(application, "application");
        this.applicationClassLoader = Objects.requireNonNull(applicationClassLoader, "applicationClassLoader");
        this.startupTrace = Objects.requireNonNull(startupTrace, "startupTrace");
        this.applicationInfo = Objects.requireNonNull(applicationInfo, "applicationInfo");
        this.extensionRegistry = new ExtensionRegistryImpl();
        this.serviceContainer = new ServiceContainerImpl(Objects.requireNonNull(taskScope, "taskScope"));
    }

    /** Registers core's extension points and base services; call it before upper modules load their descriptors. */
    public void initializeCoreServices() {
        startupTrace.runSpan("core.initializeCoreServices", () -> {
            registerCoreExtensionPoints();
            registerCoreServices();
            configureVfs();
        });
    }

    /** Initializes the extension registry and the service container and creates the preloaded services. */
    public void initializeContainers() {
        startupTrace.runSpan("core.initializeContainers", () -> {
            extensionRegistry.initialize();
            serviceContainer.initialize();
        });
    }

    /**
     * Discovers plugin descriptors, then creates the plugin class loaders and registers the extension points they
     * declare; {@link #startPlugins()} starts their extensions and services separately.
     */
    public void loadAndInitializePlugins() {
        startupTrace.runSpan("plugins.loadAndInitialize", () -> {
            loadPlugins();
            initPlugins();
        });
    }

    /**
     * Only discovers and parses plugin descriptors. It depends on core services alone, so it may run in parallel with
     * loading the XML configuration; {@link #initPlugins()} later creates class loaders and registers extension points.
     */
    public void loadPlugins() {
        startupTrace.runSpan("plugins.load", () -> {
            PluginManager pluginManager = getService(PluginManager.class);
            if (pluginManager != null) {
                pluginManager.loadPlugins();
            }
        });
    }

    /** Initializes the discovered plugins: creates their class loaders and registers their extension points. */
    public void initPlugins() {
        startupTrace.runSpan("plugins.init", () -> {
            PluginManager pluginManager = getService(PluginManager.class);
            if (pluginManager != null) {
                pluginManager.initPlugins();
            }
        });
    }

    /** Starts plugin contributions once the application has entered the STARTING phase. */
    public void startPlugins() {
        startupTrace.runSpan("plugins.start", () -> {
            PluginManager pluginManager = getService(PluginManager.class);
            if (pluginManager != null) {
                pluginManager.startPlugins();
            }
        });
    }

    /** Disposes the framework subsystems in reverse dependency order. */
    public void dispose() {
        serviceContainer.dispose();
        extensionRegistry.dispose();
    }

    public ServiceContainer getServiceContainer() {
        return serviceContainer;
    }

    public <T> T getService(Class<T> serviceClass) {
        return serviceContainer.getService(serviceClass);
    }

    public ExtensionRegistry getExtensionRegistry() {
        return extensionRegistry;
    }

    public int getRegisteredServiceCount() {
        return serviceContainer.getRegisteredServiceCount();
    }

    private void registerCoreExtensionPoints() {
        startupTrace.runSpan("core.registerExtensionPoints", () -> {
            extensionRegistry.registerExtensionPoint("applicationService", AppServiceDeclaration.class);
            startupTrace.incrementCounter("core.extensionPoints");
        });
    }

    /** Keep {@code PluginManager} registered by class name, so its implementation isn't loaded at startup. */
    private void registerCoreServices() {
        startupTrace.runSpan("core.registerServices", () -> {
            serviceContainer.registerInstance(StartupTrace.class, startupTrace);
            serviceContainer.registerInstance(ApplicationInfo.class, applicationInfo);
            serviceContainer.registerInstance(ExtensionRegistry.class, extensionRegistry);
            serviceContainer.registerService(MessageBusManager.class,
                    ServiceDescriptor.ofClass(MessageBusManager.class, MessageBusManagerImpl.class));
            serviceContainer.registerService(PluginManager.class,
                    ServiceDescriptor.ofClassName(
                            PluginManager.class,
                            PluginManagerImpl.class.getName(),
                            applicationClassLoader));
            startupTrace.addCounter("core.services.registered", 5);
        });
    }

    private void configureVfs() {
        startupTrace.runSpan("core.configureVfs", () -> {
            VirtualFileManager vfm = VirtualFileManager.getInstance();
            vfm.setApplication(application);
            vfm.registerFileSystem(LocalFileSystem.PROTOCOL, new LocalFileSystem());
            serviceContainer.registerInstance(VirtualFileManager.class, vfm);
        });
    }
}
