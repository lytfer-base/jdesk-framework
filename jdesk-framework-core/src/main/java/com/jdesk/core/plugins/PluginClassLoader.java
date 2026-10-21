package com.jdesk.core.plugins;

import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.core.runtime.params.CoreRuntimeParams;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Class loader that isolates one plugin. JDK, framework and host library classes always come from the parent, so
 * plugin and host share API types; other classes come from the plugin's own JARs and {@code classes} folder, then
 * from the plugins it depends on, then from the parent.
 */
public class PluginClassLoader extends URLClassLoader {

    private final PluginDescriptor descriptor;
    private final List<PluginClassLoader> dependencyLoaders;

    /** JDK and platform packages that always load from the parent. */
    private static final Set<String> PLATFORM_PACKAGES = Set.of(
            "java.", "javax.", "sun.", "com.sun.",
            "org.w3c.", "org.xml.", "org.ietf.", "org.omg."
    );

    /**
     * Package prefixes of the core module, which always come from the host; don't split them into subpackages or
     * widen them to {@code com.jdesk.}.
     */
    private static final Set<String> FRAMEWORK_PACKAGES = Set.of(
            "com.jdesk.core.",
            "com.jdesk.concurrency.",
            "com.jdesk.util."
    );

    /**
     * Third-party libraries in core's public API (coroutines, the logging facade); plugins use the host's copy even
     * if they bundle their own. List only libraries the host ships.
     */
    private static final Set<String> HOST_LIBRARY_PACKAGES = Set.of(
            "kotlin.",
            "kotlinx.coroutines.",
            "org.slf4j."
    );

    /** Names of the classes this loader defined, for diagnosing unload problems and leaks. */
    private final Set<String> loadedClasses = new CopyOnWriteArraySet<>();

    private volatile boolean disposed;

    /**
     * Creates a loader over the plugin's JARs and {@code classes} folder; {@code parent} is usually the application
     * class loader and {@code depLoaders} are the loaders of the plugins it depends on.
     */
    public PluginClassLoader(URL[] urls, ClassLoader parent,
                             PluginDescriptor descriptor,
                             List<PluginClassLoader> depLoaders) {
        super(urls, parent);
        this.descriptor = descriptor;
        this.dependencyLoaders = List.copyOf(depLoaders);
    }

    /** Returns the descriptor of the plugin this loader belongs to. */
    public PluginDescriptor getDescriptor() {
        return descriptor;
    }

    /** Returns a snapshot of the class names this loader defined, for diagnostics. */
    public Set<String> getLoadedClassNames() {
        return Set.copyOf(loadedClasses);
    }

    // ---- Class loading ----

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 1. Already loaded by this loader.
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;

            // 2. Platform classes always come from the parent.
            if (isPlatformClass(name)) {
                return super.loadClass(name, resolve);
            }

            // 3. Framework API from the parent, so plugins see the shared types.
            if (isFrameworkClass(name)) {
                return super.loadClass(name, resolve);
            }

            // 4. The plugin's own class path.
            try {
                c = findClass(name);
                if (c != null) {
                    loadedClasses.add(name);
                    if (resolve) resolveClass(c);
                    return c;
                }
            } catch (ClassNotFoundException ignored) {
                // Not in this plugin.
            }

            // 5. The plugins it depends on.
            for (PluginClassLoader dep : dependencyLoaders) {
                try {
                    c = dep.loadClass(name, resolve);
                    if (c != null) return c;
                } catch (ClassNotFoundException ignored) {
                }
            }

            // 6. Finally the parent.
            return super.loadClass(name, resolve);
        }
    }

    // ---- Resources ----

    @Override
    public URL findResource(String name) {
        URL url = super.findResource(name);
        if (url != null) return url;
        for (PluginClassLoader dep : dependencyLoaders) {
            url = dep.findResource(name);
            if (url != null) return url;
        }
        return null;
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        // Only this plugin's resources, not those of its dependencies.
        return super.findResources(name);
    }

    // ---- Disposal ----

    /** Closes the loader; don't load classes or resources through it afterwards. */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        try {
            close();
        } catch (IOException ignored) {
        }
        loadedClasses.clear();
    }

    public boolean isDisposed() {
        return disposed;
    }

    private static boolean isPlatformClass(String name) {
        for (String prefix : PLATFORM_PACKAGES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isFrameworkClass(String name) {
        for (String prefix : FRAMEWORK_PACKAGES) {
            if (name.startsWith(prefix)) return true;
        }
        for (String prefix : HOST_LIBRARY_PACKAGES) {
            if (name.startsWith(prefix)) return true;
        }
        for (String prefix : configuredParentFirstPackages()) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static List<String> configuredParentFirstPackages() {
        String rawValue = RuntimeParamRegistry.getString(CoreRuntimeParams.PLUGIN_PARENT_FIRST_PACKAGES, "");
        if (rawValue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawValue.split(","))
                .map(String::trim)
                .filter(prefix -> !prefix.isEmpty())
                .toList();
    }
}
