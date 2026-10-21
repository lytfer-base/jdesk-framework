package com.jdesk.demo.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of PluginGreeterService, contributed by the demo-greeter plugin.
 *
 * <p>This class is NOT loaded at startup. It is only loaded when the first
 * service lookup triggers lazy instantiation through {@code ServiceDescriptor.ofClassName()}.
 */
public class PluginGreeterServiceImpl implements PluginGreeterService {

    private static final Logger LOG = LoggerFactory.getLogger(PluginGreeterServiceImpl.class);

    private final long loadTime;

    public PluginGreeterServiceImpl() {
        this.loadTime = System.currentTimeMillis();
        LOG.info("PluginGreeterServiceImpl instantiated (lazy load at {})", loadTime);
    }

    @Override
    public String greet(String name) {
        return "Hello " + name + "! (from plugin service, loaded at "
                + new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date(loadTime)) + ")";
    }

    @Override
    public String getPluginInfo() {
        return "Demo Greeter Plugin v1.0.0 - PluginGreeterServiceImpl";
    }
}
