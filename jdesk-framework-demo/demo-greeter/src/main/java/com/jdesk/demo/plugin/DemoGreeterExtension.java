package com.jdesk.demo.plugin;

import com.jdesk.demo.GreetingProvider;

/**
 * Demo extension contributed by the "Demo Greeter" plugin.
 * This class is loaded by the PluginClassLoader and its instance
 * is registered on the {@code deskframe.demo.greeting} extension point.
 */
public class DemoGreeterExtension implements GreetingProvider {

    @Override
    public String getGreeting() {
        return "[Plugin] Hello from the Demo Greeter Plugin v1.0.0! Loaded at "
                + java.time.LocalTime.now().withNano(0);
    }
}
