package com.jdesk.core.plugins;

import com.jdesk.concurrency.startup.JdeskStartupScope;
import com.jdesk.concurrency.startup.StartupDeferred;
import com.jdesk.core.app.Application;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.extensions.ExtensionDescriptor;
import com.jdesk.core.extensions.ExtensionPoint;
import com.jdesk.core.extensions.ExtensionRegistry;
import com.jdesk.core.service.AppServiceDeclaration;
import com.jdesk.core.service.ServiceContainer;
import com.jdesk.core.service.ServiceDescriptor;
import com.jdesk.core.service.ServicePreloadMode;
import com.jdesk.core.startup.StartupSpan;
import com.jdesk.core.startup.StartupTrace;
import com.jdesk.core.startup.StartupTraces;
import com.jdesk.util.xml.SecureXmlParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Default {@link PluginManager}: discovers plugin descriptors in the plugins directory (folders with
 * {@code META-INF/plugin.xml}, single JARs and the {@code bundled-plugins.xml} registry), creates
 * {@link PluginClassLoader}s in dependency order, registers the extension points plugins declare, and registers their
 * extensions and application services lazily.
 */
public class PluginManagerImpl implements PluginManager {

    private static final Logger LOG = LoggerFactory.getLogger(PluginManagerImpl.class);

    private static final String BUNDLED_XML = "bundled-plugins.xml";
    private static final String PLUGIN_XML = "META-INF/plugin.xml";
    private static final String PLUGIN_XML_ALT = "plugin.xml";
    private static final Set<String> PLATFORM_PLUGIN_IDS = Set.of("com.jdesk.core");

    private final Application app;
    private final Path pluginsDir;
    private final ClassLoader applicationClassLoader;

    private final Map<String, PluginDescriptor> descriptors = new LinkedHashMap<>();

    private final Map<String, PluginState> states = new ConcurrentHashMap<>();

    private final Map<String, PluginClassLoader> classLoaders = new ConcurrentHashMap<>();

    private volatile boolean disposed;

    private record ExtensionRegistration(String pointName, ExtensionDescriptor<?> descriptor) {
    }

    /** Removed on unload, so no extension point keeps a contract type from the plugin's class loader. */
    private record ExtensionPointRegistration(String pointName) {
    }

    /** An entry of bundled-plugins.xml; {@code path}, if set, locates the plugin folder or JAR. */
    private record BundledPluginEntry(String id, String path) {
    }

    /** The bundled-plugins.xml entries in declaration order. */
    private record BundledPluginRegistry(List<BundledPluginEntry> entries, Set<String> ids) {
        private BundledPluginRegistry {
            entries = List.copyOf(entries);
            ids = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
        }

        private boolean containsId(String id) {
            return ids.contains(id);
        }
    }

    /** A plugin path from bundled-plugins.xml ({@code bundledId} set) or from scanning the directory. */
    private record DiscoveredPluginPath(Path path, String bundledId) {
    }

    private record ParsedPluginDescriptor(DiscoveredPluginPath pluginPath, PluginDescriptor descriptor) {
    }

    /** Unregistered and disposed when the plugin stops, before its class loader can be released. */
    private record AppServiceRegistration(Class<?> serviceClass) {
    }

    private final Map<String, List<ExtensionRegistration>> registeredExtensions = new ConcurrentHashMap<>();

    private final Map<String, List<ExtensionPointRegistration>> registeredExtensionPoints = new ConcurrentHashMap<>();

    private final Map<String, List<AppServiceRegistration>> registeredAppServices = new ConcurrentHashMap<>();

    public PluginManagerImpl(Application app) {
        this.app = Objects.requireNonNull(app, "app");
        this.pluginsDir = PathManager.getPluginsDir();
        this.applicationClassLoader = Objects.requireNonNull(
                app.getApplicationClassLoader(), "applicationClassLoader");
    }

    // ============== Lifecycle ==============

