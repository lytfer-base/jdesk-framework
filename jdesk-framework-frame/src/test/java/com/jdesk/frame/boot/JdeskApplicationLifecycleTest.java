package com.jdesk.frame.boot;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationInfo;
import com.jdesk.core.app.ApplicationTask;
import com.jdesk.core.app.ApplicationTaskStatus;
import com.jdesk.core.app.ApplicationLifecycleListener;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.message.MessageBus;
import com.jdesk.core.message.MessageBusKey;
import com.jdesk.core.message.MessageBusManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JdeskApplicationLifecycleTest {

    @TempDir
    Path homeDir;

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
    void shutdownDisposesLazilyCreatedMessageBusEvenWithoutConnections() {
        JdeskApplication app = new JdeskApplication();
        PathManager.setHomePathForTests(homeDir);
        app.init();
        MessageBus bus = app.getMessageBus();

        app.shutdown();

        assertEquals(Application.State.DISPOSED, app.getState());
        assertTrue(bus.isDisposed());
    }

    @Test
    void applicationManagerTracksInitializedApplicationUntilShutdown() {
        JdeskApplication app = new JdeskApplication();
        PathManager.setHomePathForTests(homeDir);

        app.init();

        assertSame(app, ApplicationManager.getApplication());
        assertSame(app, ApplicationManager.getApplicationIfCreated());

        app.shutdown();

        assertNull(ApplicationManager.getApplicationIfCreated());
        assertThrows(IllegalStateException.class, ApplicationManager::getApplication);
    }

    @Test
    void applicationInfoFromApplicationClassLoaderIsRegisteredInCoreServices() {
        PathManager.setHomePathForTests(homeDir);
        ClassLoader loader = applicationInfoClassLoader("""
                <?xml version="1.0"?>
                <application-info id="frame-test-app">
                    <names product="Frame Test App" fullName="Frame Test App"/>
                </application-info>
                """);
        JdeskApplication app = new JdeskApplication(loader);

        app.init();

        try {
            ApplicationInfo info = app.getService(ApplicationInfo.class);
            assertEquals("frame-test-app", info.getId());
            assertEquals("Frame Test App", info.getProductName());
        } finally {
            app.shutdown();
        }
    }

    @Test
    void launchDisposesApplicationWhenSetupFailsEvenIfExitIsVetoed() {
        PathManager.setHomePathForTests(homeDir);
        RuntimeException failure = new RuntimeException("setup failed");

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                JdeskApplicationBuilder.create("Test")
                        .applicationActivity(context -> {
                            context.getApplication().addLifecycleListener(new ApplicationLifecycleListener() {
                                @Override
                                public boolean canExitApplication(Application app) {
                                    return false;
                                }
                            });
                            throw failure;
                        })
                        .launch());

        assertSame(failure, thrown);
        assertNull(ApplicationManager.getApplicationIfCreated());
    }

    /** A shutdown requested again from a closing callback notifies each phase once, and shutdown completes. */
    @Test
    void aShutdownRequestedWhileListenersAreConsultedRunsThePreparationOnce() {
        JdeskApplication app = new JdeskApplication();
        PathManager.setHomePathForTests(homeDir);
        app.init();
        List<String> events = new ArrayList<>();
        AtomicBoolean nested = new AtomicBoolean();
        app.addLifecycleListener(new ApplicationLifecycleListener() {
            @Override
            public void appClosing(Application application) {
                events.add("closing");
                if (nested.compareAndSet(false, true)) {
                    application.shutdown();
                }
            }

            @Override
            public void onApplicationSaving(Application application) {
                events.add("saving");
            }
        });

        app.shutdown();

        assertEquals(List.of("closing", "saving"), events);
        assertEquals(Application.State.DISPOSED, app.getState());
    }

    /** Shutdown completes when a service throws an Error on dispose; the app ends disposed and unregistered. */
    @Test
    void shutdownCompletesEvenWhenAServiceFailsToDispose() {
        JdeskApplication app = new JdeskApplication();
        PathManager.setHomePathForTests(homeDir);
        app.init();
        app.getServiceContainer().registerInstance(FailingDisposable.class, new FailingDisposable());

        app.shutdown();

        assertEquals(Application.State.DISPOSED, app.getState());
        assertNull(ApplicationManager.getApplicationIfCreated());
    }

    private static final class FailingDisposable implements Disposable {
        @Override
        public void dispose() {
            throw new AssertionError("dispose failed");
        }
    }

    @Test
    void shutdownCancelsApplicationTaskScope() throws Exception {
        JdeskApplication app = new JdeskApplication();
        PathManager.setHomePathForTests(homeDir);
        app.init();
        CountDownLatch started = new CountDownLatch(1);

        ApplicationTask<Void> task = app.getTaskScope().launchIo("long-running-task", () -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(started.await(2, TimeUnit.SECONDS));
        app.shutdown();

        assertEquals(Application.State.DISPOSED, app.getState());
        assertEquals(ApplicationTaskStatus.CANCELLED, task.getStatus());
        assertTrue(task.asFuture().isCancelled());
    }

    @Test
    void startupFailureCancelsApplicationTaskScope() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        RuntimeException failure = new RuntimeException("setup failed");
        AtomicReference<ApplicationTask<Void>> taskRef = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                JdeskApplicationBuilder.create("Test")
                        .applicationActivity(context -> {
                            taskRef.set(context.getApplication().getTaskScope().launchIo("startup-background-task", () -> {
                                started.countDown();
                                try {
                                    Thread.sleep(TimeUnit.SECONDS.toMillis(10));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }));
                            try {
                                assertTrue(started.await(2, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                            throw failure;
                        })
                        .launch());

        assertSame(failure, thrown);
        assertNull(ApplicationManager.getApplicationIfCreated());
        ApplicationTask<Void> task = taskRef.get();
        assertNotNull(task);
        assertEquals(ApplicationTaskStatus.CANCELLED, task.getStatus());
        assertTrue(task.asFuture().isCancelled());
    }

    @Test
    void directLifecycleListenerReceivesFineGrainedStartupAndShutdownEvents() {
        JdeskApplication app = new JdeskApplication();
        PathManager.setHomePathForTests(homeDir);
        List<String> events = new ArrayList<>();

        app.addLifecycleListener(new ApplicationLifecycleListener() {
            @Override
            public void beforeApplicationLoaded(Application app) {
                events.add("beforeLoaded:" + app.getState());
            }

            @Override
            public void applicationInitialized(Application app) {
                events.add("initialized:" + app.getState());
            }

            @Override
            public void beforeUiShown(Application app) {
                events.add("beforeUiShown:" + app.getState());
            }

            @Override
            public void onApplicationStarted(Application app) {
                events.add("started:" + app.getState());
            }

            @Override
            public void appClosing(Application app) {
                events.add("closing:" + app.getState());
            }

            @Override
            public boolean canExitApplication(Application app) {
                events.add("canExit:" + app.getState());
                return true;
            }

            @Override
            public void onApplicationSaving(Application app) {
                events.add("saving:" + app.getState());
            }

            @Override
            public void appWillBeClosed(Application app) {
                events.add("willClose:" + app.getState());
            }

            @Override
            public void onApplicationShuttingDown(Application app) {
                events.add("shuttingDown:" + app.getState());
            }
        });

        app.init();
        app.start();
        app.shutdown();

        assertEquals(Application.State.DISPOSED, app.getState());
        assertEquals(List.of(
                "beforeLoaded:INITIALIZING",
                "initialized:INITIALIZED",
                "beforeUiShown:STARTING",
                "started:RUNNING",
                "closing:RUNNING",
                "canExit:RUNNING",
                "saving:RUNNING",
                "willClose:RUNNING",
                "shuttingDown:SHUTTING_DOWN"
        ), events);
    }

    @Test
    void shutdownCanBeCancelledBeforeSavingAndDisposal() {
        JdeskApplication app = new JdeskApplication();
        PathManager.setHomePathForTests(homeDir);
        app.init();

        AtomicBoolean allowExit = new AtomicBoolean(false);
        List<String> events = new ArrayList<>();

        app.getMessageBus().connect().subscribe(ApplicationLifecycleListener.class,
                new ApplicationLifecycleListener() {
                    @Override
                    public void appClosing(Application app) {
                        events.add("closing");
                    }

                    @Override
                    public boolean canExitApplication(Application app) {
                        events.add("canExit");
                        return allowExit.get();
                    }

                    @Override
                    public void onApplicationSaving(Application app) {
                        events.add("saving");
                    }

                    @Override
                    public void appWillBeClosed(Application app) {
                        events.add("willClose");
                    }

                    @Override
                    public void onApplicationShuttingDown(Application app) {
                        events.add("shuttingDown");
                    }
                });

        app.start();

        app.shutdown();

        assertEquals(Application.State.RUNNING, app.getState());
        assertFalse(app.isDisposed());
        assertEquals(List.of("closing", "canExit"), events);

        allowExit.set(true);
        app.shutdown();

        assertEquals(Application.State.DISPOSED, app.getState());
        assertEquals(List.of(
                "closing", "canExit",
                "closing", "canExit", "saving", "willClose", "shuttingDown"
        ), events);
    }

    @Test
    void messageBusManagerRejectsNewBusCreationWhileApplicationIsShuttingDown() {
        JdeskApplication app = new JdeskApplication();
        PathManager.setHomePathForTests(homeDir);
        app.init();

        MessageBus bus = app.getMessageBus();
        AtomicBoolean connectRejected = new AtomicBoolean(false);
        AtomicBoolean rejected = new AtomicBoolean(false);
        bus.connect().subscribe(ApplicationLifecycleListener.class,
                new ApplicationLifecycleListener() {
                    @Override
                    public void onApplicationShuttingDown(Application app) {
                        assertTrue(bus.isClosedForNewConnections());
                        IllegalStateException connectError = assertThrows(IllegalStateException.class,
                                () -> app.getMessageBus().connect());
                        assertTrue(connectError.getMessage().contains("closed for new connections"));
                        connectRejected.set(true);

                        MessageBusManager manager = app.getService(MessageBusManager.class);
                        IllegalStateException error = assertThrows(IllegalStateException.class,
                                () -> manager.getOrCreateBus(
                                        MessageBusKey.project("late-project"),
                                        Disposer.newDisposable("late-project")));
                        assertTrue(error.getMessage().contains("cannot create MessageBus"));
                        rejected.set(true);
                    }
                });

        app.shutdown();

        assertTrue(connectRejected.get());
        assertTrue(rejected.get());
        assertEquals(Application.State.DISPOSED, app.getState());
    }

    @Test
    void messageBusRemainsPublishableWhileServicesAreDisposed() {
        JdeskApplication app = new JdeskApplication();
        PathManager.setHomePathForTests(homeDir);
        app.init();

        List<String> events = new ArrayList<>();
        MessageBus bus = app.getMessageBus();
        bus.connect().subscribe(ApplicationLifecycleListener.class,
                new ApplicationLifecycleListener() {
                    @Override
                    public void onApplicationSaving(Application app) {
                        if (app.getState() == Application.State.SHUTTING_DOWN) {
                            events.add("service-disposed");
                        }
                    }
                });
        app.getServiceContainer().registerInstance(PublishingOnDisposeService.class,
                new PublishingOnDisposeService(app));

        app.shutdown();

        assertEquals(List.of("service-disposed"), events);
        assertTrue(bus.isDisposed());
    }

    static final class PublishingOnDisposeService implements Disposable {
        private final Application app;

        PublishingOnDisposeService(Application app) {
            this.app = app;
        }

        @Override
        public void dispose() {
            app.getMessageBus()
                    .syncPublisher(ApplicationLifecycleListener.class)
                    .onApplicationSaving(app);
        }
    }

    private static ClassLoader applicationInfoClassLoader(String applicationInfoXml) {
        byte[] bytes = applicationInfoXml.getBytes(StandardCharsets.UTF_8);
        return new ClassLoader(JdeskApplicationLifecycleTest.class.getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (ApplicationInfo.DEFAULT_RESOURCE.equals(name)) {
                    return new ByteArrayInputStream(bytes);
                }
                return super.getResourceAsStream(name);
            }
        };
    }
}
