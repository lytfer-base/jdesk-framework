package com.jdesk.frame.boot;

import com.jdesk.core.extensions.ExtensionRegistry;
import com.jdesk.core.extensions.ExtensionDescriptor;
import com.jdesk.core.extensions.ExtensionPoint;
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
 * Loads the frame's own extension points and application service descriptors from the bundled
 * {@code META-INF/frame-plugins.xml}; {@link ApplicationConfigLoader} loads the host application's.
 */
final class FrameConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(FrameConfigLoader.class);

    private static final String FRAME_RESOURCE = "META-INF/frame-plugins.xml";

    private final ExtensionRegistry extensionRegistry;
    private final ServiceContainer serviceContainer;
    private final ClassLoader appClassLoader;

    FrameConfigLoader(ExtensionRegistry extensionRegistry,
                      ServiceContainer serviceContainer,
                      ClassLoader appClassLoader) {
        this.extensionRegistry = extensionRegistry;
        this.serviceContainer = serviceContainer;
        this.appClassLoader = appClassLoader;
    }

    XmlConfigurationDescriptor parseFrameConfiguration() {
        StartupTrace startupTrace = StartupTraces.getCurrent();
        try (InputStream in = appClassLoader.getResourceAsStream(FRAME_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(FRAME_RESOURCE + " not found on the application class loader");
            }

            Document doc = SecureXmlParser.parse(in, FRAME_RESOURCE);
            Element root = doc.getDocumentElement();
            List<XmlConfigurationDescriptor.ExtensionPointDeclaration> extensionPoints = new ArrayList<>();
            List<XmlConfigurationDescriptor.ExtensionDeclaration> extensions = new ArrayList<>();
            List<AppServiceDeclaration> applicationServices = new ArrayList<>();

            NodeList topChildren = root.getChildNodes();
            for (int i = 0; i < topChildren.getLength(); i++) {
                Node node = topChildren.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }

                String tagName = node.getNodeName();
                if ("extensionPoints".equals(tagName)) {
                    parseExtensionPoints((Element) node, extensionPoints);
                } else if ("extensions".equals(tagName)) {
                    parseFrameExtensions((Element) node, extensions, applicationServices);
                }
            }
            LOG.info("Parsed frame config from {}", FRAME_RESOURCE);
            return new XmlConfigurationDescriptor(
                    FRAME_RESOURCE,
                    extensionPoints,
                    extensions,
                    applicationServices);
        } catch (Exception e) {
            // Required: starting without these services would only surface later as "service not registered".
            startupTrace.incrementCounter("frame.config.errors");
            throw new IllegalStateException("Failed to load " + FRAME_RESOURCE, e);
        }
    }

    void applyFrameConfiguration(XmlConfigurationDescriptor descriptor) {
        for (XmlConfigurationDescriptor.ExtensionPointDeclaration extensionPoint : descriptor.getExtensionPoints()) {
            registerExtensionPoint(extensionPoint.name(), extensionPoint.extensionClassName());
        }
        for (XmlConfigurationDescriptor.ExtensionDeclaration extension : descriptor.getExtensions()) {
            registerFrameExtension(extension.pointName(), extension.implementationClass(), extension.id());
        }
        for (AppServiceDeclaration service : descriptor.getApplicationServices()) {
            registerFrameService(service.getServiceInterface(),
                    service.getServiceImplementation(),
                    service.getPreloadMode());
        }
        LOG.info("Applied frame config from {}", descriptor.getSource());
    }

    private void parseExtensionPoints(Element container,
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
            Class<?> extensionClass = Class.forName(className, true, appClassLoader);
            extensionRegistry.registerExtensionPoint(name, extensionClass);
            StartupTraces.getCurrent().incrementCounter("frame.extensionPoints");
            LOG.debug("Loaded frame extension point: {}", name);
        } catch (ClassNotFoundException e) {
            LOG.warn("Frame extension point class not found: {} (point: {})", className, name);
        }
    }

    private void parseFrameExtensions(Element container,
                                      List<XmlConfigurationDescriptor.ExtensionDeclaration> extensions,
                                      List<AppServiceDeclaration> applicationServices) {
        NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            if ("applicationService".equals(node.getNodeName())) {
                parseFrameService((Element) node, applicationServices);
            } else if ("extension".equals(node.getNodeName())) {
                parseFrameExtension((Element) node, extensions);
            }
        }
    }

    private void parseFrameService(Element service, List<AppServiceDeclaration> applicationServices) {
        String iface = service.getAttribute("serviceInterface");
        String impl = service.getAttribute("serviceImplementation");
        if (isPresent(iface) && isPresent(impl)) {
            applicationServices.add(new AppServiceDeclaration(iface, impl,
                    parsePreloadMode(service, iface)));
        }
    }

    private void parseFrameExtension(Element extension,
                                     List<XmlConfigurationDescriptor.ExtensionDeclaration> extensions) {
        String pointName = extension.getAttribute("point");
        String implementationClass = extension.getAttribute("implementation");
        String id = extension.getAttribute("id");
        if (isPresent(pointName) && isPresent(implementationClass)) {
            extensions.add(new XmlConfigurationDescriptor.ExtensionDeclaration(
                    pointName,
                    implementationClass,
                    id));
        }
    }

    private ServicePreloadMode parsePreloadMode(Element serviceElement, String iface) {
        String preload = serviceElement.getAttribute("preload");
        try {
            return ServicePreloadMode.fromXmlAttribute(preload);
        } catch (IllegalArgumentException e) {
            LOG.warn("Unknown frame service preload mode '{}' for {} in {}; using none",
                    preload, iface, FRAME_RESOURCE);
            return ServicePreloadMode.NONE;
        }
    }

    private void registerFrameService(String iface, String impl, ServicePreloadMode preloadMode) {
        try {
            Class<?> ifaceClass = Class.forName(iface, true, appClassLoader);
            registerFrameService(ifaceClass, impl, preloadMode);
            LOG.info("Loaded frame application service: {} -> {}", iface, impl);
        } catch (ClassNotFoundException e) {
            LOG.warn("Frame service interface not found: {} (impl: {})", iface, impl);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void registerFrameService(Class<?> iface,
                                          String implClassName,
                                          ServicePreloadMode preloadMode) {
        serviceContainer.registerService(
                (Class<T>) iface,
                ServiceDescriptor.builder((Class<T>) iface)
                        .implementationClassName(implClassName)
                        .classLoader(appClassLoader)
                        .preload(preloadMode)
                        .build());
        StartupTraces.getCurrent().incrementCounter("frame.services.registered");
    }

    private void registerFrameExtension(String pointName, String implementationClass, String id) {
        ExtensionPoint<?> point = extensionRegistry.getExtensionPoint(pointName);
        if (point == null) {
            LOG.warn("Frame extension point '{}' not found for extension '{}'",
                    pointName, implementationClass);
            return;
        }

        ExtensionDescriptor<?> descriptor = new ExtensionDescriptor<>(
                point.getExtensionClass(),
                implementationClass,
                appClassLoader,
                id);
        registerLazyRaw(point, descriptor);
        StartupTraces.getCurrent().incrementCounter("frame.extensions.lazyRegistered");
        LOG.debug("Loaded frame lazy extension: {} -> {}", pointName, implementationClass);
    }

    @SuppressWarnings("unchecked")
    private <T> void registerLazyRaw(ExtensionPoint<?> point, ExtensionDescriptor<?> descriptor) {
        ((ExtensionPoint<T>) point).registerLazy((ExtensionDescriptor<T>) descriptor);
    }

    private boolean isPresent(String value) {
        return value != null && !value.isEmpty();
    }
}
