package com.jdesk.core.plugins;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.app.TestApplication;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.extensions.ExtensionPoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class PluginManagerImplJarPluginTest {

    private static final String PLUGIN_ID = "test.jar.plugin";
    private static final String GREETING_POINT = "jarplugin.greeting";
    private static final String GREETING_CLASS = "jarplugin.JarGreeting";
    private static final String GREETING_IMPL_CLASS = "jarplugin.JarGreetingImpl";
    private static final String SERVICE_CLASS = "jarplugin.JarPluginService";
    private static final String SERVICE_IMPL_CLASS = "jarplugin.JarPluginServiceImpl";

    @TempDir
    Path homeDir;

    @TempDir
    Path buildDir;

    @AfterEach
    void clearState() {
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void discoversJarPluginAndReadsDescriptorFromMetaInf() throws Exception {
        writeJarPlugin();

        Application app = new TestApplication();
        PathManager.setHomePathForTests(homeDir);

        try {
            app.init();
            app.start();

            PluginManager pluginManager = app.getService(PluginManager.class);
            assertEquals(PluginState.STARTED, pluginManager.getPluginState(PLUGIN_ID));

            PluginClassLoader classLoader = pluginManager.getPluginClassLoader(PLUGIN_ID);
            assertNotNull(classLoader);

            Class<?> serviceInterface = classLoader.loadClass(SERVICE_CLASS);
            assertTrue(app.getServiceContainer().hasService(serviceInterface));

            Object preloadedService = getServiceIfCreated(app, serviceInterface);
            assertNotNull(preloadedService);
            assertEquals("jar-service", serviceInterface.getMethod("value").invoke(preloadedService));

            Object service = getService(app, serviceInterface);
            assertSame(preloadedService, service);
            assertEquals("jar-service", serviceInterface.getMethod("value").invoke(service));

            ExtensionPoint<?> greetingPoint = app.getExtensionRegistry().getExtensionPoint(GREETING_POINT);
            assertNotNull(greetingPoint);
            assertEquals(GREETING_CLASS, greetingPoint.getExtensionClass().getName());
            assertSame(classLoader, greetingPoint.getExtensionClass().getClassLoader());

            assertEquals(1, greetingPoint.getExtensionCount());
            Object greeting = greetingPoint.getExtensions().get(0);
            assertEquals("hello from jar extension",
                    greetingPoint.getExtensionClass().getMethod("message").invoke(greeting));
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
        }
    }

    @Test
    void unloadJarPluginUnregistersPluginDeclaredExtensionPoint() throws Exception {
        writeJarPlugin();

        Application app = new TestApplication();
        PathManager.setHomePathForTests(homeDir);

        try {
            app.init();
            app.start();

            PluginManager pluginManager = app.getService(PluginManager.class);
            PluginClassLoader classLoader = pluginManager.getPluginClassLoader(PLUGIN_ID);
            assertNotNull(classLoader);

            Class<?> serviceInterface = classLoader.loadClass(SERVICE_CLASS);
            assertNotNull(getService(app, serviceInterface));

            ExtensionPoint<?> greetingPoint = app.getExtensionRegistry().getExtensionPoint(GREETING_POINT);
            assertNotNull(greetingPoint);
            assertEquals(1, greetingPoint.getExtensions().size());

            pluginManager.unloadPlugin(PLUGIN_ID);

            assertEquals(PluginState.UNLOADED, pluginManager.getPluginState(PLUGIN_ID));
            assertNull(pluginManager.getPluginClassLoader(PLUGIN_ID));
            assertTrue(classLoader.isDisposed());
            assertFalse(app.getServiceContainer().hasService(serviceInterface));
            assertNull(app.getExtensionRegistry().getExtensionPoint(GREETING_POINT));
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object getService(Application app, Class<?> serviceInterface) {
        return app.getServiceContainer().getService((Class) serviceInterface);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object getServiceIfCreated(Application app, Class<?> serviceInterface) {
        return app.getServiceContainer().getServiceIfCreated((Class) serviceInterface);
    }

    private void writeJarPlugin() throws Exception {
        Path sourcesDir = Files.createDirectories(buildDir.resolve("src").resolve("jarplugin"));
        Path classesDir = Files.createDirectories(buildDir.resolve("classes"));

        Path serviceSource = sourcesDir.resolve("JarPluginService.java");
        Files.writeString(serviceSource, """
                package jarplugin;
                
                public interface JarPluginService {
                    String value();
                }
                """);

        Path greetingSource = sourcesDir.resolve("JarGreeting.java");
        Files.writeString(greetingSource, """
                package jarplugin;
                
                public interface JarGreeting {
                    String message();
                }
                """);

        Path greetingImplSource = sourcesDir.resolve("JarGreetingImpl.java");
        Files.writeString(greetingImplSource, """
                package jarplugin;
                
                public class JarGreetingImpl implements JarGreeting {
                    @Override
                    public String message() {
                        return "hello from jar extension";
                    }
                }
                """);

        Path implSource = sourcesDir.resolve("JarPluginServiceImpl.java");
        Files.writeString(implSource, """
                package jarplugin;
                
                public class JarPluginServiceImpl implements JarPluginService {
                    @Override
                    public String value() {
                        return "jar-service";
                    }
                }
                """);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests must run on a JDK with javax.tools.JavaCompiler available");
        int result = compiler.run(null, null, null,
                "-d", classesDir.toString(),
                serviceSource.toString(),
                greetingSource.toString(),
                greetingImplSource.toString(),
                implSource.toString());
        assertEquals(0, result);

        Path pluginJar = Files.createDirectories(pluginsDir()).resolve("jar-plugin.jar");
        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(pluginJar))) {
            addPluginXml(jar);
            addClassFiles(classesDir, jar);
        }
    }

    private Path pluginsDir() {
        return homeDir.resolve("plugins");
    }

    private static void addPluginXml(JarOutputStream jar) throws Exception {
        JarEntry entry = new JarEntry("META-INF/plugin.xml");
        jar.putNextEntry(entry);
        jar.write("""
                <?xml version="1.0"?>
                <deskframe-plugin>
                  <id>%s</id>
                  <name>Jar Plugin</name>
                  <version>1.0.0</version>
                  <extensionPoints>
                    <extensionPoint name="%s" interface="%s"/>
                  </extensionPoints>
                  <extensions>
                    <extension point="%s" implementation="%s"/>
                    <applicationService serviceInterface="%s"
                                        serviceImplementation="%s"
                                        preload="await"/>
                  </extensions>
                </deskframe-plugin>
                """.formatted(
                PLUGIN_ID,
                GREETING_POINT, GREETING_CLASS,
                GREETING_POINT, GREETING_IMPL_CLASS,
                SERVICE_CLASS, SERVICE_IMPL_CLASS).getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    private static void addClassFiles(Path classesDir, JarOutputStream jar) throws Exception {
        try (Stream<Path> files = Files.walk(classesDir)) {
            for (Path classFile : files.filter(Files::isRegularFile).toList()) {
                String entryName = classesDir.relativize(classFile).toString().replace('\\', '/');
                jar.putNextEntry(new JarEntry(entryName));
                Files.copy(classFile, jar);
                jar.closeEntry();
            }
        }
    }
}
