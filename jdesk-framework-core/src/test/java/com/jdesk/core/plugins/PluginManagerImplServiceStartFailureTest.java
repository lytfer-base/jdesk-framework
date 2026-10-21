package com.jdesk.core.plugins;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.app.TestApplication;
import com.jdesk.core.disposer.Disposer;

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

/**
 * Isolation of plugin startup failures: when a service registration throws or a plugin class fails to initialize, only
 * the broken plugin or extension point fails and releases its resources, its dependents fail in cascade, and the other
 * plugins start normally.
 */
public class PluginManagerImplServiceStartFailureTest {

    private static final String PROVIDER_PLUGIN_ID = "test.startfailure.provider";
    private static final String DEPENDENT_PLUGIN_ID = "test.startfailure.dependent";
    private static final String HEALTHY_PLUGIN_ID = "test.startfailure.healthy";

    private static final String PROVIDER_POINT = "test.startfailure.point";
    private static final String DEPENDENT_POINT = "test.startfailure.dependent.point";
    private static final String HEALTHY_POINT = "test.startfailure.healthy.point";

    private static final String BROKEN_POINT_PLUGIN_ID = "test.linkage.brokenpoint";
    private static final String BROKEN_SERVICE_PLUGIN_ID = "test.linkage.brokenservice";
    private static final String LINKAGE_HEALTHY_PLUGIN_ID = "test.linkage.healthy";

    private static final String BROKEN_POINT = "test.linkage.broken.point";
    private static final String LINKAGE_HEALTHY_POINT = "test.linkage.healthy.point";

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
    void serviceStartFailureFailsPluginReleasesResourcesAndStartsUnrelatedPlugins() throws Exception {
        writePlugins();

        Application app = new TestApplication();
        PathManager.setHomePathForTests(homeDir);

        try {
            app.init();

            PluginManager pluginManager = app.getService(PluginManager.class);
            PluginClassLoader providerLoader = pluginManager.getPluginClassLoader(PROVIDER_PLUGIN_ID);
            PluginClassLoader dependentLoader = pluginManager.getPluginClassLoader(DEPENDENT_PLUGIN_ID);
            assertNotNull(providerLoader);
            assertNotNull(dependentLoader);

            // Load the classes before the class loader is released; the container checks below need them.
            Class<?> goodServiceInterface = providerLoader.loadClass("sfail.SFailService");
            Class<?> brokenServiceInterface = providerLoader.loadClass("sfail.SFailBrokenService");
            Class<?> goodServiceImplClass = providerLoader.loadClass("sfail.SFailServiceImpl");
            Class<?> healthyServiceInterface = pluginManager
                    .getPluginClassLoader(HEALTHY_PLUGIN_ID).loadClass("sfail2.HealthyService");

            // The exception doesn't propagate: startup completes.
            app.start();
            assertEquals(Application.State.RUNNING, app.getState());

            // The failed plugin is FAILED, not stuck in LOADED, and its class loader and extension points are released.
            assertEquals(PluginState.FAILED, pluginManager.getPluginState(PROVIDER_PLUGIN_ID));
            assertNull(pluginManager.getPluginClassLoader(PROVIDER_PLUGIN_ID));
            assertTrue(providerLoader.isDisposed());
            assertNull(app.getExtensionRegistry().getExtensionPoint(PROVIDER_POINT));

            // Services registered by this start are rolled back, both the preloaded one and the one that threw.
            assertFalse(app.getServiceContainer().hasService(goodServiceInterface));
            assertFalse(app.getServiceContainer().hasService(brokenServiceInterface));
            // The rollback disposes the preloaded service instance.
            assertEquals(1, goodServiceImplClass.getField("DISPOSE_COUNT").getInt(null));

            // The dependent fails in cascade and releases its resources too.
            assertEquals(PluginState.FAILED, pluginManager.getPluginState(DEPENDENT_PLUGIN_ID));
            assertNull(pluginManager.getPluginClassLoader(DEPENDENT_PLUGIN_ID));
            assertTrue(dependentLoader.isDisposed());
            assertNull(app.getExtensionRegistry().getExtensionPoint(DEPENDENT_POINT));

            // The unrelated plugin starts normally and its preload=AWAIT service is available.
            assertEquals(PluginState.STARTED, pluginManager.getPluginState(HEALTHY_PLUGIN_ID));
            assertTrue(app.getServiceContainer().hasService(healthyServiceInterface));
            assertNotNull(app.getServiceContainer().getServiceIfCreated(
                    (Class) healthyServiceInterface));
            assertNotNull(app.getExtensionRegistry().getExtensionPoint(HEALTHY_POINT));
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
        }
    }

