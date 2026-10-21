package com.jdesk.demo.builtin;

import com.jdesk.demo.GreetingProvider;
import com.jdesk.util.SystemInfo;

/**
 * Built-in greeting provider — reports Java version and CPU count.
 * Registered via app-plugins.xml {@code <extensions>} section,
 * NOT programmatically in DemoApp.
 */
public class JavaInfoGreetingProvider implements GreetingProvider {

    @Override
    public String getGreeting() {
        return "Java " + SystemInfo.JAVA_VERSION + " | " + SystemInfo.AVAILABLE_PROCESSORS + " CPUs";
    }
}
