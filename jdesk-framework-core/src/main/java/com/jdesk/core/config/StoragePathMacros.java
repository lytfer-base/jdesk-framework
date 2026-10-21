package com.jdesk.core.config;

/**
 * Standard storage path macros that {@link StateStorageManager} expands.
 */
public final class StoragePathMacros {

    public static final String APP_CONFIG = "$APP_CONFIG$";
    public static final String CACHE = "$CACHE$";

    private StoragePathMacros() {
    }
}
