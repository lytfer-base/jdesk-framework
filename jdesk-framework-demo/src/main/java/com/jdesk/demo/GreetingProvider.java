package com.jdesk.demo;

/**
 * Extension point contract for greeting providers.
 */
@FunctionalInterface
public interface GreetingProvider {
    String getGreeting();
}
