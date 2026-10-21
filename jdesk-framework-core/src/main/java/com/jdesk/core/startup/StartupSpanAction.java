package com.jdesk.core.startup;

/**
 * Java-friendly startup span action that receives the active span.
 */
@FunctionalInterface
public interface StartupSpanAction<T> {

    T run(StartupSpan span) throws Exception;
}
