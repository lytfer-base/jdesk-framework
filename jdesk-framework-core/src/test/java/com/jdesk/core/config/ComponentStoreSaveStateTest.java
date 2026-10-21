package com.jdesk.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ComponentStoreSaveStateTest {
    @TempDir
    Path configDir;

    @Test
    void candidateIsCommittedToMemoryAndSurvivesReload() throws Exception {
        ComponentStore store = new ComponentStore(configDir);
        TestComponent component = new TestComponent();
        store.register(component);

        store.saveState(component, new TestState("new"));

        assertEquals("new", component.state.value);
        TestComponent restored = new TestComponent();
        new ComponentStore(configDir).register(restored);
        assertEquals("new", restored.state.value);
    }

    @Test
    void serializationFailurePreservesSavedFileAndMemory() throws Exception {
        ComponentStore store = new ComponentStore(configDir);
        TestComponent component = new TestComponent();
        store.register(component);
        store.saveAll();
        byte[] original = Files.readAllBytes(configDir.resolve("state.xml"));
        TestState candidate = new TestState("new");
        candidate.failSerialization = true;

        assertThrows(IOException.class, () -> store.saveState(component, candidate));

        assertEquals("old", component.state.value);
        assertArrayEquals(original, Files.readAllBytes(configDir.resolve("state.xml")));
    }

    @Test
    void writeFailurePreservesMemoryAndExistingDiskContents() throws Exception {
        ComponentStore store = new ComponentStore(configDir);
        TestComponent component = new TestComponent();
        store.register(component);
        Path blockedTarget = Files.createDirectory(configDir.resolve("state.xml"));
        Path existing = blockedTarget.resolve("existing.txt");
        Files.writeString(existing, "must survive");

        assertThrows(IOException.class, () -> store.saveState(component, new TestState("new")));

        assertEquals("old", component.state.value);
        assertEquals("must survive", Files.readString(existing));
    }

    @Test
    void unregisteredInstanceCannotWriteAnotherComponentsStorage() throws Exception {
        ComponentStore store = new ComponentStore(configDir);
        TestComponent registered = new TestComponent();
        store.register(registered);
        store.saveAll();
        byte[] original = Files.readAllBytes(configDir.resolve("state.xml"));

        assertThrows(IllegalArgumentException.class,
                () -> store.saveState(new TestComponent(), new TestState("new")));

        assertEquals("old", registered.state.value);
        assertArrayEquals(original, Files.readAllBytes(configDir.resolve("state.xml")));
    }

    @Test
    void multipleWritableStoragesAreRejectedBeforeEitherIsChanged() throws Exception {
        ComponentStore store = new ComponentStore(configDir);
        MultiStorageComponent component = new MultiStorageComponent();
        store.register(component);
        store.saveAll();
        byte[] first = Files.readAllBytes(configDir.resolve("first.xml"));
        byte[] second = Files.readAllBytes(configDir.resolve("second.xml"));

        assertThrows(IllegalArgumentException.class, () -> store.saveState(component, new TestState("new")));

        assertEquals("old", component.state.value);
        assertArrayEquals(first, Files.readAllBytes(configDir.resolve("first.xml")));
        assertArrayEquals(second, Files.readAllBytes(configDir.resolve("second.xml")));
    }

    @Test
    void deprecatedStorageIsNotWrittenAndDoesNotBlockSingleWritableStorage() throws Exception {
        ComponentStore store = new ComponentStore(configDir);
        MigratedComponent component = new MigratedComponent();
        store.register(component);

        store.saveState(component, new TestState("new"));

        assertFalse(Files.exists(configDir.resolve("deprecated.xml")));
        MigratedComponent restored = new MigratedComponent();
        new ComponentStore(configDir).register(restored);
        assertEquals("new", restored.state.value);
    }

    @Test
    void componentWithoutWritableStorageCannotReportASuccessfulSave() {
        ComponentStore store = new ComponentStore(configDir);
        ReadOnlyComponent component = new ReadOnlyComponent();
        store.register(component);

        assertThrows(IllegalArgumentException.class, () -> store.saveState(component, new TestState("new")));
        assertEquals("old", component.state.value);
    }

    @Test
    void saveAllCannotOverwriteCandidateWithAnOlderSnapshot() throws Exception {
        ComponentStore store = new ComponentStore(configDir);
        SlowSnapshotComponent component = new SlowSnapshotComponent();
        store.register(component);
        component.holdSnapshot = true;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread older = worker(store::saveAll, failure);
        Thread newer = worker(() -> store.saveState(component, new TestState("new")), failure);
        try {
            older.start();
            assertTrue(component.snapshotTaken.await(10, TimeUnit.SECONDS));
            newer.start();
            awaitDoneOrBlocked(newer);
        } finally {
            component.release.countDown();
            older.join(10_000);
            newer.join(10_000);
        }

        assertFalse(older.isAlive());
        assertFalse(newer.isAlive());
        assertNull(failure.get());
        assertEquals("new", component.state.value);
        SlowSnapshotComponent restored = new SlowSnapshotComponent();
        new ComponentStore(configDir).register(restored);
        assertEquals("new", restored.state.value);
    }

    @Test
    void saveAllWaitsForThePersistedCandidateToReachMemory() throws Exception {
        ComponentStore store = new ComponentStore(configDir);
        SlowCommitComponent component = new SlowCommitComponent();
        store.register(component);
        component.holdCommit = true;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread update = worker(() -> store.saveState(component, new TestState("new")), failure);
        Thread closing = worker(store::saveAll, failure);
        try {
            update.start();
            assertTrue(component.commitStarted.await(10, TimeUnit.SECONDS));
            closing.start();
            awaitDoneOrBlocked(closing);
        } finally {
            component.release.countDown();
            update.join(10_000);
            closing.join(10_000);
        }

        assertFalse(update.isAlive());
        assertFalse(closing.isAlive());
        assertNull(failure.get());
        assertEquals("new", component.state.value);
        SlowCommitComponent restored = new SlowCommitComponent();
        new ComponentStore(configDir).register(restored);
        assertEquals("new", restored.state.value);
    }

    private static Thread worker(CheckedTask task, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        thread.setDaemon(true);
        return thread;
    }

    private static void awaitDoneOrBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(!thread.isAlive() || thread.getState() == Thread.State.BLOCKED);
    }

    @FunctionalInterface
    interface CheckedTask {
        void run() throws Exception;
    }

    @State(name = "TestComponent", storages = @Storage("state.xml"))
    static class TestComponent implements PersistentStateComponent<TestState> {
        volatile TestState state = new TestState("old");

        @Override
        public TestState getState() {
            return new TestState(state.value);
        }

        @Override
        public void loadState(TestState state) {
            this.state = state;
        }
    }

    @State(name = "MultiStorageComponent", storages = {@Storage("first.xml"), @Storage("second.xml")})
    static final class MultiStorageComponent extends TestComponent {
    }

    @State(name = "MigratedComponent", storages = {
            @Storage(value = "deprecated.xml", deprecated = true), @Storage("current.xml")})
    static final class MigratedComponent extends TestComponent {
    }

    @State(name = "ReadOnlyComponent", storages = @Storage(value = "deprecated.xml", deprecated = true))
    static final class ReadOnlyComponent extends TestComponent {
    }

    @State(name = "SlowSnapshotComponent", storages = @Storage("slow.xml"))
    static final class SlowSnapshotComponent extends TestComponent {
        volatile boolean holdSnapshot;
        final CountDownLatch snapshotTaken = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public TestState getState() {
            TestState snapshot = super.getState();
            if (holdSnapshot) {
                holdSnapshot = false;
                snapshotTaken.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Snapshot was never released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return snapshot;
        }
    }

    @State(name = "SlowCommitComponent", storages = @Storage("commit.xml"))
    static final class SlowCommitComponent extends TestComponent {
        volatile boolean holdCommit;
        final CountDownLatch commitStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void loadState(TestState state) {
            if (holdCommit) {
                holdCommit = false;
                commitStarted.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Commit was never released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            super.loadState(state);
        }
    }

    public static final class TestState {
        public String value;
        @JsonIgnore
        public boolean failSerialization;

        public TestState() {
        }

        TestState(String value) {
            this.value = value;
        }

        public String getSerializationProbe() {
            if (failSerialization) throw new IllegalStateException("Cannot serialize candidate");
            return null;
        }
    }
}
