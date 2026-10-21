package com.jdesk.core.runtime;

/**
 * Listener notified when a runtime parameter value changes.
 */
@FunctionalInterface
public interface RuntimeParamChangeListener {

    /** Called after the value has actually changed. */
    void parameterChanged(RuntimeParamValue oldValue, RuntimeParamValue newValue);
}
