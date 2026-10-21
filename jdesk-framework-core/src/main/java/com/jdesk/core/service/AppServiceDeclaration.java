package com.jdesk.core.service;

/**
 * An application service that a plugin contributes with {@code <applicationService>} in plugin.xml. Registration
 * doesn't load the implementation class; the first {@code getService} for the interface loads and instantiates it.
 */
public class AppServiceDeclaration {
    private String serviceInterface;
    private String serviceImplementation;
    private ServicePreloadMode preloadMode = ServicePreloadMode.NONE;

    public AppServiceDeclaration() {
    }

    public AppServiceDeclaration(String serviceInterface, String serviceImplementation) {
        this(serviceInterface, serviceImplementation, ServicePreloadMode.NONE);
    }

    public AppServiceDeclaration(String serviceInterface,
                                 String serviceImplementation,
                                 ServicePreloadMode preloadMode) {
        this.serviceInterface = serviceInterface;
        this.serviceImplementation = serviceImplementation;
        this.preloadMode = normalizePreloadMode(preloadMode);
    }

    public String getServiceInterface() {
        return serviceInterface;
    }

    public void setServiceInterface(String serviceInterface) {
        this.serviceInterface = serviceInterface;
    }

    public String getServiceImplementation() {
        return serviceImplementation;
    }

    public void setServiceImplementation(String serviceImplementation) {
        this.serviceImplementation = serviceImplementation;
    }

    public ServicePreloadMode getPreloadMode() {
        return preloadMode;
    }

    public void setPreloadMode(ServicePreloadMode preloadMode) {
        this.preloadMode = normalizePreloadMode(preloadMode);
    }

    @Override
    public String toString() {
        return "AppSvc{" + serviceInterface + " -> " + serviceImplementation
                + ", preload=" + preloadMode + "}";
    }

    private static ServicePreloadMode normalizePreloadMode(ServicePreloadMode preloadMode) {
        return preloadMode == null ? ServicePreloadMode.NONE : preloadMode;
    }
}
