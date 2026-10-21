package com.jdesk.core.app;

import com.jdesk.util.xml.SecureXmlParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.util.Objects;

/**
 * Loads {@link ApplicationInfo} from an XML descriptor on the class path. A missing resource gives the defaults and
 * malformed XML throws {@link IllegalArgumentException}.
 * Parse only through {@link SecureXmlParser}; don't switch to an XML parser with default settings.
 */
public final class ApplicationInfoLoader {

    private ApplicationInfoLoader() {
    }

    /** Loads from {@value ApplicationInfo#DEFAULT_RESOURCE}; a missing resource gives the defaults. */
    public static ApplicationInfo load(ClassLoader classLoader) {
        return load(classLoader, ApplicationInfo.DEFAULT_RESOURCE);
    }

    /**
     * Loads from the class path resource {@code resourcePath}, or from the default one if it is blank; leading slashes
     * are dropped and backslashes become slashes. A missing resource gives the defaults, and malformed XML throws
     * {@link IllegalArgumentException}.
     */
    public static ApplicationInfo load(ClassLoader classLoader, String resourcePath) {
        Objects.requireNonNull(classLoader, "classLoader");
        String normalizedResource = normalizeResourcePath(resourcePath);
        try (InputStream in = classLoader.getResourceAsStream(normalizedResource)) {
            if (in == null) {
                return loadDefault();
            }
            Document document = SecureXmlParser.parse(in, normalizedResource);
            return parse(document.getDocumentElement());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to close " + normalizedResource, e);
        }
    }

    /** Returns the info used when there is no descriptor, built from the {@link ApplicationInfo.Builder} defaults. */
    public static ApplicationInfo loadDefault() {
        return ApplicationInfo.builder().build();
    }

    /**
     * Converts the root element of a descriptor. Every child element ({@code names}, {@code version}, {@code company},
     * {@code icon}, {@code description}) is optional, without {@code names} the name attributes are read from the
     * root, and missing values keep the {@link ApplicationInfo.Builder} defaults.
     */
    public static ApplicationInfo parse(Element root) {
        Objects.requireNonNull(root, "root");
        ApplicationInfo.Builder builder = ApplicationInfo.builder();
        setIfPresent(firstAttribute(root, "id", "appId", "applicationId"), builder::id);

        Element names = firstChild(root, "names");
        if (names != null) {
            setIfPresent(firstAttribute(names, "product", "name"), builder::productName);
            setIfPresent(firstAttribute(names, "fullName", "fullname", "full"), builder::fullProductName);
        } else {
            setIfPresent(firstAttribute(root, "product", "name"), builder::productName);
            setIfPresent(firstAttribute(root, "fullName", "fullname", "full"), builder::fullProductName);
        }

        Element version = firstChild(root, "version");
        if (version != null) {
            builder.versionName(firstAttribute(version, "name"));
            builder.majorVersion(firstAttribute(version, "major"));
            builder.minorVersion(firstAttribute(version, "minor"));
            builder.microVersion(firstAttribute(version, "micro"));
            builder.patchVersion(firstAttribute(version, "patch"));
            builder.versionSuffix(firstAttribute(version, "suffix"));
            builder.buildNumber(firstAttribute(version, "build", "number"));
            builder.buildDate(firstAttribute(version, "date", "build-date", "buildDate"));
        }

        Element company = firstChild(root, "company");
        if (company != null) {
            builder.companyName(firstAttribute(company, "name"));
            builder.companyUrl(firstAttribute(company, "url"));
        }

        Element icon = firstChild(root, "icon");
        if (icon != null) {
            builder.iconSvgPath(firstAttribute(icon, "svg"));
            builder.smallIconSvgPath(firstAttribute(icon, "svg-small", "small-svg", "smallSvg"));
            builder.iconPngPath(firstAttribute(icon, "png"));
            builder.smallIconPngPath(firstAttribute(icon, "png-small", "small-png", "smallPng"));
        }

        Element description = firstChild(root, "description");
        if (description != null) {
            builder.description(description.getTextContent());
        }

        return builder.build();
    }

    private static Element firstChild(Element root, String tagName) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && tagName.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static String firstAttribute(Element element, String... names) {
        for (String name : names) {
            if (element.hasAttribute(name)) {
                return element.getAttribute(name);
            }
        }
        return "";
    }

    private static void setIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    /** {@link ClassLoader#getResourceAsStream} rejects a leading slash, so the result never starts with one. */
    private static String normalizeResourcePath(String path) {
        String normalized = path == null || path.isBlank()
                ? ApplicationInfo.DEFAULT_RESOURCE
                : path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
