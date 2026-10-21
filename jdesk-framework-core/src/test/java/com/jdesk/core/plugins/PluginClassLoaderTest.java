package com.jdesk.core.plugins;

import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.core.runtime.RuntimeParamSource;
import com.jdesk.core.runtime.params.CoreRuntimeParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PluginClassLoaderTest {

    private static final String PROBE_SIMPLE_NAME = "ProbeApi";

    @TempDir
    Path tempDir;

    @AfterEach
    void resetRuntimeParams() {
        RuntimeParamRegistry.reset();
    }

    @Test
    void framePackageClassesAreLoadedFromParentClassLoader() throws Exception {
        RuntimeParamRegistry.setStartupValue(
                CoreRuntimeParams.PLUGIN_PARENT_FIRST_PACKAGES_KEY,
                "com.jdesk.frame.",
                RuntimeParamSource.PROGRAMMATIC);

        assertProbeLoadedFrom("com.jdesk.frame.test", "parent");
    }

    /** With no extra prefixes, core packages are parent-first: the host's class wins over the plugin's own copy. */
    @ParameterizedTest
    @ValueSource(strings = {
            "com.jdesk.core.disposer",
            "com.jdesk.core.history",
            "com.jdesk.core.io",
            "com.jdesk.core.path",
            "com.jdesk.core.runtime",
            "com.jdesk.core.startup"})
    void corePackagesAreParentFirstWithoutConfiguration(String corePackage) throws Exception {
        assertProbeLoadedFrom(corePackage, "parent");
    }

    /** Libraries used by core's public API (Kotlin stdlib, coroutines, SLF4J) are parent-first as well. */
    @ParameterizedTest
    @ValueSource(strings = {"kotlin", "kotlinx.coroutines", "org.slf4j"})
    void hostLibraryPackagesAreParentFirst(String libraryPackage) throws Exception {
        assertProbeLoadedFrom(libraryPackage, "parent");
    }

    /**
     * Classes outside the framework packages stay plugin-first, including plugin packages under {@code com.jdesk.}
     * and kotlinx libraries the host doesn't ship.
     */
    @ParameterizedTest
    @ValueSource(strings = {"com.example", "com.jdesk.demo.plugin", "kotlinx.serialization"})
    void nonFrameworkPackagesStayPluginFirst(String pluginPackage) throws Exception {
        assertProbeLoadedFrom(pluginPackage, "plugin");
    }

    /** Compiles a probe class into parent and plugin directories and checks which copy the plugin loader returns. */
    private void assertProbeLoadedFrom(String packageName, String expectedOrigin) throws Exception {
        Path workDir = tempDir.resolve(packageName);
        Path parentClasses = compileProbe(packageName, "parent", workDir.resolve("parent-classes"));
        Path pluginClasses = compileProbe(packageName, "plugin", workDir.resolve("plugin-classes"));

        try (URLClassLoader parentClassLoader = new URLClassLoader(
                new URL[]{parentClasses.toUri().toURL()},
                PluginClassLoaderTest.class.getClassLoader())) {
            PluginDescriptor descriptor = PluginDescriptor.builder()
                    .pluginId("test.probe.api")
                    .name("Probe API Test")
                    .pluginPath(pluginClasses)
                    .addClassPathEntry(pluginClasses)
                    .build();
            PluginClassLoader pluginClassLoader = new PluginClassLoader(
                    new URL[]{pluginClasses.toUri().toURL()},
                    parentClassLoader,
                    descriptor,
                    List.of());
            try {
                Class<?> probe = pluginClassLoader.loadClass(packageName + "." + PROBE_SIMPLE_NAME);
                Object instance = probe.getConstructor().newInstance();

                ClassLoader expectedLoader = "parent".equals(expectedOrigin) ? parentClassLoader : pluginClassLoader;
                assertSame(expectedLoader, probe.getClassLoader(), packageName);
                assertEquals(expectedOrigin, probe.getMethod("origin").invoke(instance), packageName);
            } finally {
                pluginClassLoader.dispose();
            }
        }
    }

    private static Path compileProbe(String packageName, String origin, Path outputDir) throws Exception {
        Path sourceDir = outputDir.resolve("src").resolve(packageName.replace('.', '/'));
        Files.createDirectories(sourceDir);
        Files.createDirectories(outputDir);
        Path sourceFile = sourceDir.resolve(PROBE_SIMPLE_NAME + ".java");
        Files.writeString(sourceFile, """
                package %s;

                public class %s {
                    public String origin() {
                        return "%s";
                    }
                }
                """.formatted(packageName, PROBE_SIMPLE_NAME, origin));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(null, null, null,
                "-d", outputDir.toString(),
                sourceFile.toString());
        if (result != 0) {
            throw new IllegalStateException("Compilation failed: " + sourceFile);
        }
        return outputDir;
    }
}
