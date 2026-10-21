package com.jdesk.frame.boot;

import com.jdesk.core.extensions.impl.ExtensionRegistryImpl;
import com.jdesk.core.service.impl.ServiceContainerImpl;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApplicationConfigLoaderTest {

    @Test
    void parsingApplicationConfigurationDoesNotNeedRegisteredExtensionPointsUntilApply() {
        String pointName = "test.application.point";
        String xml = """
                <?xml version="1.0"?>
                <app-plugins>
                  <extensionPoints>
                    <extensionPoint name="%s"
                                    interface="%s"/>
                  </extensionPoints>
                  <extensions>
                    <extension point="%s"
                               implementation="%s"
                               id="fixture"/>
                  </extensions>
                </app-plugins>
                """.formatted(
                pointName,
                TestApplicationExtension.class.getName(),
                pointName,
                TestApplicationExtensionImpl.class.getName());

        ExtensionRegistryImpl extensionRegistry = new ExtensionRegistryImpl();
        ServiceContainerImpl serviceContainer = new ServiceContainerImpl();
        ApplicationConfigLoader loader = new ApplicationConfigLoader(extensionRegistry,
                serviceContainer,
                new InlineApplicationConfigClassLoader(
                        ApplicationConfigLoaderTest.class.getClassLoader(),
                        xml));

        XmlConfigurationDescriptor descriptor = loader.parseApplicationConfiguration();

        assertEquals(1, descriptor.getExtensionPoints().size());
        assertEquals(1, descriptor.getExtensions().size());
        assertNull(extensionRegistry.getExtensionPoint(pointName));

        loader.applyApplicationConfiguration(descriptor);

        assertNotNull(extensionRegistry.getExtensionPoint(pointName));
        List<TestApplicationExtension> extensions = extensionRegistry.getExtensions(pointName);
        assertEquals(1, extensions.size());
        assertEquals("fixture", extensions.get(0).value());
    }

    @Test
    void applicationServicePreloadAwaitIsAppliedWhenContainerInitializes() {
        AwaitPreloadServiceImpl.created = 0;
        String xml = """
                <?xml version="1.0"?>
                <app-plugins>
                  <extensions>
                    <applicationService serviceInterface="%s"
                                        serviceImplementation="%s"
                                        preload="await"/>
                  </extensions>
                </app-plugins>
                """.formatted(
                AwaitPreloadService.class.getName(),
                AwaitPreloadServiceImpl.class.getName());

        ExtensionRegistryImpl extensionRegistry = new ExtensionRegistryImpl();
        ServiceContainerImpl serviceContainer = new ServiceContainerImpl();
        ApplicationConfigLoader loader = new ApplicationConfigLoader(extensionRegistry,
                serviceContainer,
                new InlineApplicationConfigClassLoader(
                        ApplicationConfigLoaderTest.class.getClassLoader(),
                        xml));

        XmlConfigurationDescriptor descriptor = loader.parseApplicationConfiguration();
        loader.applyApplicationConfiguration(descriptor);

        assertNull(serviceContainer.getServiceIfCreated(AwaitPreloadService.class));

        serviceContainer.initialize();

        assertNotNull(serviceContainer.getServiceIfCreated(AwaitPreloadService.class));
        assertEquals(1, AwaitPreloadServiceImpl.created);
    }

    interface TestApplicationExtension {
        String value();
    }

    public static final class TestApplicationExtensionImpl implements TestApplicationExtension {
        @Override
        public String value() {
            return "fixture";
        }
    }

    interface AwaitPreloadService {
    }

    public static final class AwaitPreloadServiceImpl implements AwaitPreloadService {
        static int created;

        public AwaitPreloadServiceImpl() {
            created++;
        }
    }

    private static final class InlineApplicationConfigClassLoader extends ClassLoader {
        private final String xml;

        private InlineApplicationConfigClassLoader(ClassLoader parent, String xml) {
            super(parent);
            this.xml = xml;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if ("META-INF/app-plugins.xml".equals(name)) {
                return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            }
            return super.getResourceAsStream(name);
        }
    }
}