    @Override
    public void loadPlugins() {
        checkNotDisposed();
        StartupTrace startupTrace = StartupTraces.getCurrent();
        LOG.info("Loading plugins from: {}", pluginsDir);

        if (!Files.isDirectory(pluginsDir)) {
            LOG.warn("Plugins directory does not exist: {}. Creating it.", pluginsDir);
            try {
                Files.createDirectories(pluginsDir);
            } catch (IOException e) {
                startupTrace.incrementCounter("plugins.directory.createErrors");
                LOG.error("Failed to create plugins directory", e);
                return;
            }
        }

        // 1. Read bundled-plugins.xml to know which plugins are bundled.
        BundledPluginRegistry bundledRegistry = loadBundledRegistry();

        // 2. Resolve the bundled plugin paths, then scan the directory for folders and JARs.
        List<DiscoveredPluginPath> pluginPaths = discoverPluginPaths(bundledRegistry);
        startupTrace.addCounter("plugins.paths.discovered", pluginPaths.size());

        // 3. Parse descriptors in parallel, then store them in discovery order.
        for (ParsedPluginDescriptor parsed : parsePluginDescriptors(pluginPaths)) {
            PluginDescriptor desc = parsed.descriptor();
            if (desc != null) {
                String expectedBundledId = parsed.pluginPath().bundledId();
                if (expectedBundledId != null
                        && !expectedBundledId.equals(desc.getPluginId())) {
                    LOG.warn("Bundled plugin path {} was registered for id {}, but descriptor declares {}",
                            parsed.pluginPath().path(), expectedBundledId, desc.getPluginId());
                }

                // Bundled status follows the parsed plugin ID, not the registry path.
                if (bundledRegistry.containsId(desc.getPluginId())) {
                    desc.setBundled(true);
                }
                descriptors.put(desc.getPluginId(), desc);
                states.put(desc.getPluginId(), PluginState.UNLOADED);
                startupTrace.incrementCounter("plugins.descriptors.loaded");
                LOG.info("Discovered plugin: {} ({}) [bundled={}]",
                        desc.getPluginId(), desc.getName(), desc.isBundled());
            }
        }

        LOG.info("Loaded {} plugin descriptors", descriptors.size());
    }

