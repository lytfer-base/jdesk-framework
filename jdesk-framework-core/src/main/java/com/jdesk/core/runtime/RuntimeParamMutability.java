package com.jdesk.core.runtime;

/**
 * Whether a runtime parameter may change after the application has started.
 */
public enum RuntimeParamMutability {
    /** Set during startup and read-only afterwards. */
    LOCKED_AFTER_BOOT,

    /** May change at any time. */
    MUTABLE
}