    /** A class init Error (here a throwing static initializer) affects only the broken extension point or plugin. */
    @Test
    void pluginClassInitErrorsDoNotAbortStartup() throws Exception {
        writeClassInitErrorPlugins();

        Application app = new TestApplication();
        PathManager.setHomePathForTests(homeDir);

        try {
            app.init();
            app.start();
            assertEquals(Application.State.RUNNING, app.getState());

            PluginManager pluginManager = app.getService(PluginManager.class);
            // An extension point interface that fails to initialize is skipped; the plugin itself starts.
            assertNull(app.getExtensionRegistry().getExtensionPoint(BROKEN_POINT));
            assertEquals(PluginState.STARTED, pluginManager.getPluginState(BROKEN_POINT_PLUGIN_ID));
            // A preloaded service implementation that fails to initialize makes the plugin FAILED and frees its loader.
            assertEquals(PluginState.FAILED, pluginManager.getPluginState(BROKEN_SERVICE_PLUGIN_ID));
            assertNull(pluginManager.getPluginClassLoader(BROKEN_SERVICE_PLUGIN_ID));
            // The unrelated plugin starts normally.
            assertEquals(PluginState.STARTED, pluginManager.getPluginState(LINKAGE_HEALTHY_PLUGIN_ID));
            assertNotNull(app.getExtensionRegistry().getExtensionPoint(LINKAGE_HEALTHY_POINT));
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
        }
    }

    private void writePlugins() throws Exception {
        Path sourcesDir = Files.createDirectories(buildDir.resolve("src"));
        Path classesDir = Files.createDirectories(buildDir.resolve("classes"));

        writeSource(sourcesDir, "sfail/SFailService.java", """
                package sfail;

                public interface SFailService {
                    String value();
                }
                """);
        writeSource(sourcesDir, "sfail/SFailServiceImpl.java", """
                package sfail;

                import com.jdesk.core.disposer.Disposable;

                public class SFailServiceImpl implements SFailService, Disposable {
                    public static volatile int DISPOSE_COUNT;

                    @Override
                    public String value() {
                        return "good-service";
                    }

                    @Override
                    public void dispose() {
                        DISPOSE_COUNT++;
                    }
                }
                """);
        writeSource(sourcesDir, "sfail/SFailBrokenService.java", """
                package sfail;

                public interface SFailBrokenService {
                    String value();
                }
                """);
        writeSource(sourcesDir, "sfail/SFailBrokenServiceImpl.java", """
                package sfail;

                public class SFailBrokenServiceImpl implements SFailBrokenService {
                    public SFailBrokenServiceImpl() {
                        throw new IllegalStateException("boom: broken service constructor");
                    }

                    @Override
                    public String value() {
                        return "never";
                    }
                }
                """);
        writeSource(sourcesDir, "sfail/SFailRunnable.java", """
                package sfail;

                public class SFailRunnable implements Runnable {
                    @Override
                    public void run() {
                    }
                }
                """);
        writeSource(sourcesDir, "sfail2/HealthyService.java", """
                package sfail2;

                public interface HealthyService {
                    String value();
                }
                """);
        writeSource(sourcesDir, "sfail2/HealthyServiceImpl.java", """
                package sfail2;

                public class HealthyServiceImpl implements HealthyService {
                    @Override
                    public String value() {
                        return "healthy";
                    }
                }
                """);

        compile(sourcesDir, classesDir);

        writeJar("provider-plugin.jar", """
                <?xml version="1.0"?>
                <deskframe-plugin>
                  <id>%s</id>
                  <name>Broken Service Provider</name>
                  <version>1.0.0</version>
                  <extensionPoints>
                    <extensionPoint name="%s" interface="java.lang.Runnable"/>
                  </extensionPoints>
                  <extensions>
                    <extension point="%s" implementation="sfail.SFailRunnable"/>
                    <applicationService serviceInterface="sfail.SFailService"
                                        serviceImplementation="sfail.SFailServiceImpl"
                                        preload="await"/>
                    <applicationService serviceInterface="sfail.SFailBrokenService"
                                        serviceImplementation="sfail.SFailBrokenServiceImpl"
                                        preload="await"/>
                  </extensions>
                </deskframe-plugin>
                """.formatted(PROVIDER_PLUGIN_ID, PROVIDER_POINT, PROVIDER_POINT),
                classesDir, "sfail/");

        writeJar("dependent-plugin.jar", """
                <?xml version="1.0"?>
                <deskframe-plugin>
                  <id>%s</id>
                  <name>Dependent</name>
                  <version>1.0.0</version>
                  <depends>%s</depends>
                  <extensionPoints>
                    <extensionPoint name="%s" interface="java.lang.Runnable"/>
                  </extensionPoints>
                </deskframe-plugin>
                """.formatted(DEPENDENT_PLUGIN_ID, PROVIDER_PLUGIN_ID, DEPENDENT_POINT),
                classesDir, null);

        writeJar("healthy-plugin.jar", """
                <?xml version="1.0"?>
                <deskframe-plugin>
                  <id>%s</id>
                  <name>Healthy</name>
                  <version>1.0.0</version>
                  <extensionPoints>
                    <extensionPoint name="%s" interface="java.lang.Runnable"/>
                  </extensionPoints>
                  <extensions>
                    <applicationService serviceInterface="sfail2.HealthyService"
                                        serviceImplementation="sfail2.HealthyServiceImpl"
                                        preload="await"/>
                  </extensions>
                </deskframe-plugin>
                """.formatted(HEALTHY_PLUGIN_ID, HEALTHY_POINT),
                classesDir, "sfail2/");
    }

