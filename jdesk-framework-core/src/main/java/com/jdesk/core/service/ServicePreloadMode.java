package com.jdesk.core.service;

/**
 * Controls how a registered service participates in startup preloading.
 */
public enum ServicePreloadMode {
    /** Do not create the service during startup. */
    NONE,

    /** Create the service before startup passes the container initialization boundary. */
    AWAIT,

    /** Start service creation in the background without blocking the startup boundary. */
    ASYNC;

    public static ServicePreloadMode fromXmlAttribute(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "none" -> NONE;
            case "await" -> AWAIT;
            case "async" -> ASYNC;
            default -> throw new IllegalArgumentException("Unknown service preload mode: " + value);
        };
    }
}
