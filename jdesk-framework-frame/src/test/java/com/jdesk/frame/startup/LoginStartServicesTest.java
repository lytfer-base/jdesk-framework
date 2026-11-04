package com.jdesk.frame.startup;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jdesk.core.app.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginStartServicesTest {

    private static final String SERVICES_FILE = "META-INF/services/com.jdesk.frame.startup.LoginStartServiceProvider";
    private static final String UNLINKABLE_PROVIDER = "com.jdesk.frame.startup.UnlinkableProvider";
    private static final Application NO_APPLICATION = null;
    private static final List<String> ASKED = new ArrayList<>();
    private static Application lastApplication;

    @TempDir
    Path dir;
    private final List<URLClassLoader> loaders = new ArrayList<>();

    @BeforeEach
    void forgetEarlierRuns() {
        ASKED.clear();
        lastApplication = null;
    }

    @AfterEach
    void closeLoaders() throws IOException {
        for (URLClassLoader loader : loaders) {
            loader.close();
        }
    }

    @Test
    void providerReturningServiceIsUsed() throws IOException {
        Lookup lookup = lookup(loaderWith(FakeProvider.class));

        assertTrue(lookup.service().isPresent());
        assertInstanceOf(FakeService.class, lookup.service().get());
        assertEquals(List.of("loginStart.service.selected provider=" + FakeProvider.class.getName()), lookup.messages(Level.INFO));
    }

    @Test
    void providerReturningEmptyMeansNoLoginStart() throws IOException {
        Lookup lookup = lookup(loaderWith(EmptyProvider.class));

        assertTrue(lookup.service().isEmpty());
        assertEquals(List.of("loginStart.service.none"), lookup.messages(Level.INFO));
    }

    @Test
    void noProviderMeansNoLoginStart() throws IOException {
        Lookup lookup = lookup(emptyLoader());

        assertTrue(lookup.service().isEmpty());
        assertEquals(List.of("loginStart.service.none"), lookup.messages(Level.INFO));
        assertTrue(lookup.messages(Level.WARN).isEmpty());
    }

    @Test
    void firstProviderThatGivesAServiceWins() throws IOException {
        Lookup lookup = lookup(loaderWith(EmptyProvider.class, FakeProvider.class, SecondProvider.class));

        assertInstanceOf(FakeService.class, lookup.service().orElseThrow());
        assertEquals(List.of("EmptyProvider", "FakeProvider"), ASKED);
    }

    @Test
    void providerIsGivenTheApplication() throws IOException {
        Application application = applicationWith(null);

        LoginStartServices.create(application, loaderWith(FakeProvider.class));

        assertSame(application, lastApplication);
    }

    @Test
    void providerWhoseConstructorThrowsIsSkipped() throws IOException {
        Lookup lookup = lookup(loaderWith(ConstructorThrowsProvider.class, FakeProvider.class));

        assertInstanceOf(FakeService.class, lookup.service().orElseThrow());
        assertEquals(1, lookup.events(Level.WARN).size());
        assertEquals(List.of("loginStart.service.selected provider=" + FakeProvider.class.getName()), lookup.messages(Level.INFO));
    }

    @Test
    void providerWhoseCreateThrowsIsSkipped() throws IOException {
        Lookup lookup = lookup(loaderWith(CreateThrowsProvider.class, FakeProvider.class));

        assertInstanceOf(FakeService.class, lookup.service().orElseThrow());
        assertEquals(1, lookup.events(Level.WARN).size());
        assertEquals(List.of("CreateThrowsProvider", "FakeProvider"), ASKED);
    }

    @Test
    void providerReturningNullIsSkipped() throws IOException {
        Lookup lookup = lookup(loaderWith(NullProvider.class, FakeProvider.class));

        assertInstanceOf(FakeService.class, lookup.service().orElseThrow());
        assertEquals(1, lookup.events(Level.WARN).size());
    }

    @Test
    void unknownProviderClassIsSkipped() throws IOException {
        Lookup lookup = lookup(loaderWithNames("com.jdesk.frame.startup.NoSuchProvider", FakeProvider.class.getName()));

        assertInstanceOf(FakeService.class, lookup.service().orElseThrow());
        assertEquals(1, lookup.events(Level.WARN).size());
    }

    @Test
    void onlyFailingProvidersMeanNoLoginStart() throws IOException {
        Lookup lookup = lookup(loaderWith(ConstructorThrowsProvider.class, CreateThrowsProvider.class));

        assertTrue(lookup.service().isEmpty());
        assertEquals(2, lookup.events(Level.WARN).size());
        assertEquals(List.of("loginStart.service.none"), lookup.messages(Level.INFO));
    }

    @Test
    void providerClassThatCannotBeLinkedIsSkipped() throws IOException {
        Lookup lookup = lookup(loaderWithUnlinkable(UNLINKABLE_PROVIDER, UNLINKABLE_PROVIDER, FakeProvider.class.getName()));

        assertInstanceOf(FakeService.class, lookup.service().orElseThrow());
        assertEquals(1, lookup.events(Level.WARN).size());
        assertEquals(List.of("loginStart.service.selected provider=" + FakeProvider.class.getName()), lookup.messages(Level.INFO));
    }

    @Test
    void providerWhoseCreateFailsToLinkIsSkipped() throws IOException {
        Lookup lookup = lookup(loaderWith(LinkageErrorProvider.class, FakeProvider.class));

        assertInstanceOf(FakeService.class, lookup.service().orElseThrow());
        assertEquals(1, lookup.events(Level.WARN).size());
        assertEquals(List.of("LinkageErrorProvider", "FakeProvider"), ASKED);
    }

    @Test
    void unreadableProviderConfigurationEndsTheLookup() {
        Lookup lookup = lookupThroughFailingConfiguration(() -> {
            throw new IOException("unreadable");
        });

        assertTrue(lookup.service().isEmpty());
        assertEquals(List.of("loginStart.service.none"), lookup.messages(Level.INFO));
    }

    @Test
    void persistentLinkageErrorEndsTheLookup() {
        Lookup lookup = lookupThroughFailingConfiguration(() -> {
            throw new NoClassDefFoundError("missing dependency");
        });

        assertTrue(lookup.service().isEmpty());
        assertEquals(List.of("loginStart.service.none"), lookup.messages(Level.INFO));
    }

    @Test
    void publicLookupUsesTheApplicationClassLoader() throws IOException {
        Application application = applicationWith(loaderWith(FakeProvider.class));

        assertInstanceOf(FakeService.class, LoginStartServices.create(application).orElseThrow());
    }

    @Test
    void publicLookupToleratesAMissingApplication() {
        assertTrue(LoginStartServices.create(null).isEmpty());
    }

    private Lookup lookup(ClassLoader loader) {
        Logger logger = (Logger) LoggerFactory.getLogger(LoginStartServices.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Level previous = logger.getLevel();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
        try {
            return new Lookup(LoginStartServices.create(NO_APPLICATION, loader), logs.list);
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(logs);
        }
    }

    private ClassLoader loaderWith(Class<?>... providers) throws IOException {
        return loaderWithNames(Arrays.stream(providers).map(Class::getName).toArray(String[]::new));
    }

    private ClassLoader loaderWithNames(String... classNames) throws IOException {
        return newLoader(servicesRoot(classNames));
    }

    private ClassLoader loaderWithUnlinkable(String unlinkable, String... classNames) throws IOException {
        return newLoader(servicesRoot(classNames), unlinkable);
    }

    private Path servicesRoot(String... classNames) throws IOException {
        Path root = Files.createTempDirectory(dir, "loader");
        Path file = root.resolve(SERVICES_FILE);
        Files.createDirectories(file.getParent());
        Files.write(file, Arrays.asList(classNames));
        return root;
    }

    private ClassLoader emptyLoader() throws IOException {
        return newLoader(Files.createTempDirectory(dir, "empty"));
    }

    private ClassLoader newLoader(Path root, String... unlinkable) throws IOException {
        Set<String> rejected = Set.of(unlinkable);
        URLClassLoader loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (rejected.contains(name)) {
                    throw new NoClassDefFoundError("a type that " + name + " depends on is missing");
                }
                return super.loadClass(name, resolve);
            }
        };
        loaders.add(loader);
        return loader;
    }

    private Lookup lookupThroughFailingConfiguration(Failure failure) {
        AtomicInteger attempts = new AtomicInteger();
        URLClassLoader failing = new URLClassLoader(new URL[0], getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (attempts.incrementAndGet() > 100) {
                    throw new AssertionError("the lookup keeps retrying a configuration it cannot read");
                }
                failure.raise();
                return super.getResources(name);
            }
        };
        loaders.add(failing);
        return lookup(failing);
    }

    private interface Failure {
        void raise() throws IOException;
    }

    private static Application applicationWith(ClassLoader loader) {
        return (Application) Proxy.newProxyInstance(Application.class.getClassLoader(), new Class<?>[]{Application.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getApplicationClassLoader" -> loader;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "stub application";
                    default -> null;
                });
    }

    private record Lookup(Optional<LoginStartService> service, List<ILoggingEvent> logs) {

        List<ILoggingEvent> events(Level level) {
            return logs.stream().filter(event -> event.getLevel() == level).toList();
        }

        List<String> messages(Level level) {
            return events(level).stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    public static final class FakeProvider implements LoginStartServiceProvider {
        @Override
        public Optional<LoginStartService> create(Application application) {
            ASKED.add("FakeProvider");
            lastApplication = application;
            return Optional.of(new FakeService());
        }
    }

    public static final class SecondProvider implements LoginStartServiceProvider {
        @Override
        public Optional<LoginStartService> create(Application application) {
            ASKED.add("SecondProvider");
            return Optional.of(new FakeService());
        }
    }

    public static final class EmptyProvider implements LoginStartServiceProvider {
        @Override
        public Optional<LoginStartService> create(Application application) {
            ASKED.add("EmptyProvider");
            return Optional.empty();
        }
    }

    public static final class ConstructorThrowsProvider implements LoginStartServiceProvider {
        public ConstructorThrowsProvider() {
            throw new IllegalStateException("constructor failed");
        }

        @Override
        public Optional<LoginStartService> create(Application application) {
            return Optional.empty();
        }
    }

    public static final class CreateThrowsProvider implements LoginStartServiceProvider {
        @Override
        public Optional<LoginStartService> create(Application application) {
            ASKED.add("CreateThrowsProvider");
            throw new IllegalStateException("create failed");
        }
    }

    public static final class LinkageErrorProvider implements LoginStartServiceProvider {
        @Override
        public Optional<LoginStartService> create(Application application) {
            ASKED.add("LinkageErrorProvider");
            throw new NoClassDefFoundError("missing dependency");
        }
    }

    public static final class NullProvider implements LoginStartServiceProvider {
        @Override
        public Optional<LoginStartService> create(Application application) {
            return null;
        }
    }

    private static final class FakeService implements LoginStartService {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public boolean setEnabled(boolean enabled) {
            return enabled;
        }

        @Override
        public void dispose() {
        }
    }
}
