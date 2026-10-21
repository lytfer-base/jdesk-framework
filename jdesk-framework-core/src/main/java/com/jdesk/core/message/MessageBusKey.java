package com.jdesk.core.message;

import java.util.Objects;

/**
 * Stable lookup key of a {@link MessageBus}. It only identifies the bus; the bus lifecycle stays with the
 * {@code Disposable} given when it was created.
 */
public final class MessageBusKey {

    private static final String APPLICATION_SCOPE = "application";
    private static final String PROJECT_SCOPE = "project";
    private static final String SERVICE_SCOPE = "service";

    private static final MessageBusKey APPLICATION =
            new MessageBusKey(APPLICATION_SCOPE, "default");

    private final String scope;
    private final String id;
    private final int hash;

    private MessageBusKey(String scope, String id) {
        this.scope = requireText(scope, "scope");
        this.id = requireText(id, "id");
        this.hash = Objects.hash(this.scope, this.id);
    }

    /** Returns the key of the application bus. */
    public static MessageBusKey application() {
        return APPLICATION;
    }

    /** Returns the key of a project-scoped bus. */
    public static MessageBusKey project(String projectId) {
        return of(PROJECT_SCOPE, projectId);
    }

    /** Returns the key of a service-scoped bus named after the service class. */
    public static MessageBusKey service(Class<?> serviceClass) {
        Objects.requireNonNull(serviceClass, "serviceClass");
        return service(serviceClass.getName());
    }

    /** Returns the key of a service-scoped bus for a stable service ID. */
    public static MessageBusKey service(String serviceId) {
        return of(SERVICE_SCOPE, serviceId);
    }

    /** Returns the key of a bus in a custom scope. */
    public static MessageBusKey of(String scope, String id) {
        return new MessageBusKey(scope, id);
    }

    public String scope() {
        return scope;
    }

    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MessageBusKey other)) return false;
        return scope.equals(other.scope) && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "MessageBusKey{" + scope + ":" + id + "}";
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
