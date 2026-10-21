package com.jdesk.frame.boot;

/**
 * Startup-critical activity that runs after the application context exists
 * and before the first window is shown.
 *
 * <p>Failures abort startup and trigger normal startup rollback.
 */
@FunctionalInterface
public interface ApplicationActivity {
    void runActivity(ApplicationContext context);
}
