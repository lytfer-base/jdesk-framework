package com.jdesk.core.plugins;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.app.TestApplication;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class PluginManagerImplServiceUnloadTest {

    private static final String PLUGIN_ID = "test.plugin.services";

    @TempDir
    Path homeDir;

    @AfterEach
    void clearState() {
        TestPluginServiceImpl.disposeCount.set(0);
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void stopPluginUnregistersAndDisposesApplicationServices() throws Exception {
        writePluginXml();

        Application app = new TestApplication();
        PathManager.setHomePathForTests(homeDir);

        try {
            app.init();
            app.start();

            PluginManager pluginManager = app.getService(PluginManager.class);
            assertEquals(PluginState.STARTED, pluginManager.getPluginState(PLUGIN_ID));
            assertTrue(app.getServiceContainer().hasService(TestPluginService.class));

            TestPluginService service = app.getService(TestPluginService.class);
            assertEquals("plugin-service", service.value());

            pluginManager.stopPlugin(PLUGIN_ID);

            assertEquals(PluginState.STOPPED, pluginManager.getPluginState(PLUGIN_ID));
            assertFalse(app.getServiceContainer().hasService(TestPluginService.class));
            assertNull(app.getServiceContainer().getServiceIfCreated(TestPluginService.class));
            assertEquals(1, TestPluginServiceImpl.disposeCount.get());
            assertThrows(IllegalStateException.class,
                    () -> app.getService(TestPluginService.class));
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
        }
    }

    private void writePluginXml() throws Exception {
        Path metaInf = pluginsDir().resolve("service-plugin").resolve("META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("plugin.xml"), """
                <?xml version="1.0"?>
                <deskframe-plugin>
                  <id>%s</id>
                  <name>Service Plugin</name>
                  <version>1.0.0</version>
                  <extensions>
                    <applicationService serviceInterface="%s"
                                        serviceImplementation="%s"/>
                  </extensions>
                </deskframe-plugin>
                """.formatted(
                PLUGIN_ID,
                TestPluginService.class.getName(),
                TestPluginServiceImpl.class.getName()));
    }

    private Path pluginsDir() {
        return homeDir.resolve("plugins");
    }

    public interface TestPluginService {
        String value();
    }

    public static final class TestPluginServiceImpl implements TestPluginService, Disposable {
        private static final AtomicInteger disposeCount = new AtomicInteger();

        public TestPluginServiceImpl() {
        }

        @Override
        public String value() {
            return "plugin-service";
        }

        @Override
        public void dispose() {
            disposeCount.incrementAndGet();
        }
    }
}
