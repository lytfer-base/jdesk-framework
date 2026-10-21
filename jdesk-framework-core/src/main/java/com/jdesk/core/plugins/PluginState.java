package com.jdesk.core.plugins;

/** Plugin lifecycle states. */
public enum PluginState {

    /** The descriptor is parsed but no class loader exists yet. */
    UNLOADED,

    /** The class loader exists and plugin classes can load. */
    LOADED,

    /** Extension and service descriptors are registered and the plugin is active. */
    STARTED,

    /** The plugin is stopping and its extensions and services are being unregistered. */
    STOPPING,

    /** The plugin has stopped but keeps its class loader. */
    STOPPED,

    /** The plugin is unloading and its class loader is about to be released. */
    UNLOADING,

    /** The plugin can't be used: its descriptor or dependencies are invalid, or it failed to start. */
    FAILED
}
