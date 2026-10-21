package com.jdesk.core.app;

/**
 * State of an application-owned background task.
 */
public enum ApplicationTaskStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
