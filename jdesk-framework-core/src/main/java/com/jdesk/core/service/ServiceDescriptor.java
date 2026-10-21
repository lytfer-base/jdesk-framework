package com.jdesk.core.service;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;

import java.lang.reflect.Constructor;

/**
 * Describes how a service instance is created: a prebuilt instance, a loaded implementation class instantiated on the
 * first {@code getService()}, or an implementation class name loaded only then, which plugins should prefer so
 * startup loads no implementation classes. Lazy implementations need a no-argument constructor or one taking an
 * {@link Application}.
 */
public class ServiceDescriptor<T> {

    private final Class<T> serviceInterface;
    private final Class<? extends T> implementationClass;
    private final String implementationClassName;
    private final ClassLoader classLoader;
    private final T prebuiltInstance;
    private final ServicePreloadMode preloadMode;

    private ServiceDescriptor(Builder<T> builder) {
        this.serviceInterface = builder.serviceInterface;
        this.implementationClass = builder.implementationClass;
        this.implementationClassName = builder.implementationClassName;
        this.classLoader = builder.classLoader;
        this.prebuiltInstance = builder.prebuiltInstance;
        this.preloadMode = builder.preloadMode;
    }

    public Class<T> getServiceInterface() { return serviceInterface; }

    public boolean isPreload() { return preloadMode != ServicePreloadMode.NONE; }

    public ServicePreloadMode getPreloadMode() { return preloadMode; }

    public T getPrebuiltInstance() { return prebuiltInstance; }

    /**
     * Returns the prebuilt instance, or creates one from the implementation class or class name, passing the current
     * application to a constructor that takes an {@link Application} if there is one.
     *
     * @throws RuntimeException if instantiation fails
     */
    public T createInstance() {
        if (prebuiltInstance != null) return prebuiltInstance;

        Class<? extends T> implClass = resolveImplementationClass();

        if (implClass != null) {
            try {
                Constructor<? extends T> appConstructor = findApplicationConstructor(implClass);
                if (appConstructor != null) {
                    Application app = ApplicationManager.getApplication();
                    return appConstructor.newInstance(app);
                }
                Constructor<? extends T> noArgConstructor = implClass.getDeclaredConstructor();
                noArgConstructor.setAccessible(true);
                return noArgConstructor.newInstance();
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to instantiate service " + serviceInterface.getName()
                        + " (impl: " + implClass.getName() + ")", e);
            }
        }

        throw new IllegalStateException(
                "No instance, implementation class, or class name for " + serviceInterface.getName());
    }

    private Class<? extends T> resolveImplementationClass() {
        Class<? extends T> implClass = implementationClass;
        if (implClass == null && implementationClassName != null) {
            try {
                ClassLoader loader = classLoader != null
                        ? classLoader
                        : getClass().getClassLoader();
                Class<?> raw = Class.forName(implementationClassName, true, loader);
                implClass = raw.asSubclass(serviceInterface);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(
                        "Implementation class not found: " + implementationClassName
                        + " (interface: " + serviceInterface.getName() + ")", e);
            }
        }
        return implClass;
    }

    private Constructor<? extends T> findApplicationConstructor(Class<? extends T> implClass) {
        try {
            Constructor<? extends T> constructor = implClass.getDeclaredConstructor(Application.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    // ---- Factories ----

    /** Describes a prebuilt instance. */
    public static <T> ServiceDescriptor<T> ofInstance(Class<T> serviceInterface, T instance) {
        return new Builder<T>(serviceInterface).prebuiltInstance(instance).build();
    }

    /** Describes a lazily created instance of an implementation class that is already loaded. */
    public static <T> ServiceDescriptor<T> ofClass(Class<T> serviceInterface,
                                                    Class<? extends T> implClass) {
        return new Builder<T>(serviceInterface).implementationClass(implClass).build();
    }

    /** Describes an implementation class, by fully qualified name, that {@code classLoader} loads on first use. */
    public static <T> ServiceDescriptor<T> ofClassName(Class<T> serviceInterface,
                                                        String implClassName,
                                                        ClassLoader classLoader) {
        return new Builder<T>(serviceInterface)
                .implementationClassName(implClassName)
                .classLoader(classLoader)
                .build();
    }

    // ---- Builder ----

    public static <T> Builder<T> builder(Class<T> serviceInterface) {
        return new Builder<>(serviceInterface);
    }

    public static class Builder<T> {
        private final Class<T> serviceInterface;
        private Class<? extends T> implementationClass;
        private String implementationClassName;
        private ClassLoader classLoader;
        private T prebuiltInstance;
        private ServicePreloadMode preloadMode = ServicePreloadMode.NONE;

        Builder(Class<T> serviceInterface) {
            this.serviceInterface = serviceInterface;
        }

        public Builder<T> implementationClass(Class<? extends T> implClass) {
            this.implementationClass = implClass;
            return this;
        }

        public Builder<T> implementationClassName(String className) {
            this.implementationClassName = className;
            return this;
        }

        public Builder<T> classLoader(ClassLoader loader) {
            this.classLoader = loader;
            return this;
        }

        public Builder<T> prebuiltInstance(T instance) {
            this.prebuiltInstance = instance;
            return this;
        }

        public Builder<T> preload(boolean preload) {
            this.preloadMode = preload ? ServicePreloadMode.AWAIT : ServicePreloadMode.NONE;
            return this;
        }

        public Builder<T> preload(ServicePreloadMode preloadMode) {
            this.preloadMode = preloadMode == null ? ServicePreloadMode.NONE : preloadMode;
            return this;
        }

        public ServiceDescriptor<T> build() {
            if (prebuiltInstance == null
                    && implementationClass == null
                    && implementationClassName == null) {
                throw new IllegalArgumentException(
                        "One of prebuiltInstance, implementationClass, or implementationClassName must be set");
            }
            return new ServiceDescriptor<>(this);
        }
    }
}
