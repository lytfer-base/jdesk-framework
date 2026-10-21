package com.jdesk.core.plugins;

import com.jdesk.core.disposer.Disposable;

import java.util.List;

/**
 * Manages the plugin lifecycle: discovery, loading, start, stop and unload. Plugins are found in the plugins directory,
 * each described by a {@code META-INF/plugin.xml} with its ID, dependencies, extension points, extensions and
 * application services. Call {@link #loadPlugins()}, {@link #initPlugins()} and {@link #startPlugins()} in order.
 */
public interface PluginManager extends Disposable {

    /**
     * Discovers and parses all plugin descriptors; folder and single-JAR plugins are both scanned for
     * {@code META-INF/plugin.xml}.
     */
    void loadPlugins();

    /**
     * Creates the class loaders of the discovered plugins and registers their extension points; plugin classes can
     * load afterwards, but contributions aren't active yet.
     */
    void initPlugins();

    /** Starts every loadable plugin by registering its extensions and application services. */
    void startPlugins();

    /** Stops a plugin, unregistering its extensions and application services. */
    void stopPlugin(String pluginId);

    /** Unloads a plugin, releasing its class loader and registrations. */
    void unloadPlugin(String pluginId);

    /** Stops and unloads every plugin, then disposes the manager. */
    void dispose();

    // ---- Queries ----

    /** Returns the descriptors of all discovered plugins, loaded or not. */
    List<PluginDescriptor> getAllPlugins();

    /** Returns the descriptors of the started plugins. */
    List<PluginDescriptor> getStartedPlugins();

    /** Returns the descriptor of a plugin, or {@code null} if it wasn't discovered. */
    PluginDescriptor getPlugin(String pluginId);

    /** Returns the current state of a plugin. */
    PluginState getPluginState(String pluginId);

    /** Returns whether the plugin is started. */
    boolean isPluginStarted(String pluginId);

    /** Returns whether the plugin's class loader is available. */
    boolean isPluginLoaded(String pluginId);

    /** Returns the plugin's class loader, or {@code null} if it isn't loaded. */
    PluginClassLoader getPluginClassLoader(String pluginId);
}
