package com.jdesk.frame.boot;

import com.jdesk.core.extensions.ExtensionDescriptor;
import com.jdesk.core.extensions.ExtensionPoint;
import com.jdesk.core.extensions.ExtensionRegistry;
import com.jdesk.core.service.AppServiceDeclaration;
import com.jdesk.core.service.ServiceContainer;
import com.jdesk.core.service.ServiceDescriptor;
import com.jdesk.core.service.ServicePreloadMode;
import com.jdesk.core.startup.StartupTrace;
import com.jdesk.core.startup.StartupTraces;
import com.jdesk.util.xml.SecureXmlParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the host application's extension points, bundled extensions and services from
 * {@code META-INF/app-plugins.xml} on its classpath. It runs after the frame configuration, so the host can declare
 * its own extension points and contribute lazy extensions to frame and core ones; services are registered by class
 * name and their classes load on first use.
 */
final class ApplicationConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationConfigLoader.class);

    private static final String APP_PLUGINS_RESOURCE = "META-INF/app-plugins.xml";

    private final ExtensionRegistry extensionRegistry;
    private final ServiceContainer serviceContainer;
    private final ClassLoader applicationClassLoader;

    ApplicationConfigLoader(ExtensionRegistry extensionRegistry,
                            ServiceContainer serviceContainer,
                            ClassLoader applicationClassLoader) {
        this.extensionRegistry = extensionRegistry;
        this.serviceContainer = serviceContainer;
        this.applicationClassLoader = applicationClassLoader;
    }

    XmlConfigurationDescriptor parseApplicationConfiguration() {
        StartupTrace startupTrace = StartupTraces.getCurrent();
        try (InputStream in = applicationClassLoader.getResourceAsStream(APP_PLUGINS_RESOURCE)) {
            if (in == null) {
                LOG.info("{} not found on application classpath; no application extensions loaded",
                        APP_PLUGINS_RESOURCE);
                startupTrace.incrementCounter("application.config.missing");
                return XmlConfigurationDescriptor.empty(APP_PLUGINS_RESOURCE);
            }

            Document doc = SecureXmlParser.parse(in, APP_PLUGINS_RESOURCE);
            Element root = doc.getDocumentElement();
            List<XmlConfigurationDescriptor.ExtensionPointDeclaration> extensionPoints = new ArrayList<>();
            List<XmlConfigurationDescriptor.ExtensionDeclaration> extensions = new ArrayList<>();
            List<AppServiceDeclaration> applicationServices = new ArrayList<>();

            Element extensionPointContainer = childElement(root, "extensionPoints");
            if (extensionPointContainer != null) {
                parseExtensionPoints(extensionPointContainer, extensionPoints);
            }

            Element extensionContainer = childElement(root, "extensions");
            if (extensionContainer != null) {
                parseApplicationExtensions(extensionContainer, extensions, applicationServices);
            }

            LOG.info("Parsed application plugin config from {}", APP_PLUGINS_RESOURCE);
            return new XmlConfigurationDescriptor(
                    APP_PLUGINS_RESOURCE,
                    extensionPoints,
                    extensions,
                    applicationServices);
        } catch (Exception e) {
            startupTrace.incrementCounter("application.config.errors");
            LOG.error("Failed to load {}", APP_PLUGINS_RESOURCE, e);
            return XmlConfigurationDescriptor.empty(APP_PLUGINS_RESOURCE);
        }
    }

    void applyApplicationConfiguration(XmlConfigurationDescriptor descriptor) {
        for (XmlConfigurationDescriptor.ExtensionPointDeclaration extensionPoint : descriptor.getExtensionPoints()) {
            registerExtensionPoint(extensionPoint.name(), extensionPoint.extensionClassName());
        }
        for (XmlConfigurationDescriptor.ExtensionDeclaration extension : descriptor.getExtensions()) {
            registerApplicationExtension(extension.pointName(), extension.implementationClass(), extension.id());
        }
        for (AppServiceDeclaration service : descriptor.getApplicationServices()) {
            registerApplicationService(service.getServiceInterface(),
                    service.getServiceImplementation(),
                    service.getPreloadMode());
        }
        LOG.info("Applied application plugin config from {}", descriptor.getSource());
    }

    private void parseExtensionPoints(
            Element container,
            List<XmlConfigurationDescriptor.ExtensionPointDeclaration> extensionPoints) {
        NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && "extensionPoint".equals(node.getNodeName())) {
                Element extensionPoint = (Element) node;
                String name = extensionPoint.getAttribute("name");
                String className = extensionPointClassName(extensionPoint);
                if (isPresent(name) && isPresent(className)) {
                    extensionPoints.add(new XmlConfigurationDescriptor.ExtensionPointDeclaration(name, className));
                }
            }
        }
    }

    private String extensionPointClassName(Element extensionPoint) {
        String iface = extensionPoint.getAttribute("interface");
        if (isPresent(iface)) {
            return iface;
        }
        String beanClass = extensionPoint.getAttribute("beanClass");
        if (isPresent(beanClass)) {
            return beanClass;
        }
        return null;
    }

    private void registerExtensionPoint(String name, String className) {
        try {
            Class<?> extensionClass = Class.forName(className, true, applicationClassLoader);
            extensionRegistry.registerExtensionPoint(name, extensionClass);
            StartupTraces.getCurrent().incrementCounter("application.extensionPoints");
            LOG.debug("Loaded application extension point: {}", name);
        } catch (ClassNotFoundException e) {
            LOG.warn("Application extension point class not found: {} (point: {})", className, name);
        }
    }

    private void parseApplicationExtensions(
            Element container,
            List<XmlConfigurationDescriptor.ExtensionDeclaration> extensions,
            List<AppServiceDeclaration> applicationServices) {
        NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            if ("extension".equals(node.getNodeName())) {
                parseExtension((Element) node, extensions);
            } else if ("applicationService".equals(node.getNodeName())) {
                parseApplicationService((Element) node, applicationServices);
            }
        }
    }

    private void parseExtension(Element extensionElement,
                                List<XmlConfigurationDescriptor.ExtensionDeclaration> extensions) {
        String pointName = extensionElement.getAttribute("point");
        String implementationClass = extensionElement.getAttribute("implementation");
        String id = extensionElement.getAttribute("id");
        if (!isPresent(pointName) || !isPresent(implementationClass)) {
            return;
        }

        extensions.add(new XmlConfigurationDescriptor.ExtensionDeclaration(
                pointName,
                implementationClass,
                id));
    }

    private void registerApplicationExtension(String pointName, String implementationClass, String id) {
        if (!isPresent(pointName) || !isPresent(implementationClass)) {
            return;
        }

        ExtensionPoint<?> point = extensionRegistry.getExtensionPoint(pointName);
        if (point == null) {
            LOG.warn("Extension point '{}' not found for application extension '{}'",
                    pointName, implementationClass);
            return;
        }

        ExtensionDescriptor<?> descriptor = new ExtensionDescriptor<>(
                point.getExtensionClass(),
                implementationClass,
                applicationClassLoader,
                id);
        registerLazyRaw(point, descriptor);
        StartupTraces.getCurrent().incrementCounter("application.extensions.lazyRegistered");
        LOG.debug("Loaded application lazy extension: {} -> {}", pointName, implementationClass);
    }

    private void parseApplicationService(Element serviceElement, List<AppServiceDeclaration> applicationServices) {
        String iface = serviceElement.getAttribute("serviceInterface");
        String impl = serviceElement.getAttribute("serviceImplementation");
        if (!isPresent(iface) || !isPresent(impl)) {
            return;
        }

        applicationServices.add(new AppServiceDeclaration(iface, impl,
                parsePreloadMode(serviceElement, iface)));
    }

    private ServicePreloadMode parsePreloadMode(Element serviceElement, String iface) {
        String preload = serviceElement.getAttribute("preload");
        try {
            return ServicePreloadMode.fromXmlAttribute(preload);
        } catch (IllegalArgumentException e) {
            LOG.warn("Unknown application service preload mode '{}' for {} in {}; using none",
                    preload, iface, APP_PLUGINS_RESOURCE);
            return ServicePreloadMode.NONE;
        }
    }

    private void registerApplicationService(String iface, String impl, ServicePreloadMode preloadMode) {
        if (!isPresent(iface) || !isPresent(impl)) {
            return;
        }

        try {
            Class<?> ifaceClass = Class.forName(iface, true, applicationClassLoader);
            registerServiceRaw(ifaceClass, impl, preloadMode);
            StartupTraces.getCurrent().incrementCounter("application.services.registered");
            LOG.info("Loaded application service: {} -> {}", iface, impl);
        } catch (ClassNotFoundException e) {
            LOG.warn("Application service interface not found: {} (impl: {})", iface, impl);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void registerLazyRaw(ExtensionPoint<?> point, ExtensionDescriptor<?> descriptor) {
        ((ExtensionPoint<T>) point).registerLazy((ExtensionDescriptor<T>) descriptor);
    }

    @SuppressWarnings("unchecked")
    private <T> void registerServiceRaw(Class<?> iface,
                                        String implClassName,
                                        ServicePreloadMode preloadMode) {
        serviceContainer.registerService(
                (Class<T>) iface,
                ServiceDescriptor.builder((Class<T>) iface)
                        .implementationClassName(implClassName)
                        .classLoader(applicationClassLoader)
                        .preload(preloadMode)
                        .build());
    }

    private static Element childElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private boolean isPresent(String value) {
        return value != null && !value.isEmpty();
    }
}
