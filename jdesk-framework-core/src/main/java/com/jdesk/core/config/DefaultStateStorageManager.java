package com.jdesk.core.config;

import com.jdesk.core.app.PathManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-level state storage manager: expands {@link StoragePathMacros} and reuses one {@link StateStorage} per
 * normalized path.
 */
final class DefaultStateStorageManager implements StateStorageManager {

    private final Path configDir;
    private final Path cacheDir;
    private final ObjectMapper xmlMapper;
    private final Map<String, StateStorage> storages = new ConcurrentHashMap<>();

    DefaultStateStorageManager(Path configDir, ObjectMapper xmlMapper) {
        this.configDir = configDir;
        this.cacheDir = PathManager.getSystemDir().resolve("cache");
        this.xmlMapper = xmlMapper;
    }

    @Override
    public StateStorage getStateStorage(String pathSpec) {
        Path path = expandMacro(pathSpec).normalize();
        return storages.computeIfAbsent(path.toString(), ignored -> new FileStateStorage(path, xmlMapper));
    }

    @Override
    public Path expandMacro(String pathSpec) {
        if (pathSpec == null || pathSpec.isBlank()) {
            throw new IllegalArgumentException("Storage path must not be blank");
        }
        if (pathSpec.equals(StoragePathMacros.APP_CONFIG)) {
            return configDir;
        }
        if (pathSpec.startsWith(StoragePathMacros.APP_CONFIG + "/")) {
            return configDir.resolve(pathSpec.substring(StoragePathMacros.APP_CONFIG.length() + 1));
        }
        if (pathSpec.equals(StoragePathMacros.CACHE)) {
            return cacheDir;
        }
        if (pathSpec.startsWith(StoragePathMacros.CACHE + "/")) {
            return cacheDir.resolve(pathSpec.substring(StoragePathMacros.CACHE.length() + 1));
        }

        Path path = Path.of(pathSpec);
        return path.isAbsolute() ? path : configDir.resolve(path);
    }

    @Override
    public String collapseMacro(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path normalizedConfig = configDir.toAbsolutePath().normalize();
        if (normalized.startsWith(normalizedConfig)) {
            return StoragePathMacros.APP_CONFIG + "/" + normalizedConfig.relativize(normalized);
        }

        Path normalizedCache = cacheDir.toAbsolutePath().normalize();
        if (normalized.startsWith(normalizedCache)) {
            return StoragePathMacros.CACHE + "/" + normalizedCache.relativize(normalized);
        }
        return normalized.toString();
    }

    @Override
    public Path getConfigDir() {
        return configDir;
    }
}
