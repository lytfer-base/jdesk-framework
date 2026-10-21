package com.jdesk.frame.webview.jcef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The layout follows JCEF's {@code system/jcef/user-data}, which has no Default level. */
final class JcefGpuCachesTest {

    private static final List<String> GPU_CACHES =
            List.of("GrShaderCache", "ShaderCache", "GraphiteDawnCache", "GPUCache", "Code Cache");

    @Test
    void clearsGpuCachesButKeepsDraftsAndOtherCaches(@TempDir Path cachePath) throws IOException {
        for (String name : GPU_CACHES) {
            Path nested = Files.createDirectories(cachePath.resolve(name).resolve("js"));
            Files.writeString(nested.resolve("data_0"), "cache");
        }
        Path drafts = Files.createDirectories(cachePath.resolve("Local Storage").resolve("leveldb"));
        Files.writeString(drafts.resolve("000003.log"), "emergency draft");
        Files.createDirectories(cachePath.resolve("Cache"));
        Files.createDirectories(cachePath.resolve("DawnCache"));

        JcefGpuCaches.clear(cachePath);

        for (String name : GPU_CACHES) {
            assertFalse(Files.exists(cachePath.resolve(name)), name + " should be cleared");
        }
        assertTrue(Files.exists(drafts.resolve("000003.log")), "Local Storage holds emergency drafts and must stay");
        assertTrue(Files.isDirectory(cachePath.resolve("Cache")), "the HTTP cache isn't on the list");
        assertTrue(Files.isDirectory(cachePath.resolve("DawnCache")), "DawnCache isn't on the list");
    }
}