    private List<ParsedPluginDescriptor> parsePluginDescriptors(List<DiscoveredPluginPath> pluginPaths) {
        if (pluginPaths.isEmpty()) {
            return List.of();
        }
        StartupTrace startupTrace = StartupTraces.getCurrent();
        Integer parentId = startupTrace.getCurrentSpanId();
        try (JdeskStartupScope scope = new JdeskStartupScope("plugin-descriptor-loading")) {
            List<StartupDeferred<ParsedPluginDescriptor>> tasks = new ArrayList<>(pluginPaths.size());
            for (DiscoveredPluginPath pluginPath : pluginPaths) {
                String taskName = "plugin.descriptor.parse:" + pluginPath.path().getFileName();
                tasks.add(scope.asyncIo(taskName, startupTrace.task(parentId, taskName, () -> {
                    try {
                        PluginDescriptor descriptor = parsePluginDescriptor(pluginPath.path());
                        if (descriptor == null) {
                            startupTrace.incrementCounter("plugins.descriptors.empty");
                        }
                        return new ParsedPluginDescriptor(pluginPath, descriptor);
                    } catch (Exception e) {
                        startupTrace.incrementCounter("plugins.descriptors.errors");
                        LOG.error("Failed to load plugin descriptor from {}", pluginPath.path(), e);
                        return new ParsedPluginDescriptor(pluginPath, null);
                    }
                })));
            }
            return scope.awaitAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while parsing plugin descriptors", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse plugin descriptors", e);
        }
    }

    @Override
    public void initPlugins() {
        checkNotDisposed();

        ExtensionRegistry ext = app.getExtensionRegistry();
        List<PluginDescriptor> orderedPlugins = getLoadableTopologicalOrder();
        StartupTrace startupTrace = StartupTraces.getCurrent();

        // 1. Create the class loaders first, so extension point interfaces can live in plugin JARs.
        try (StartupSpan span = startupTrace.begin("plugins.classloaders.create")) {
            for (PluginDescriptor desc : orderedPlugins) {
                createClassLoader(desc);
                states.put(desc.getPluginId(), PluginState.LOADED);
                startupTrace.incrementCounter("plugins.classloaders.created");
            }
        }

        // 2. Register the extension points the plugins declare.
        try (StartupSpan span = startupTrace.begin("plugins.extensionPoints.register")) {
            for (PluginDescriptor desc : orderedPlugins) {
                List<ExtensionPointRegistration> pointRegistrations = new ArrayList<>();
                registeredExtensionPoints.put(desc.getPluginId(), pointRegistrations);
                for (PluginDescriptor.ExtensionPointDeclaration ep : desc.getExtensionPoints()) {
                    try {
                        if (ext.getExtensionPoint(ep.getName()) != null) {
                            LOG.warn("Extension point already registered: {} (plugin: {})",
                                    ep.getName(), desc.getPluginId());
                            continue;
                        }

                        PluginClassLoader pluginLoader = classLoaders.get(desc.getPluginId());
                        ClassLoader loader = pluginLoader != null ? pluginLoader : appClassLoader();
                        Class<?> iface = Class.forName(ep.getExtensionClassName(), true, loader);
                        ext.registerExtensionPoint(ep.getName(), iface);
                        pointRegistrations.add(new ExtensionPointRegistration(ep.getName()));
                        startupTrace.incrementCounter("plugins.extensionPoints.registered");
                        LOG.debug("Registered extension point: {} (plugin: {})", ep.getName(), desc.getPluginId());
                    } catch (ClassNotFoundException e) {
                        startupTrace.incrementCounter("plugins.extensionPoints.missingClass");
                        LOG.warn("Extension point class not found: {} (plugin: {})", ep.getExtensionClassName(), desc.getPluginId());
                    } catch (RuntimeException | LinkageError e) {
                        startupTrace.incrementCounter("plugins.extensionPoints.errors");
                        LOG.warn("Failed to register extension point: {} (plugin: {})",
                                ep.getName(), desc.getPluginId(), e);
                    }
                }
            }
        }

        LOG.info("Initialized {} plugins", getLoadedCount());
    }

    @Override
    public void startPlugins() {
        checkNotDisposed();

        ExtensionRegistry ext = app.getExtensionRegistry();
        for (PluginDescriptor desc : getLoadableTopologicalOrder()) {
            startPlugin(desc, ext);
        }

        LOG.info("Started {} plugins", getStartedCount());
    }

    private void startPlugin(PluginDescriptor desc, ExtensionRegistry ext) {
        PluginState state = states.get(desc.getPluginId());
        if (state == PluginState.STARTED) {
            return;
        }
        if (state != PluginState.LOADED && state != PluginState.STOPPED) {
            LOG.debug("Skipping plugin start: {} (state={})", desc.getPluginId(), state);
            return;
        }

        PluginClassLoader cl = classLoaders.get(desc.getPluginId());

        List<ExtensionRegistration> registrations = new ArrayList<>();
        registeredExtensions.put(desc.getPluginId(), registrations);

        List<AppServiceRegistration> serviceRegistrations = new ArrayList<>();
        registeredAppServices.put(desc.getPluginId(), serviceRegistrations);

        // ---- Lazy extensions ----
        // Only descriptors are stored; implementations load on the first getExtensions or forEachExtensionSafe.
        for (PluginDescriptor.ExtensionDeclaration extDecl : desc.getExtensions()) {
            try {
                ExtensionPoint<?> ep = ext.getExtensionPoint(extDecl.getPointName());
                if (ep == null) {
                    LOG.warn("Extension point '{}' not found for plugin '{}' extension '{}'",
                            extDecl.getPointName(), desc.getPluginId(),
                            extDecl.getImplementationClass());
                    continue;
                }

                ExtensionDescriptor<?> descriptor = new ExtensionDescriptor<>(
                        ep.getExtensionClass(),
                        extDecl.getImplementationClass(),
                        cl != null ? cl : appClassLoader(),
                        extDecl.getId());

                registerLazyRaw(ep, descriptor);
                registrations.add(new ExtensionRegistration(extDecl.getPointName(), descriptor));
                StartupTraces.getCurrent().incrementCounter("plugins.extensions.lazyRegistered");

                LOG.debug("Registered lazy extension: {} → {} (plugin: {})",
                        extDecl.getPointName(), extDecl.getImplementationClass(),
                        desc.getPluginId());
            } catch (Exception | LinkageError e) {
                LOG.error("Failed to register lazy extension {} for plugin {}",
                        extDecl.getImplementationClass(), desc.getPluginId(), e);
            }
        }

        // ---- Lazy application services ----
        // Only descriptors are stored; implementations load on the first getService.
        ServiceContainer serviceContainer = app.getServiceContainer();
        for (AppServiceDeclaration svc : desc.getAppServices()) {
            try {
                // The interface must load now: it is the registration key.
                Class<?> ifaceClass = Class.forName(svc.getServiceInterface(), true,
                        cl != null ? cl : appClassLoader());

                ServiceDescriptor<?> descriptor = ServiceDescriptor.builder(ifaceClass)
                        .implementationClassName(svc.getServiceImplementation())
                        .classLoader(cl != null ? cl : appClassLoader())
                        .preload(svc.getPreloadMode())
                        .build();

                // Record first: a failing AWAIT preload leaves the service registered, so rollback must remove it.
                serviceRegistrations.add(new AppServiceRegistration(ifaceClass));
                registerServiceRaw(serviceContainer, ifaceClass, descriptor);
                StartupTraces.getCurrent().incrementCounter("plugins.services.registered");
                LOG.info("Registered app service: {} → {} (plugin: {}, lazy)",
                        svc.getServiceInterface(), svc.getServiceImplementation(),
                        desc.getPluginId());
            } catch (ClassNotFoundException e) {
                LOG.warn("Service interface not found: {} (plugin: {})",
                        svc.getServiceInterface(), desc.getPluginId());
            } catch (Exception | LinkageError e) {
                // Registration, AWAIT preload or class init failed: roll back this plugin, don't stop the others.
                LOG.error("Failed to register app service {} for plugin {}",
                        svc.getServiceInterface(), desc.getPluginId(), e);
                failPluginStart(desc.getPluginId(),
                        "App service registration failed: " + svc.getServiceInterface());
                return;
            }
        }

        states.put(desc.getPluginId(), PluginState.STARTED);
    }

    @SuppressWarnings("unchecked")
    private <T> void registerServiceRaw(ServiceContainer container,
                                        Class<?> iface, ServiceDescriptor<?> desc) {
        container.registerService((Class<T>) iface, (ServiceDescriptor<T>) desc);
    }

    @SuppressWarnings("unchecked")
    private <T> boolean unregisterServiceRaw(ServiceContainer container, Class<?> iface) {
        return container.unregisterService((Class<T>) iface);
    }

    private ClassLoader appClassLoader() {
        return applicationClassLoader;
    }

    @Override
    public void stopPlugin(String pluginId) {
        checkNotDisposed();
        PluginState state = states.get(pluginId);
        if (state != PluginState.STARTED) return;

        states.put(pluginId, PluginState.STOPPING);

        // Services go before extensions, so a service being disposed can still see the plugin's extensions.
        unregisterPluginComponents(pluginId,
                registeredAppServices.remove(pluginId),
                registeredExtensions.remove(pluginId));

        states.put(pluginId, PluginState.STOPPED);
        LOG.info("Stopped plugin: {}", pluginId);
    }

    /** Unregisters services in reverse order (disposing created ones), then extensions; failures are only logged. */
    private void unregisterPluginComponents(String pluginId,
                                            List<AppServiceRegistration> serviceRegistrations,
                                            List<ExtensionRegistration> registrations) {
        ServiceContainer serviceContainer = app.getServiceContainer();
        if (serviceRegistrations != null) {
            List<AppServiceRegistration> reverse = new ArrayList<>(serviceRegistrations);
            Collections.reverse(reverse);
            for (AppServiceRegistration reg : reverse) {
                try {
                    if (unregisterServiceRaw(serviceContainer, reg.serviceClass())) {
                        LOG.debug("Unregistered app service: {} (plugin: {})",
                                reg.serviceClass().getName(), pluginId);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to unregister app service {} (plugin {})",
                            reg.serviceClass().getName(), pluginId, e);
                }
            }
        }

        ExtensionRegistry ext = app.getExtensionRegistry();
        if (registrations != null) {
            for (ExtensionRegistration reg : registrations) {
                try {
                    ExtensionPoint<?> ep = ext.getExtensionPoint(reg.pointName());
                    if (ep != null) {
                        unregisterLazyRaw(ep, reg.descriptor());
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to unregister extension {} from point {} (plugin {})",
                            reg.descriptor().getImplementationClassName(),
                            reg.pointName(), pluginId, e);
                }
            }
        }
    }

    /**
     * Rolls back a failed start, releases the plugin's resources and marks it {@link PluginState#FAILED}, then does
     * the same for every direct or indirect dependent that hasn't started. Nothing propagates to {@code startPlugins}.
     */
    private void failPluginStart(String pluginId, String reason) {
        releasePluginResources(pluginId, reason);

        Deque<String> queue = new ArrayDeque<>();
        queue.add(pluginId);
        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            for (PluginDescriptor dependent : descriptors.values()) {
                String dependentId = dependent.getPluginId();
                if (dependentId.equals(pluginId) || !dependent.getDependencies().contains(currentId)) {
                    continue;
                }
                PluginState dependentState = states.getOrDefault(dependentId, PluginState.UNLOADED);
                if (dependentState != PluginState.LOADED && dependentState != PluginState.STOPPED) {
                    continue;
                }
                releasePluginResources(dependentId, "Dependency failed to start: " + pluginId);
                queue.add(dependentId);
            }
        }
    }

    private void releasePluginResources(String pluginId, String reason) {
        unregisterPluginComponents(pluginId,
                registeredAppServices.remove(pluginId),
                registeredExtensions.remove(pluginId));
        unregisterExtensionPoints(pluginId);
        PluginClassLoader cl = classLoaders.remove(pluginId);
        if (cl != null) {
            cl.dispose();
        }
        markFailed(pluginId, reason);
    }

    @SuppressWarnings("unchecked")
    private void registerLazyRaw(ExtensionPoint<?> ep,
                                 ExtensionDescriptor<?> descriptor) {
        ((ExtensionPoint) ep).registerLazy(descriptor);
    }

    @SuppressWarnings("unchecked")
    private void unregisterLazyRaw(ExtensionPoint<?> ep,
                                   ExtensionDescriptor<?> descriptor) {
        ((ExtensionPoint) ep).unregisterLazy(descriptor);
    }

    @Override
    public void unloadPlugin(String pluginId) {
        checkNotDisposed();

        PluginState state = states.get(pluginId);
        if (state == PluginState.FAILED) {
            return;
        }
        assertNoLoadedDependents(pluginId);
        if (state == PluginState.STARTED) {
            stopPlugin(pluginId);
        }

        states.put(pluginId, PluginState.UNLOADING);

        unregisterExtensionPoints(pluginId);

        PluginClassLoader cl = classLoaders.remove(pluginId);
        if (cl != null) {
            cl.dispose();
        }

        states.put(pluginId, PluginState.UNLOADED);
        LOG.info("Unloaded plugin: {}", pluginId);
    }

    private void assertNoLoadedDependents(String pluginId) {
        List<String> loadedDependents = new ArrayList<>();
        for (PluginDescriptor desc : descriptors.values()) {
            String dependentId = desc.getPluginId();
            if (pluginId.equals(dependentId) || !desc.getDependencies().contains(pluginId)) {
                continue;
            }

            PluginState dependentState = states.get(dependentId);
            if (dependentState != null
                    && dependentState != PluginState.UNLOADED
                    && dependentState != PluginState.FAILED) {
                loadedDependents.add(dependentId);
            }
        }

        if (!loadedDependents.isEmpty()) {
            Collections.sort(loadedDependents);
            throw new IllegalStateException(
                    "Cannot unload plugin " + pluginId
                            + " because it is required by loaded plugin(s): "
                            + String.join(", ", loadedDependents));
        }
    }

    private void unregisterExtensionPoints(String pluginId) {
        ExtensionRegistry ext = app.getExtensionRegistry();
        List<ExtensionPointRegistration> pointRegistrations = registeredExtensionPoints.remove(pluginId);
        if (pointRegistrations == null) {
            return;
        }

        List<ExtensionPointRegistration> reverse = new ArrayList<>(pointRegistrations);
        Collections.reverse(reverse);
        for (ExtensionPointRegistration reg : reverse) {
            try {
                if (ext.unregisterExtensionPoint(reg.pointName())) {
                    LOG.debug("Unregistered extension point: {} (plugin: {})",
                            reg.pointName(), pluginId);
                }
            } catch (Exception e) {
                LOG.warn("Failed to unregister extension point {} (plugin {})",
                        reg.pointName(), pluginId, e);
            }
        }
    }

    @Override
    public void dispose() {
        if (disposed) return;
        LOG.info("Disposing PluginManager...");

        List<PluginDescriptor> reverse = new ArrayList<>(getLoadableTopologicalOrder());
        Collections.reverse(reverse);
        for (PluginDescriptor desc : reverse) {
            try {
                unloadPlugin(desc.getPluginId());
            } catch (Exception e) {
                LOG.warn("Error unloading plugin: {}", desc.getPluginId(), e);
            }
        }

        disposed = true;
        descriptors.clear();
        states.clear();
        classLoaders.clear();
        registeredExtensions.clear();
        registeredExtensionPoints.clear();
        registeredAppServices.clear();
    }

    // ============== Queries ==============

    @Override
    public List<PluginDescriptor> getAllPlugins() {
        return List.copyOf(descriptors.values());
    }

    @Override
    public List<PluginDescriptor> getStartedPlugins() {
        return descriptors.values().stream()
                .filter(d -> states.get(d.getPluginId()) == PluginState.STARTED)
                .toList();
    }

    @Override
    public PluginDescriptor getPlugin(String pluginId) {
        return descriptors.get(pluginId);
    }

    @Override
    public PluginState getPluginState(String pluginId) {
        return states.getOrDefault(pluginId, PluginState.UNLOADED);
    }

    @Override
    public boolean isPluginStarted(String pluginId) {
        return states.get(pluginId) == PluginState.STARTED;
    }

    @Override
    public boolean isPluginLoaded(String pluginId) {
        PluginState s = states.get(pluginId);
        return s == PluginState.LOADED || s == PluginState.STARTED
                || s == PluginState.STOPPED;
    }

    @Override
    public PluginClassLoader getPluginClassLoader(String pluginId) {
        return classLoaders.get(pluginId);
    }

    // ============== Topological order ==============

    /** Returns loadable plugins in dependency order, marking those with missing or circular dependencies FAILED. */
    private List<PluginDescriptor> getLoadableTopologicalOrder() {
        List<PluginDescriptor> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();

        for (PluginDescriptor desc : descriptors.values()) {
            if (!visited.contains(desc.getPluginId())) {
                visitPlugin(desc, visited, visiting, stack, result);
            }
        }

        return result;
    }

    private boolean visitPlugin(PluginDescriptor desc, Set<String> visited,
                                Set<String> visiting, Deque<String> stack,
                                List<PluginDescriptor> result) {
        String id = desc.getPluginId();
        if (states.get(id) == PluginState.FAILED) {
            return false;
        }
        if (visited.contains(id)) {
            return states.get(id) != PluginState.FAILED;
        }
        if (visiting.contains(id)) {
            markCircularDependency(id, stack);
            return false;
        }

        visiting.add(id);
        stack.addLast(id);
        boolean loadable = true;
        for (String depId : desc.getDependencies()) {
            if (isPlatformDependency(depId)) {
                continue;
            }
            PluginDescriptor dep = descriptors.get(depId);
            if (dep != null) {
                if (!visitPlugin(dep, visited, visiting, stack, result)) {
                    markFailed(id, "Dependency is not loadable: " + depId);
                    loadable = false;
                }
            } else {
                markFailed(id, "Missing dependency: " + depId);
                loadable = false;
            }
        }
        stack.removeLast();
        visiting.remove(id);
        visited.add(id);
        if (loadable && states.get(id) != PluginState.FAILED) {
            result.add(desc);
            return true;
        }
        return false;
    }

    private boolean isPlatformDependency(String pluginId) {
        return PLATFORM_PLUGIN_IDS.contains(pluginId);
    }

    private void markCircularDependency(String repeatedId, Deque<String> stack) {
        List<String> cycle = new ArrayList<>();
        boolean inCycle = false;
        for (String pluginId : stack) {
            if (pluginId.equals(repeatedId)) {
                inCycle = true;
            }
            if (inCycle) {
                cycle.add(pluginId);
            }
        }
        cycle.add(repeatedId);

        Set<String> cyclePlugins = new LinkedHashSet<>(cycle);
        String cycleText = String.join(" -> ", cycle);
        for (String pluginId : cyclePlugins) {
            markFailed(pluginId, "Circular dependency: " + cycleText);
        }
    }

    private void markFailed(String pluginId, String reason) {
        if (states.get(pluginId) != PluginState.FAILED) {
            LOG.warn("Plugin {} marked FAILED: {}", pluginId, reason);
        }
        states.put(pluginId, PluginState.FAILED);
    }

    // ============== Class loaders ==============

    private void createClassLoader(PluginDescriptor desc) {
        List<URL> urls = new ArrayList<>();

        for (Path cpEntry : desc.getClassPath()) {
            try {
                urls.add(cpEntry.toUri().toURL());
            } catch (MalformedURLException e) {
                LOG.warn("Invalid classpath entry: {}", cpEntry);
            }
        }

        List<PluginClassLoader> depLoaders = new ArrayList<>();
        for (String depId : desc.getDependencies()) {
            PluginClassLoader depCl = classLoaders.get(depId);
            if (depCl != null) {
                depLoaders.add(depCl);
            }
        }

        ClassLoader parent = appClassLoader();
        PluginClassLoader cl = new PluginClassLoader(urls.toArray(new URL[0]), parent, desc, depLoaders);
        classLoaders.put(desc.getPluginId(), cl);

        LOG.debug("Created class loader for plugin {} ({} JARs)", desc.getPluginId(), urls.size());
    }

    // ============== Discovery ==============

    private BundledPluginRegistry loadBundledRegistry() {
        StartupTrace startupTrace = StartupTraces.getCurrent();
        try (StartupSpan span = startupTrace.begin("plugins.bundledRegistry.load")) {
            List<BundledPluginEntry> bundledPlugins = new ArrayList<>();
            Set<String> bundledIds = new LinkedHashSet<>();
            Path registryFile = pluginsDir.resolve(BUNDLED_XML);

            if (!Files.exists(registryFile)) {
                startupTrace.incrementCounter("plugins.bundledRegistry.missing");
                LOG.info("No bundled-plugins.xml found — no bundled plugins.");
                return new BundledPluginRegistry(bundledPlugins, bundledIds);
            }

            try {
                Document doc = parseXml(registryFile);
                Element root = doc.getDocumentElement();
                NodeList children = root.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node node = children.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE && "plugin".equals(node.getNodeName())) {
                        Element el = (Element) node;
                        String id = blankToNull(el.getAttribute("id"));
                        if (id == null) {
                            LOG.warn("Ignoring bundled plugin entry without id in {}", registryFile);
                            continue;
                        }
                        if (!bundledIds.add(id)) {
                            LOG.warn("Ignoring duplicate bundled plugin id {} in {}", id, registryFile);
                            continue;
                        }
                        bundledPlugins.add(new BundledPluginEntry(id, blankToNull(el.getAttribute("path"))));
                    }
                }
                startupTrace.addCounter("plugins.bundledRegistry.entries", bundledIds.size());
                LOG.info("Loaded bundled-plugins.xml: {} plugins", bundledIds.size());
            } catch (Exception e) {
                span.failed(e);
                startupTrace.incrementCounter("plugins.bundledRegistry.errors");
                LOG.error("Failed to parse {}", registryFile, e);
            }

            return new BundledPluginRegistry(bundledPlugins, bundledIds);
        }
    }

    private List<DiscoveredPluginPath> discoverPluginPaths(BundledPluginRegistry bundledRegistry) {
        List<DiscoveredPluginPath> paths = new ArrayList<>();
        Set<Path> seenPaths = new LinkedHashSet<>();

        for (BundledPluginEntry entry : bundledRegistry.entries()) {
            if (entry.path() == null) {
                continue;
            }

            Path bundledPath = resolveBundledPluginPath(entry);
            if (bundledPath == null) {
                continue;
            }

            if (!Files.exists(bundledPath)) {
                LOG.warn("Bundled plugin {} points to missing path: {}",
                        entry.id(), bundledPath);
                continue;
            }
            if (!isPluginRootPath(bundledPath)) {
                LOG.warn("Bundled plugin {} path is not a plugin directory or JAR: {}",
                        entry.id(), bundledPath);
                continue;
            }

            if (seenPaths.add(normalizedPath(bundledPath))) {
                paths.add(new DiscoveredPluginPath(bundledPath, entry.id()));
            }
        }

        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(pluginsDir,
                PluginManagerImpl::isPluginRootPath)) {
            for (Path entry : dirs) {
                String name = entry.getFileName().toString();
                if (BUNDLED_XML.equals(name)) continue;
                if (seenPaths.add(normalizedPath(entry))) {
                    paths.add(new DiscoveredPluginPath(entry, null));
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan plugins directory: {}", pluginsDir, e);
        }

        return paths;
    }

    private Path resolveBundledPluginPath(BundledPluginEntry entry) {
        Path rawPath = Path.of(entry.path());
        if (rawPath.isAbsolute()) {
            LOG.warn("Bundled plugin {} path must be relative to the plugins directory: {}",
                    entry.id(), entry.path());
            return null;
        }

        Path pluginsRoot = normalizedPath(pluginsDir);
        Path resolved = pluginsRoot.resolve(rawPath).normalize();
        if (!resolved.startsWith(pluginsRoot)) {
            LOG.warn("Bundled plugin {} path escapes the plugins directory: {}",
                    entry.id(), entry.path());
            return null;
        }
        return resolved;
    }

    private static boolean isPluginRootPath(Path path) {
        return Files.isDirectory(path) || (Files.isRegularFile(path) && isJarPath(path));
    }

    private static boolean isJarPath(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static Path normalizedPath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private PluginDescriptor parsePluginDescriptor(Path pluginPath) {
        Document doc;
        if (Files.isDirectory(pluginPath)) {
            Path xmlPath = pluginPath.resolve(PLUGIN_XML);
            if (!Files.exists(xmlPath)) {
                xmlPath = pluginPath.resolve(PLUGIN_XML_ALT);
            }

            if (!Files.exists(xmlPath)) {
                LOG.debug("No plugin.xml found in {}", pluginPath);
                return null;
            }

            doc = parseXml(xmlPath);
        } else {
            doc = parseXmlInJar(pluginPath);
            if (doc == null) {
                LOG.debug("No plugin.xml found in {}", pluginPath);
                return null;
            }
        }

        return buildDescriptor(doc, pluginPath);
    }

    private PluginDescriptor buildDescriptor(Document doc, Path pluginPath) {
        Element root = doc.getDocumentElement();
        PluginDescriptor.Builder builder = PluginDescriptor.builder();

        builder.pluginPath(pluginPath);

        // Required.
        builder.pluginId(childText(root, "id"));
        builder.name(childText(root, "name"));

        // Optional.
        String version = childText(root, "version");
        if (version != null) builder.version(version);

        String description = childText(root, "description");
        if (description != null) builder.description(description);

        Element vendorEl = childElement(root, "vendor");
        if (vendorEl != null) {
            builder.vendor(vendorEl.getTextContent().trim());
            String email = vendorEl.getAttribute("email");
            if (email != null && !email.isEmpty()) builder.vendorEmail(email);
            String url = vendorEl.getAttribute("url");
            if (url != null && !url.isEmpty()) builder.vendorUrl(url);
        }

        for (Element depEl : childElements(root, "depends")) {
            String depId = depEl.getTextContent().trim();
            if (!depId.isEmpty()) {
                builder.addDependency(depId);
            }
        }

        Element epContainer = childElement(root, "extensionPoints");
        if (epContainer != null) {
            for (Element epEl : childElements(epContainer, "extensionPoint")) {
                String epName = epEl.getAttribute("name");
                String epClass = extensionPointClassName(epEl);
                if (epName != null && epClass != null) {
                    builder.addExtensionPoint(
                            new PluginDescriptor.ExtensionPointDeclaration(epName, epClass));
                }
            }
        }

        Element extContainer = childElement(root, "extensions");
        if (extContainer != null) {
            for (Element extEl : childElements(extContainer, "extension")) {
                String point = extEl.getAttribute("point");
                String impl = extEl.getAttribute("implementation");
                String extId = extEl.getAttribute("id");
                if (point != null && impl != null) {
                    builder.addExtension(
                            new PluginDescriptor.ExtensionDeclaration(point, impl, extId));
                }
            }

            for (Element svcEl : childElements(extContainer, "applicationService")) {
                String iface = svcEl.getAttribute("serviceInterface");
                String impl = svcEl.getAttribute("serviceImplementation");
                if (iface != null && impl != null) {
                    builder.addAppService(new AppServiceDeclaration(iface, impl,
                            parsePreloadMode(svcEl, iface, pluginPath)));
                }
            }
        }

        discoverClassPath(pluginPath, builder);

        return builder.build();
    }

    private ServicePreloadMode parsePreloadMode(Element serviceElement, String iface, Path pluginPath) {
        String preload = serviceElement.getAttribute("preload");
        try {
            return ServicePreloadMode.fromXmlAttribute(preload);
        } catch (IllegalArgumentException e) {
            LOG.warn("Unknown plugin service preload mode '{}' for {} in {}; using none",
                    preload, iface, pluginPath);
            return ServicePreloadMode.NONE;
        }
    }

    private String extensionPointClassName(Element ep) {
        String iface = ep.getAttribute("interface");
        if (iface != null && !iface.isEmpty()) {
            return iface;
        }
        String beanClass = ep.getAttribute("beanClass");
        if (beanClass != null && !beanClass.isEmpty()) {
            return beanClass;
        }
        return null;
    }

    private void discoverClassPath(Path pluginPath, PluginDescriptor.Builder builder) {
        if (Files.isDirectory(pluginPath)) {
            Path libDir = pluginPath.resolve("lib");
            if (Files.isDirectory(libDir)) {
                try (DirectoryStream<Path> jars = Files.newDirectoryStream(libDir, "*.jar")) {
                    for (Path jar : jars) {
                        builder.addClassPathEntry(jar);
                    }
                } catch (IOException e) {
                    LOG.warn("Failed to scan lib dir: {}", libDir, e);
                }
            }
            Path classesDir = pluginPath.resolve("classes");
            if (Files.isDirectory(classesDir)) {
                builder.addClassPathEntry(classesDir);
            }
        } else if (isJarPath(pluginPath)) {
            builder.addClassPathEntry(pluginPath);
        }
    }

    private Document parseXmlInJar(Path jarPath) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(PLUGIN_XML);
            if (entry == null) {
                entry = jarFile.getJarEntry(PLUGIN_XML_ALT);
            }
            if (entry == null) {
                return null;
            }

            try (InputStream input = jarFile.getInputStream(entry)) {
                return parseXml(input, jarPath + "!/" + entry.getName());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read plugin XML from JAR: " + jarPath, e);
        }
    }

    // ============== XML helpers ==============

    private Document parseXml(Path path) {
        return SecureXmlParser.parse(path);
    }

    private Document parseXml(InputStream input, String sourceDescription) {
        return SecureXmlParser.parse(input, sourceDescription);
    }

    private static String childText(Element parent, String tagName) {
        Element el = childElement(parent, tagName);
        return el != null ? el.getTextContent().trim() : null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Element childElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    // ============== Helpers ==============

    private long getLoadedCount() {
        return states.values().stream()
                .filter(s -> s == PluginState.LOADED || s == PluginState.STARTED).count();
    }

    private long getStartedCount() {
        return states.values().stream()
                .filter(s -> s == PluginState.STARTED).count();
    }

    private void checkNotDisposed() {
        if (disposed) throw new IllegalStateException("PluginManager is disposed");
    }
}