    private void writeClassInitErrorPlugins() throws Exception {
        Path sourcesDir = Files.createDirectories(buildDir.resolve("src"));
        Path classesDir = Files.createDirectories(buildDir.resolve("classes"));

        writeSource(sourcesDir, "lnkpoint/Boom.java", """
                package lnkpoint;

                public final class Boom {
                    public static String fail() {
                        throw new IllegalStateException("boom: extension point class init");
                    }
                }
                """);
        writeSource(sourcesDir, "lnkpoint/BrokenPoint.java", """
                package lnkpoint;

                public interface BrokenPoint {
                    String VALUE = Boom.fail();
                }
                """);
        writeSource(sourcesDir, "lnksvc/BrokenInitService.java", """
                package lnksvc;

                public interface BrokenInitService {
                    String value();
                }
                """);
        writeSource(sourcesDir, "lnksvc/BrokenInitServiceImpl.java", """
                package lnksvc;

                public class BrokenInitServiceImpl implements BrokenInitService {
                    private static final String VALUE = fail();

                    private static String fail() {
                        throw new IllegalStateException("boom: service class init");
                    }

                    @Override
                    public String value() {
                        return VALUE;
                    }
                }
                """);
        compile(sourcesDir, classesDir);

        writeJar("broken-point-plugin.jar", """
                <?xml version="1.0"?>
                <deskframe-plugin>
                  <id>%s</id>
                  <name>Broken Extension Point</name>
                  <version>1.0.0</version>
                  <extensionPoints>
                    <extensionPoint name="%s" interface="lnkpoint.BrokenPoint"/>
                  </extensionPoints>
                </deskframe-plugin>
                """.formatted(BROKEN_POINT_PLUGIN_ID, BROKEN_POINT),
                classesDir, "lnkpoint/");

        writeJar("broken-service-plugin.jar", """
                <?xml version="1.0"?>
                <deskframe-plugin>
                  <id>%s</id>
                  <name>Broken Service Class Init</name>
                  <version>1.0.0</version>
                  <extensions>
                    <applicationService serviceInterface="lnksvc.BrokenInitService"
                                        serviceImplementation="lnksvc.BrokenInitServiceImpl"
                                        preload="await"/>
                  </extensions>
                </deskframe-plugin>
                """.formatted(BROKEN_SERVICE_PLUGIN_ID),
                classesDir, "lnksvc/");

        writeJar("linkage-healthy-plugin.jar", """
                <?xml version="1.0"?>
                <deskframe-plugin>
                  <id>%s</id>
                  <name>Healthy</name>
                  <version>1.0.0</version>
                  <extensionPoints>
                    <extensionPoint name="%s" interface="java.lang.Runnable"/>
                  </extensionPoints>
                </deskframe-plugin>
                """.formatted(LINKAGE_HEALTHY_PLUGIN_ID, LINKAGE_HEALTHY_POINT),
                classesDir, null);
    }

    private static void compile(Path sourcesDir, Path classesDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests must run on a JDK with javax.tools.JavaCompiler available");
        try (Stream<Path> sources = Files.walk(sourcesDir)) {
            String[] sourcePaths = sources.filter(Files::isRegularFile)
                    .map(Path::toString)
                    .toArray(String[]::new);
            String[] compilerArgs = new String[2 + sourcePaths.length];
            compilerArgs[0] = "-d";
            compilerArgs[1] = classesDir.toString();
            System.arraycopy(sourcePaths, 0, compilerArgs, 2, sourcePaths.length);
            int result = compiler.run(null, null, null, compilerArgs);
            assertEquals(0, result);
        }
    }

    private static void writeSource(Path sourcesDir, String relativePath, String content)
            throws Exception {
        Path source = sourcesDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private void writeJar(String jarName, String pluginXml, Path classesDir, String packagePrefix)
            throws Exception {
        Path pluginJar = Files.createDirectories(pluginsDir()).resolve(jarName);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(pluginJar))) {
            JarEntry xmlEntry = new JarEntry("META-INF/plugin.xml");
            jar.putNextEntry(xmlEntry);
            jar.write(pluginXml.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();

            // A null packagePrefix packs only the descriptor, for a plugin without classes.
            if (packagePrefix == null) {
                return;
            }
            String prefix = packagePrefix.replace('\\', '/');
            try (Stream<Path> files = Files.walk(classesDir)) {
                for (Path classFile : files.filter(Files::isRegularFile).toList()) {
                    String entryName = classesDir.relativize(classFile).toString().replace('\\', '/');
                    if (!entryName.startsWith(prefix)) {
                        continue;
                    }
                    jar.putNextEntry(new JarEntry(entryName));
                    Files.copy(classFile, jar);
                    jar.closeEntry();
                }
            }
        }
    }

    private Path pluginsDir() {
        return homeDir.resolve("plugins");
    }
}
