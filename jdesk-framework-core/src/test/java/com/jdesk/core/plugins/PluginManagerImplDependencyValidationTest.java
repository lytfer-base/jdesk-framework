package com.jdesk.core.plugins;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.app.TestApplication;
import com.jdesk.core.disposer.Disposer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class PluginManagerImplDependencyValidationTest {

    @TempDir
    Path homeDir;

    @AfterEach
    void clearState() {
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void pluginWithMissingDependencyIsMarkedFailedAndNotInitialized() throws Exception {
        writePlugin("valid-plugin", "test.valid.plugin", "", "test.valid.point");
        writePlugin("missing-dependency-plugin", "test.missing.dependency",
                "<depends>test.plugin.not.present</depends>", "test.missing.point");

        Application app = new TestApplication();
        PathManager.setHomePathForTests(homeDir);

        try {
            app.init();
            app.start();

            PluginManager pluginManager = app.getService(PluginManager.class);

            assertEquals(PluginState.STARTED, pluginManager.getPluginState("test.valid.plugin"));
            assertNotNull(pluginManager.getPluginClassLoader("test.valid.plugin"));
            assertNotNull(app.getExtensionRegistry().getExtensionPoint("test.valid.point"));

            assertEquals(PluginState.FAILED, pluginManager.getPluginState("test.missing.dependency"));
            assertNull(pluginManager.getPluginClassLoader("test.missing.dependency"));
            assertNull(app.getExtensionRegistry().getExtensionPoint("test.missing.point"));
            assertFalse(pluginManager.isPluginStarted("test.missing.dependency"));
            assertFalse(pluginManager.isPluginLoaded("test.missing.dependency"));
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
        }
    }

    @Test
    void circularDependencyMarksAllPluginsInCycleFailed() throws Exception {
        writePlugin("cycle-a", "test.cycle.a",
                "<depends>test.cycle.b</depends>", "test.cycle.a.point");
        writePlugin("cycle-b", "test.cycle.b",
                "<depends>test.cycle.a</depends>", "test.cycle.b.point");

        Application app = new TestApplication();
        PathManager.setHomePathForTests(homeDir);

        try {
            app.init();
            app.start();

            PluginManager pluginManager = app.getService(PluginManager.class);

            assertEquals(PluginState.FAILED, pluginManager.getPluginState("test.cycle.a"));
            assertEquals(PluginState.FAILED, pluginManager.getPluginState("test.cycle.b"));
            assertNull(pluginManager.getPluginClassLoader("test.cycle.a"));
            assertNull(pluginManager.getPluginClassLoader("test.cycle.b"));
            assertNull(app.getExtensionRegistry().getExtensionPoint("test.cycle.a.point"));
            assertNull(app.getExtensionRegistry().getExtensionPoint("test.cycle.b.point"));
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
        }
    }

    @Test
    void unloadingPluginRequiredByLoadedDependentIsRejected() throws Exception {
        writePlugin("dependency-base", "test.dependency.base",
                "", "test.dependency.base.point");
        writePlugin("dependency-consumer", "test.dependency.consumer",
                "<depends>test.dependency.base</depends>", "test.dependency.consumer.point");

        Application app = new TestApplication();
        PathManager.setHomePathForTests(homeDir);

        try {
            app.init();
            app.start();

            PluginManager pluginManager = app.getService(PluginManager.class);
            PluginClassLoader baseClassLoader = pluginManager.getPluginClassLoader("test.dependency.base");
            PluginClassLoader consumerClassLoader = pluginManager.getPluginClassLoader("test.dependency.consumer");

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> pluginManager.unloadPlugin("test.dependency.base"));

            assertTrue(error.getMessage().contains("test.dependency.base"));
            assertTrue(error.getMessage().contains("test.dependency.consumer"));
            assertEquals(PluginState.STARTED, pluginManager.getPluginState("test.dependency.base"));
            assertEquals(PluginState.STARTED, pluginManager.getPluginState("test.dependency.consumer"));
            assertSame(baseClassLoader, pluginManager.getPluginClassLoader("test.dependency.base"));
            assertSame(consumerClassLoader, pluginManager.getPluginClassLoader("test.dependency.consumer"));
            assertFalse(baseClassLoader.isDisposed());
            assertFalse(consumerClassLoader.isDisposed());
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
        }
    }

    @Test
    void pluginXmlWithDoctypeIsRejected() throws Exception {
        Path pluginsDir = Files.createDirectories(pluginsDir());
        Path secret = pluginsDir.resolve("secret.txt");
        Files.writeString(secret, "leaked-plugin-id");

        Path metaInf = pluginsDir.resolve("xxe-plugin").resolve("META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("plugin.xml"), """
                <?xml version="1.0"?>
                <!DOCTYPE deskframe-plugin [
                  <!ENTITY xxe SYSTEM "%s">
                ]>
                <deskframe-plugin>
                  <id>&xxe;</id>
                  <name>XXE Plugin</name>
                </deskframe-plugin>
                """.formatted(secret.toUri()));

        Application app = new TestApplication();
        PathManager.setHomePathForTests(homeDir);

        try {
            app.init();
            app.start();

            PluginManager pluginManager = app.getService(PluginManager.class);

            assertNull(pluginManager.getPlugin("leaked-plugin-id"));
            assertTrue(pluginManager.getAllPlugins().isEmpty());
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
        }
    }

    private void writePlugin(String directoryName, String pluginId,
                             String dependenciesXml, String extensionPointName) throws Exception {
        Path metaInf = pluginsDir().resolve(directoryName).resolve("META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("plugin.xml"), """
                <?xml version="1.0"?>
                <deskframe-plugin>
                  <id>%s</id>
                  <name>%s</name>
                  <version>1.0.0</version>
                  %s
                  <extensionPoints>
                    <extensionPoint name="%s" interface="java.lang.Runnable"/>
                  </extensionPoints>
                </deskframe-plugin>
                """.formatted(pluginId, pluginId, dependenciesXml, extensionPointName));
    }

    private Path pluginsDir() {
        return homeDir.resolve("plugins");
    }
}
