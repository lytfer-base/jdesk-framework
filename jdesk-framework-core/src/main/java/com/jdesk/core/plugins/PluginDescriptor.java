package com.jdesk.core.plugins;

import com.jdesk.core.service.AppServiceDeclaration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plugin descriptor parsed from the plugin's {@code META-INF/plugin.xml}: ID, name, version, vendor, dependencies,
 * declared extension points, contributed extensions and application services, and class path.
 */
public class PluginDescriptor {

    private final String pluginId;
    private final String name;
    private final String version;
    private final String description;
    private final String vendor;
    private final String vendorEmail;
    private final String vendorUrl;

    private final List<String> dependencies;

    private final List<ExtensionPointDeclaration> extensionPoints;

    private final List<ExtensionDeclaration> extensions;

    private final List<AppServiceDeclaration> appServices;

    /** The plugin folder, or the JAR of a single-JAR plugin. */
    private final Path pluginPath;

    private final List<Path> classPath;

    /** Whether the plugin ships with the application. */
    private volatile boolean bundled;

    // ---- Construction ----

    private PluginDescriptor(Builder builder) {
        this.pluginId = builder.pluginId;
        this.name = builder.name;
        this.version = builder.version;
        this.description = builder.description;
        this.vendor = builder.vendor;
        this.vendorEmail = builder.vendorEmail;
        this.vendorUrl = builder.vendorUrl;
        this.dependencies = Collections.unmodifiableList(new ArrayList<>(builder.dependencies));
        this.extensionPoints = Collections.unmodifiableList(new ArrayList<>(builder.extensionPoints));
        this.extensions = Collections.unmodifiableList(new ArrayList<>(builder.extensions));
        this.appServices = Collections.unmodifiableList(new ArrayList<>(builder.appServices));
        this.pluginPath = builder.pluginPath;
        this.classPath = Collections.unmodifiableList(new ArrayList<>(builder.classPath));
        this.bundled = builder.bundled;
    }

    // ---- Accessors ----

    public String getPluginId() { return pluginId; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getDescription() { return description; }
    public String getVendor() { return vendor; }
    public String getVendorEmail() { return vendorEmail; }
    public String getVendorUrl() { return vendorUrl; }
    public List<String> getDependencies() { return dependencies; }
    public List<ExtensionPointDeclaration> getExtensionPoints() { return extensionPoints; }
    public List<ExtensionDeclaration> getExtensions() { return extensions; }
    public List<AppServiceDeclaration> getAppServices() { return appServices; }
    public Path getPluginPath() { return pluginPath; }
    public List<Path> getClassPath() { return classPath; }
    public boolean isBundled() { return bundled; }

    /** Marks the plugin as bundled; set after parsing, from its match in bundled-plugins.xml. */
    public void setBundled(boolean bundled) {
        this.bundled = bundled;
    }

    @Override
    public String toString() {
        return "PluginDescriptor{id=" + pluginId + ", name=" + name + ", version=" + version + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PluginDescriptor that)) return false;
        return pluginId.equals(that.pluginId);
    }

    @Override
    public int hashCode() {
        return pluginId.hashCode();
    }

    // ---- Nested types ----

    /** An extension point a plugin declares; its type is an interface or a bean class that extensions must match. */
    public static class ExtensionPointDeclaration {
        private final String name;
        private final String extensionClassName;

        public ExtensionPointDeclaration(String name, String extensionClassName) {
            this.name = name;
            this.extensionClassName = extensionClassName;
        }

        public String getName() { return name; }
        public String getExtensionClassName() { return extensionClassName; }

        @Override
        public String toString() {
            return "EP{name=" + name + ", class=" + extensionClassName + "}";
        }
    }

    /** An extension a plugin contributes to an extension point. */
    public static class ExtensionDeclaration {
        private final String pointName;
        private final String implementationClass;
        private final String id;

        public ExtensionDeclaration(String pointName, String implementationClass, String id) {
            this.pointName = pointName;
            this.implementationClass = implementationClass;
            this.id = id;
        }

        public String getPointName() { return pointName; }
        public String getImplementationClass() { return implementationClass; }
        public String getId() { return id; }

        @Override
        public String toString() {
            return "Ext{point=" + pointName + ", impl=" + implementationClass + "}";
        }
    }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String pluginId;
        private String name;
        private String version = "0.0.0";
        private String description;
        private String vendor;
        private String vendorEmail;
        private String vendorUrl;
        private final List<String> dependencies = new ArrayList<>();
        private final List<ExtensionPointDeclaration> extensionPoints = new ArrayList<>();
        private final List<ExtensionDeclaration> extensions = new ArrayList<>();
        private final List<AppServiceDeclaration> appServices = new ArrayList<>();
        private Path pluginPath;
        private final List<Path> classPath = new ArrayList<>();
        private boolean bundled;

        public Builder pluginId(String id) { this.pluginId = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder vendor(String vendor) { this.vendor = vendor; return this; }
        public Builder vendorEmail(String email) { this.vendorEmail = email; return this; }
        public Builder vendorUrl(String url) { this.vendorUrl = url; return this; }
        public Builder addDependency(String pluginId) { dependencies.add(pluginId); return this; }
        public Builder addExtensionPoint(ExtensionPointDeclaration ep) { extensionPoints.add(ep); return this; }
        public Builder addExtension(ExtensionDeclaration ext) { extensions.add(ext); return this; }
        public Builder addAppService(AppServiceDeclaration svc) { appServices.add(svc); return this; }
        public Builder pluginPath(Path path) { this.pluginPath = path; return this; }
        public Builder addClassPathEntry(Path path) { classPath.add(path); return this; }
        public Builder bundled(boolean bundled) { this.bundled = bundled; return this; }

        public PluginDescriptor build() {
            if (pluginId == null || pluginId.isEmpty()) throw new IllegalStateException("pluginId is required");
            if (name == null || name.isEmpty()) throw new IllegalStateException("name is required");
            return new PluginDescriptor(this);
        }
    }
}
