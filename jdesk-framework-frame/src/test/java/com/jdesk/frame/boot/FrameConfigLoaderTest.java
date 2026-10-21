package com.jdesk.frame.boot;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.extensions.impl.ExtensionRegistryImpl;
import com.jdesk.core.service.impl.ServiceContainerImpl;
import com.jdesk.core.vfs.ManagedVfsService;
import com.jdesk.frame.action.ActionManager;
import com.jdesk.frame.icon.JdfIconManager;
import com.jdesk.frame.settings.SettingsManager;
import com.jdesk.frame.storage.DatabaseService;
import com.jdesk.frame.swing.SwingUiDispatcher;
import com.jdesk.frame.webview.WebViewService;
import com.jdesk.frame.webview.bridge.WebBridgeService;
import com.jdesk.frame.webview.resource.WebResourceSchemeRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameConfigLoaderTest {

    @TempDir
    Path homeDir;

    /** An unreadable built-in descriptor fails startup at once rather than starting without the frame's services. */
    @Test
    void anUnreadableFrameDescriptorFailsStartupRightAway() {
        ClassLoader broken = new ClassLoader(FrameConfigLoaderTest.class.getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return "META-INF/frame-plugins.xml".equals(name)
                        ? new ByteArrayInputStream("<plugin><extensions>".getBytes(StandardCharsets.UTF_8))
                        : super.getResourceAsStream(name);
            }
        };

        assertThrows(IllegalStateException.class,
                () -> new FrameConfigLoader(null, null, broken).parseFrameConfiguration());
    }

    @AfterEach
    void clearGlobalState() {
        Application app = ApplicationManager.getApplicationIfCreated();
        if (app != null) {
            if (!app.isDisposed()) {
                app.shutdown();
            }
            ApplicationManager.clearApplication(app);
        }
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void frameApplicationLoadsFrameServicesFromFramePluginsXml() {
        Application app = new JdeskApplication(FrameConfigLoaderTest.class.getClassLoader());
        PathManager.setHomePathForTests(homeDir);
        try {
            app.init();

            assertTrue(app.getServiceContainer().hasService(ActionManager.class));
            assertTrue(app.getServiceContainer().hasService(SettingsManager.class));
            assertTrue(app.getServiceContainer().hasService(JdfIconManager.class));
            assertTrue(app.getServiceContainer().hasService(SwingUiDispatcher.class));
            assertTrue(app.getServiceContainer().hasService(DatabaseService.class));
            assertTrue(app.getServiceContainer().hasService(ManagedVfsService.class));
            assertTrue(app.getServiceContainer().hasService(WebViewService.class));
            assertTrue(app.getServiceContainer().hasService(WebResourceSchemeRegistry.class));
            assertTrue(app.getServiceContainer().hasService(WebBridgeService.class));
            assertNull(app.getServiceContainer().getServiceIfCreated(DatabaseService.class));
            assertNull(app.getServiceContainer().getServiceIfCreated(ManagedVfsService.class));
            assertNull(app.getServiceContainer().getServiceIfCreated(WebViewService.class));
            assertNull(app.getServiceContainer().getServiceIfCreated(WebResourceSchemeRegistry.class));
            assertNull(app.getServiceContainer().getServiceIfCreated(WebBridgeService.class));

            assertNotNull(app.getService(ActionManager.class));
            assertNotNull(app.getService(SettingsManager.class));
            assertNotNull(app.getService(JdfIconManager.class));
            assertNotNull(app.getService(SwingUiDispatcher.class));
            assertNull(app.getServiceContainer().getServiceIfCreated(DatabaseService.class));
            assertNull(app.getServiceContainer().getServiceIfCreated(ManagedVfsService.class));
            assertNull(app.getServiceContainer().getServiceIfCreated(WebViewService.class));
            assertNull(app.getServiceContainer().getServiceIfCreated(WebResourceSchemeRegistry.class));
            assertNull(app.getServiceContainer().getServiceIfCreated(WebBridgeService.class));
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
        }
    }

    @Test
    void frameServicePreloadAwaitIsAppliedWhenContainerInitializes() {
        FrameAwaitPreloadServiceImpl.created = 0;
        String xml = """
                <?xml version="1.0"?>
                <frame-plugins>
                  <extensions>
                    <applicationService serviceInterface="%s"
                                        serviceImplementation="%s"
                                        preload="await"/>
                  </extensions>
                </frame-plugins>
                """.formatted(
                FrameAwaitPreloadService.class.getName(),
                FrameAwaitPreloadServiceImpl.class.getName());

        ExtensionRegistryImpl extensionRegistry = new ExtensionRegistryImpl();
        ServiceContainerImpl serviceContainer = new ServiceContainerImpl();
        FrameConfigLoader loader = new FrameConfigLoader(extensionRegistry,
                serviceContainer,
                new InlineFrameConfigClassLoader(
                        FrameConfigLoaderTest.class.getClassLoader(),
                        xml));

        XmlConfigurationDescriptor descriptor = loader.parseFrameConfiguration();
        loader.applyFrameConfiguration(descriptor);

        assertNull(serviceContainer.getServiceIfCreated(FrameAwaitPreloadService.class));

        serviceContainer.initialize();

        assertNotNull(serviceContainer.getServiceIfCreated(FrameAwaitPreloadService.class));
        assertEquals(1, FrameAwaitPreloadServiceImpl.created);
    }

    interface FrameAwaitPreloadService {
    }

    public static final class FrameAwaitPreloadServiceImpl implements FrameAwaitPreloadService {
        static int created;

        public FrameAwaitPreloadServiceImpl() {
            created++;
        }
    }

    private static final class InlineFrameConfigClassLoader extends ClassLoader {
        private final String xml;

        private InlineFrameConfigClassLoader(ClassLoader parent, String xml) {
            super(parent);
            this.xml = xml;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if ("META-INF/frame-plugins.xml".equals(name)) {
                return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            }
            return super.getResourceAsStream(name);
        }
    }
}
