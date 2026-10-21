package com.jdesk.core.runtime;

/**
 * Where the current value of a runtime parameter came from.
 */
public enum RuntimeParamSource {
    DEFAULT,
    STARTUP_CONFIG,
    SYSTEM_PROPERTY,
    USER_SETTING,
    PROGRAMMATIC
}
