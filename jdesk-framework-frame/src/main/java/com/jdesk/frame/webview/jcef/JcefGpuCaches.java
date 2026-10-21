package com.jdesk.frame.webview.jcef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Clears the GPU and shader disk caches before CefApp starts: after a quick restart a new GPU process can hang on
 * leftovers, leaving pages blank and windows frozen. The JCEF profile is cache_path itself, with no Default folder.
 * Clear pure caches only: never add Local Storage, which holds page data. Keep the list in step with other backends.
 */
final class JcefGpuCaches {

    private static final Logger LOG = LoggerFactory.getLogger(JcefGpuCaches.class);

    private static final List<String> DIRS = List.of(
            "GrShaderCache",
            "ShaderCache",
            "GraphiteDawnCache",
            "GPUCache",
            "Code Cache");

    private JcefGpuCaches() {
    }

    /**
     * Deletes the cache folders under {@code cachePath}, skipping files it can't delete, without blocking startup.
     * Call it only before this process creates CefApp.
     */
    static void clear(Path cachePath) {
        for (String name : DIRS) {
            Path dir = cachePath.resolve(name);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try {
                deleteRecursively(dir);
                LOG.info("jcef.gpuCache.cleared dir={}", dir);
            } catch (IOException failure) {
                LOG.warn("jcef.gpuCache.clear.failed dir={} error={}", dir, failure.toString());
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Skip cache files in use; startup goes on.
                }
            });
        }
    }
}
