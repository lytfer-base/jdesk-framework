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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class PluginManagerImplApplicationClassLoaderTest {

    private static final String PLUGIN_ID = "test.app.parent.plugin";
    private static final String APP_POINT = "test.app.greeting";
    private static final String APP_CONTRACT =
            "com.jdesk.core.plugins.PluginManagerImplApplicationClassLoaderTest$AppGreeting";
    private static final String PLUGIN_IMPL = "appplugin.AppGreetingImpl";

    @TempDir
    Path homeDir;

    @TempDir
    Path pluginBuildDir;

    @AfterEach
    void clearState() {
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void pluginParentIsApplicationClassLoaderAndCanContributeToApplicationExtensionPoint()
            throws Exception {
        writePluginJar();

        try (URLClassLoader applicationLoader = new URLClassLoader(
                new URL[0],
                getClass().getClassLoader())) {
            Application app = new TestApplication(applicationLoader);
            PathManager.setHomePathForTests(homeDir);

            try {
                app.init();
                app.getExtensionRegistry().registerExtensionPoint(APP_POINT, AppGreeting.class);
                app.start();

                PluginManager pluginManager = app.getService(PluginManager.class);
                PluginClassLoader pluginLoader = pluginManager.getPluginClassLoader(PLUGIN_ID);
                assertNotNull(pluginLoader);
                assertSame(applicationLoader, pluginLoader.getParent());

                ExtensionPoint<?> point = app.getExtensionRegistry().getExtensionPoint(APP_POINT);
                assertNotNull(point);
                assertEquals(APP_CONTRACT, point.getExtensionClass().getName());

                assertEquals(1, point.getExtensionCount());
                Object greeting = point.getExtensions().get(0);
                assertEquals("hello from application extension",
                        point.getExtensionClass().getMethod("message").invoke(greeting));
            } finally {
                if (!app.isDisposed()) {
                    app.shutdown();
                }
            }
        }
    }

    private void writePluginJar() throws Exception {
        Path sourceDir = Files.createDirectories(pluginBuildDir.resolve("src").resolve("appplugin"));
        Path classesDir = Files.createDirectories(pluginBuildDir.resolve("classes"));

        Path implSource = sourceDir.resolve("AppGreetingImpl.java");
        Files.writeString(implSource, """
                package appplugin;

                import com.jdesk.core.plugins.PluginManagerImplApplicationClassLoaderTest.AppGreeting;

                public class AppGreetingImpl implements AppGreeting {
                    @Override
                    public String message() {
                        return "hello from application extension";
                    }
                }
                """);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests must run on a JDK with javax.tools.JavaCompiler available");
        int result = compiler.run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString(),
                implSource.toString());
        assertEquals(0, result);

        Path pluginJar = Files.createDirectories(pluginsDir()).resolve("app-parent-plugin.jar");
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
        jar.putNextEntry(new JarEntry("META-INF/plugin.xml"));
        jar.write("""
                <?xml version="1.0"?>
                <deskframe-plugin>
                  <id>%s</id>
                  <name>Application Parent Plugin</name>
                  <version>1.0.0</version>
                  <extensions>
                    <extension point="%s" implementation="%s"/>
                  </extensions>
                </deskframe-plugin>
                """.formatted(PLUGIN_ID, APP_POINT, PLUGIN_IMPL)
                .getBytes(StandardCharsets.UTF_8));
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

    public interface AppGreeting {
        String message();
    }
}
