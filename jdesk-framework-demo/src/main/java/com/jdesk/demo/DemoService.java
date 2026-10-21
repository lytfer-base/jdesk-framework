package com.jdesk.demo;

/**
 * A demo service registered in the DI container.
 * Demonstrates that services can be resolved via {@code Application.getService()}.
 */
public class DemoService {

    private final long createdAt = System.currentTimeMillis();

    public String getMessage() {
        return "DemoService instance created at " + createdAt;
    }

    public String getUptime() {
        long uptime = System.currentTimeMillis() - createdAt;
        return uptime / 1000 + " seconds";
    }
}
