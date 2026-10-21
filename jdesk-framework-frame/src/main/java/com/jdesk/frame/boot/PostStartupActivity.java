package com.jdesk.frame.boot;

/**
 * Non-critical activity that runs after the first window becomes visible.
 *
 * <p>Failures are logged and do not shut down the application.
 */
@FunctionalInterface
public interface PostStartupActivity {
    void runActivity(ApplicationContext context);
}
