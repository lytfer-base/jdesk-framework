package com.jdesk.demo.plugin;

/**
 * Service interface that a plugin can contribute.
 * Host code can access this through the plugin class loader and ServiceContainer.
 */
public interface PluginGreeterService {

    /**
     * Greet with a plugin-specific message.
     */
    String greet(String name);

    /**
     * The plugin that provides this service.
     */
    String getPluginInfo();
}
