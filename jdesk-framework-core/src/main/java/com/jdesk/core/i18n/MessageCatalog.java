package com.jdesk.core.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads UTF-8 properties messages by root directory and namespace; a namespace belongs to the first root that has its
 * source-language file.
 */
public final class MessageCatalog {
    private static final Logger LOG = LoggerFactory.getLogger(MessageCatalog.class);
    private final List<Root> roots = new CopyOnWriteArrayList<>();
    private final Map<String, Optional<Root>> owners = new ConcurrentHashMap<>();
    private final Map<BundleKey, Map<String, String>> bundles = new ConcurrentHashMap<>();

    public synchronized void registerRoot(String resourceDir, ClassLoader loader, UiLanguage source) {
        Root root = new Root(Objects.requireNonNull(resourceDir, "resourceDir"),
                Objects.requireNonNull(loader, "loader"), Objects.requireNonNull(source, "source"));
        if (!roots.contains(root)) {
            roots.add(root);
            owners.clear();
        }
    }

    /**
     * Returns the raw message in the target language, else English, else the source language, or null; pseudo text
     * is derived from English.
     */
    public String find(String key, UiLanguage language) {
        int dot = key == null ? -1 : key.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        String namespace = key.substring(0, dot);
        Root root = owners.computeIfAbsent(namespace, this::ownerOf).orElse(null);
        if (root == null) {
            return null;
        }
        if (language.isPseudo()) {
            String english = firstFound(root, namespace, key, List.of(UiLanguage.EN, root.source()));
            return english == null ? null : PseudoLocalizer.pseudo(english);
        }
        Set<UiLanguage> chain = new LinkedHashSet<>(List.of(language, UiLanguage.EN, root.source()));
        return firstFound(root, namespace, key, List.copyOf(chain));
    }

    public synchronized void clear() {
        roots.clear();
        owners.clear();
        bundles.clear();
    }

    private String firstFound(Root root, String namespace, String key, List<UiLanguage> chain) {
        for (UiLanguage candidate : chain) {
            String value = bundle(root, namespace, candidate).get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Optional<Root> ownerOf(String namespace) {
        for (Root root : roots) {
            if (root.loader().getResource(path(root, namespace, root.source())) != null) {
                return Optional.of(root);
            }
        }
        return Optional.empty();
    }

    private Map<String, String> bundle(Root root, String namespace, UiLanguage language) {
        String path = path(root, namespace, language);
        return bundles.computeIfAbsent(new BundleKey(root.loader(), path), ignored -> load(root.loader(), path));
    }

    private static String path(Root root, String namespace, UiLanguage language) {
        return root.dir() + "/" + namespace + "_" + language.fileSuffix() + ".properties";
    }

    // Treat an unreadable or malformed file as empty and cache that, so lookup falls back; don't throw.
    private static Map<String, String> load(ClassLoader loader, String path) {
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                return Map.of();
            }
            Properties properties = new Properties();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            Map<String, String> values = new HashMap<>();
            properties.forEach((k, v) -> values.put((String) k, (String) v));
            return Map.copyOf(values);
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("i18n.catalog.unreadable path={}", path, e);
            return Map.of();
        }
    }

    private record Root(String dir, ClassLoader loader, UiLanguage source) {
    }

    private record BundleKey(ClassLoader loader, String path) {
    }
}
