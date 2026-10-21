package com.jdesk.frame.boot;

import com.jdesk.core.service.AppServiceDeclaration;

import java.util.List;

/**
 * Parsed XML configuration model.
 *
 * <p>The model is intentionally free of loaded classes and runtime registry
 * mutations. Runtime state is changed only when a loader applies this model.
 */
final class XmlConfigurationDescriptor {

    private final String source;
    private final List<ExtensionPointDeclaration> extensionPoints;
    private final List<ExtensionDeclaration> extensions;
    private final List<AppServiceDeclaration> applicationServices;

    XmlConfigurationDescriptor(String source,
                               List<ExtensionPointDeclaration> extensionPoints,
                               List<ExtensionDeclaration> extensions,
                               List<AppServiceDeclaration> applicationServices) {
        this.source = source;
        this.extensionPoints = List.copyOf(extensionPoints);
        this.extensions = List.copyOf(extensions);
        this.applicationServices = List.copyOf(applicationServices);
    }

    static XmlConfigurationDescriptor empty(String source) {
        return new XmlConfigurationDescriptor(source, List.of(), List.of(), List.of());
    }

    String getSource() {
        return source;
    }

    List<ExtensionPointDeclaration> getExtensionPoints() {
        return extensionPoints;
    }

    List<ExtensionDeclaration> getExtensions() {
        return extensions;
    }

    List<AppServiceDeclaration> getApplicationServices() {
        return applicationServices;
    }

    record ExtensionPointDeclaration(String name, String extensionClassName) {
    }

    record ExtensionDeclaration(String pointName, String implementationClass, String id) {
    }
}
