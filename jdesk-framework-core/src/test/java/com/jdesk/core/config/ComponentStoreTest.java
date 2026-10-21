package com.jdesk.core.config;

import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.service.ServiceDescriptor;
import com.jdesk.core.service.impl.ServiceContainerImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ComponentStoreTest {

    @TempDir
    Path configDir;

    @AfterEach
    void clearDisposerTree() {
        Disposer.clearAll();
    }

    @Test
    void annotatedComponentLoadsDefaultStateAndSavesXml() {
        ComponentStore store = new ComponentStore(configDir);
        AnnotatedComponent component = new AnnotatedComponent();

        store.register(component);

        assertEquals("default", component.state.value);

        component.state.value = "changed";
        store.saveAll();

        assertTrue(Files.isRegularFile(configDir.resolve("annotated.xml")));

        ComponentStore reloadedStore = new ComponentStore(configDir);
        AnnotatedComponent reloaded = new AnnotatedComponent();
        reloadedStore.register(reloaded);

        assertEquals("changed", reloaded.state.value);
    }

    /** Two threads save at once: a snapshot taken earlier must never reach the disk after a newer one. */
    @Test
    void anOlderSnapshotNeverLandsAfterANewerOne() throws Exception {
        ComponentStore store = new ComponentStore(configDir);
        SlowSnapshotComponent component = new SlowSnapshotComponent();
        store.register(component);
        component.value = "old";
        component.holdNextSnapshot = true;

        Thread early = new Thread(store::saveAll);
        early.start();
        assertTrue(component.snapshotTaken.await(10, TimeUnit.SECONDS), "precondition: early save took the old state");
        component.value = "new";
        Thread late = new Thread(store::saveAll);
        late.start();
        awaitDoneOrParked(late);
        component.release.countDown();
        early.join(10_000);
        late.join(10_000);

        SlowSnapshotComponent reloaded = new SlowSnapshotComponent();
        new ComponentStore(configDir).register(reloaded);
        assertEquals("new", reloaded.value);
    }

    /** Waits until the thread ends or has been blocked on a lock for 200 ms in a row. */
    private static void awaitDoneOrParked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int blockedPolls = 0;
        while (thread.isAlive() && blockedPolls < 20 && System.nanoTime() < deadline) {
            blockedPolls = thread.getState() == Thread.State.BLOCKED ? blockedPolls + 1 : 0;
            Thread.sleep(10);
        }
    }

    @Test
    void legacyRegisterApiStillWorks() {
        ComponentStore store = new ComponentStore(configDir);
        LegacyComponent component = new LegacyComponent();

        store.register(component, TestState.class, "legacy.xml");
        component.state.value = "legacy";
        store.saveAll();

        ComponentStore reloadedStore = new ComponentStore(configDir);
        LegacyComponent reloaded = new LegacyComponent();
        reloadedStore.register(reloaded, TestState.class, "legacy.xml");

        assertEquals("legacy", reloaded.state.value);
    }

    @Test
    void lazyServiceIsRegisteredWhenComponentStoreAlreadyExists() {
        ServiceContainerImpl container = new ServiceContainerImpl();
        ComponentStore store = new ComponentStore(configDir);
        container.registerInstance(ComponentStore.class, store);
        container.registerService(PersistentService.class,
                ServiceDescriptor.ofClass(PersistentService.class, PersistentService.class));

        PersistentService service = container.getService(PersistentService.class);

        assertEquals("service-default", service.state.value);

        service.state.value = "service-changed";
        store.saveAll();

        ComponentStore reloadedStore = new ComponentStore(configDir);
        PersistentService reloaded = new PersistentService();
        reloadedStore.register(reloaded);

        assertEquals("service-changed", reloaded.state.value);
    }

    @Test
    void existingServicesCanBeRegisteredAfterComponentStoreIsCreated() {
        ServiceContainerImpl container = new ServiceContainerImpl();
        container.registerService(PersistentService.class,
                ServiceDescriptor.ofClass(PersistentService.class, PersistentService.class));

        PersistentService service = container.getService(PersistentService.class);
        assertNull(service.state);

        ComponentStore store = new ComponentStore(configDir);
        container.registerInstance(ComponentStore.class, store);
        store.registerExistingServices(container);

        assertEquals("service-default", service.state.value);
    }

    /** Unregistering a service saves its state and drops it from the store; registering it again reads that state. */
    @Test
    void unregisteredServiceIsSavedAndReleasedFromStore() {
        ServiceContainerImpl container = new ServiceContainerImpl();
        ComponentStore store = new ComponentStore(configDir);
        container.registerInstance(ComponentStore.class, store);
        container.registerService(PersistentService.class,
                ServiceDescriptor.ofClass(PersistentService.class, PersistentService.class));
        PersistentService service = container.getService(PersistentService.class);
        service.state.value = "saved-on-unregister";

        container.unregisterService(PersistentService.class);
        container.registerService(PersistentService.class,
                ServiceDescriptor.ofClass(PersistentService.class, PersistentService.class));
        PersistentService recreated = container.getService(PersistentService.class);

        assertNotSame(service, recreated);
        assertEquals("saved-on-unregister", recreated.state.value);
    }

    /**
     * A {@code null} String field reads back as {@code null} after a disk round trip, and an empty-string sentinel
     * stays empty. By default the null is written as {@code <optional/>} and read back as {@code ""}, which an
     * in-memory round trip ({@code restored.loadState(source.getState())}) can't catch.
     */
    @Test
    void nullStringStaysNullAcrossDiskRoundTrip() throws Exception {
        ComponentStore store = new ComponentStore(configDir);
        NullableComponent component = new NullableComponent();
        store.register(component);

        component.state.optional = null;
        component.state.sentinel = "";
        component.state.filled = "value";
        store.saveAll();
        String xml = Files.readString(configDir.resolve("nullable.xml"));

        ComponentStore reloadedStore = new ComponentStore(configDir);
        NullableComponent reloaded = new NullableComponent();
        reloadedStore.register(reloaded);

        assertEquals("value", reloaded.state.filled);
        assertNull(reloaded.state.optional);
        assertEquals("", reloaded.state.sentinel);
        assertFalse(xml.contains("optional"), () -> "a null field should not be written:\n" + xml);
        assertTrue(xml.contains("sentinel"), () -> "an empty-string field should still be written:\n" + xml);
    }

    /**
     * A component whose {@code getState()} throws is skipped and the components after it are still saved.
     * Keep the healthy component's name sorting after the broken one, or the test no longer covers that.
     */
    @Test
    void failingComponentDoesNotPreventOtherComponentsFromSaving() throws Exception {
        ComponentStore store = new ComponentStore(configDir);
        BrokenStateComponent broken = new BrokenStateComponent();
        PersistentService healthy = new PersistentService();
        store.register(broken);
        store.register(healthy);
        healthy.state = new TestState("survivor");

        assertDoesNotThrow(store::saveAll);

        assertTrue(Files.isRegularFile(configDir.resolve("service.xml")),
                "the healthy component after the broken one must still be saved");

        ComponentStore reloadedStore = new ComponentStore(configDir);
        PersistentService reloaded = new PersistentService();
        reloadedStore.register(reloaded);

        assertEquals("survivor", reloaded.state.value);
    }

    /**
     * A corrupt state file, such as one truncated by a power cut, is backed up to {@code .corrupt} before the default
     * state is used, and a later saveAll must not overwrite that backup.
     */
    @Test
    void corruptedStateFileIsBackedUpBeforeDefaultFallback() throws Exception {
        Path stateFile = configDir.resolve("annotated.xml");
        Files.createDirectories(configDir);
        byte[] corruptedBytes = "<TestState><value>tru".getBytes();
        Files.write(stateFile, corruptedBytes);

        ComponentStore store = new ComponentStore(configDir);
        AnnotatedComponent component = new AnnotatedComponent();
        store.register(component);

        assertEquals("default", component.state.value, "a corrupt file should fall back to the default state");
        Path backup = configDir.resolve("annotated.xml.corrupt");
        assertTrue(Files.isRegularFile(backup), "a corrupt file must first be backed up as .corrupt");
        assertArrayEquals(corruptedBytes, Files.readAllBytes(backup),
                "the backup must keep the corrupt bytes exactly, for manual repair");

        // saveAll now writes the default state to the original path; the backup must survive untouched.
        store.saveAll();
        assertTrue(Files.isRegularFile(stateFile));
        assertArrayEquals(corruptedBytes, Files.readAllBytes(backup),
                "the .corrupt backup must survive saveAll untouched");
        ComponentStore reloadedStore = new ComponentStore(configDir);
        AnnotatedComponent reloaded = new AnnotatedComponent();
        reloadedStore.register(reloaded);
        assertEquals("default", reloaded.state.value);
    }

    @State(name = "BrokenStateComponent", storages = @Storage("broken.xml"))
    public static final class BrokenStateComponent implements PersistentStateComponent<TestState> {
        @Override
        public TestState getState() {
            throw new IllegalStateException("boom: broken component state");
        }

        @Override
        public void loadState(TestState state) {
        }

        @Override
        public TestState getDefaultState() {
            return new TestState("never");
        }
    }

    @State(name = "NullableComponent", storages = @Storage("nullable.xml"))
    public static final class NullableComponent implements PersistentStateComponent<NullableState> {
        NullableState state;

        @Override
        public NullableState getState() {
            return state;
        }

        @Override
        public void loadState(NullableState state) {
            this.state = state;
        }

        @Override
        public NullableState getDefaultState() {
            return new NullableState();
        }
    }

    public static final class NullableState {
        public String optional;
        public String sentinel = "";
        public String filled;
    }

    @State(name = "AnnotatedComponent", storages = @Storage("annotated.xml"))
    public static final class AnnotatedComponent implements PersistentStateComponent<TestState> {
        TestState state;

        @Override
        public TestState getState() {
            return state;
        }

        @Override
        public void loadState(TestState state) {
            this.state = state;
        }

        @Override
        public TestState getDefaultState() {
            return new TestState("default");
        }
    }

    public static final class LegacyComponent implements PersistentStateComponent<TestState> {
        TestState state = new TestState("initial");

        @Override
        public TestState getState() {
            return state;
        }

        @Override
        public void loadState(TestState state) {
            this.state = state;
        }
    }

    @State(name = "PersistentService", storages = @Storage("service.xml"))
    public static final class PersistentService implements PersistentStateComponent<TestState> {
        TestState state;

        @Override
        public TestState getState() {
            return state;
        }

        @Override
        public void loadState(TestState state) {
            this.state = state;
        }

        @Override
        public TestState getDefaultState() {
            return new TestState("service-default");
        }
    }

    /** Hands out a snapshot of its value; with holdNextSnapshot set, the next call waits for the test's release. */
    @State(name = "SlowSnapshotComponent", storages = @Storage("slow.xml"))
    public static final class SlowSnapshotComponent implements PersistentStateComponent<TestState> {
        volatile String value = "default";
        volatile boolean holdNextSnapshot;
        final CountDownLatch snapshotTaken = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public TestState getState() {
            TestState snapshot = new TestState(value);
            if (holdNextSnapshot) {
                holdNextSnapshot = false;
                snapshotTaken.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return snapshot;
        }

        @Override
        public void loadState(TestState state) {
            value = state.value;
        }

        @Override
        public TestState getDefaultState() {
            return new TestState("default");
        }
    }

    public static final class TestState {
        public String value;

        public TestState() {
        }

        TestState(String value) {
            this.value = value;
        }
    }
}
