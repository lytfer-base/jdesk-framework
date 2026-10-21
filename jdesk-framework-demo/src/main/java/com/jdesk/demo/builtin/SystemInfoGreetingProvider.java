package com.jdesk.demo.builtin;

import com.jdesk.demo.GreetingProvider;
import com.jdesk.util.SystemInfo;

/**
 * Built-in greeting provider — reports OS name.
 * Registered via app-plugins.xml {@code <extensions>} section,
 * NOT programmatically in DemoApp.
 */
public class SystemInfoGreetingProvider implements GreetingProvider {

    @Override
    public String getGreeting() {
        return "JDesk Framework running on " + SystemInfo.OS_NAME;
    }
}
