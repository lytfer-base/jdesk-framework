package com.jdesk.core.plugins;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.app.TestApplication;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.runtime.params.CoreRuntimeParams;
import com.jdesk.core.startup.StartupTrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PluginManagerImplBundledRegistryTest {

    @TempDir
    Path homeDir;

    @AfterEach
    void clearState() {
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void bundledRegistryPathDiscoversNestedPluginBeforeScannedPlugins() throws Exception {
        enableStartupTraceReport();
        writePlugin("external-plugin", "test.external.plugin");
        writePlugin("bundled/nested-plugin", "test.bundled.plugin");
        Path pluginsDir = pluginsDir();
        Files.writeString(pluginsDir.resolve("bundled-plugins.xml"), """
                <?xml version="1.0"?>
                <bundled-plugins>
                  <plugin id="test.bundled.plugin" path="bundled/nested-plugin"/>
                </bundled-plugins>
                """);

        Application app = new TestApplication(StartupTrace.report());

        try {
            app.init();
            app.start();

            PluginManager pluginManager = app.getService(PluginManager.class);

            PluginDescriptor bundled = pluginManager.getPlugin("test.bundled.plugin");
            assertNotNull(bundled);
            assertTrue(bundled.isBundled());
            assertEquals(
                    pluginsDir.resolve("bundled").resolve("nested-plugin").toAbsolutePath().normalize(),
                    bundled.getPluginPath().toAbsolutePath().normalize());

            PluginDescriptor external = pluginManager.getPlugin("test.external.plugin");
            assertNotNull(external);
            assertFalse(external.isBundled());

            List<String> pluginIds = pluginManager.getAllPlugins().stream()
                    .map(PluginDescriptor::getPluginId)
                    .toList();
            assertEquals(List.of("test.bundled.plugin", "test.external.plugin"), pluginIds);

            StartupTrace trace = app.getService(StartupTrace.class);
            assertEquals(3L, trace.getCounters().get("plugins.paths.discovered"));
            assertEquals(2L, trace.getCounters().get("plugins.descriptors.loaded"));
            assertEquals(1L, trace.getCounters().get("plugins.descriptors.empty"));
            assertTrue(trace.getEvents().stream()
                    .anyMatch(event -> event.getName().startsWith("plugin.descriptor.parse:")));
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
        }
    }

    private void enableStartupTraceReport() throws Exception {
        Path systemDir = homeDir.resolve("system");
        Files.createDirectories(systemDir);
        Files.writeString(systemDir.resolve("application.properties"),
                CoreRuntimeParams.STARTUP_TRACE_MODE_KEY + "=report\n");
        PathManager.setHomePathForTests(homeDir);
    }

    @Test
    void idOnlyBundledRegistryEntryStillMarksScannedPluginBundled() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        writePlugin("legacy-plugin", "test.legacy.bundled");
        Path pluginsDir = pluginsDir();
        Files.writeString(pluginsDir.resolve("bundled-plugins.xml"), """
                <?xml version="1.0"?>
                <bundled-plugins>
                  <plugin id="test.legacy.bundled"/>
                </bundled-plugins>
                """);

        Application app = new TestApplication();

        try {
            app.init();
            app.start();

            PluginDescriptor plugin = app.getService(PluginManager.class)
                    .getPlugin("test.legacy.bundled");

            assertNotNull(plugin);
            assertTrue(plugin.isBundled());
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
        }
    }

    private void writePlugin(String directoryName, String pluginId) throws Exception {
        Path metaInf = pluginsDir().resolve(directoryName).resolve("META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("plugin.xml"), """
                <?xml version="1.0"?>
                <deskframe-plugin>
                  <id>%s</id>
                  <name>%s</name>
                  <version>1.0.0</version>
                </deskframe-plugin>
                """.formatted(pluginId, pluginId));
    }

    private Path pluginsDir() {
        return homeDir.resolve("plugins");
    }
}
