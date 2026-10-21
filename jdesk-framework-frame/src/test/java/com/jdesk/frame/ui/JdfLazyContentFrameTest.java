package com.jdesk.frame.ui;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationLifecycleListener;
import com.jdesk.core.app.ApplicationTaskScope;
import com.jdesk.core.extensions.ExtensionRegistry;
import com.jdesk.core.message.MessageBus;
import com.jdesk.core.service.ServiceContainer;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Tests the lazy loading in {@link JdfLazyContentFrame}: content loads once, background and EDT thread boundaries,
 * fallback with retry on failure, reassembly after reset, and reset discarding in-flight loads and handing stale
 * results to the discard hook.
 */
class JdfLazyContentFrameTest {

    @Test
    void loadsContentOnceWithCorrectThreadBoundary() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TestLazyFrame frame = new TestLazyFrame();

        JLabel content = frame.ensureContentLoaded().join();
        JLabel again = frame.ensureContentLoaded().join();

        assertEquals("content", content.getText());
        assertSame(content, frame.getContentPane());
        assertSame(content, again);
        assertEquals(1, frame.loadCalls.get());
        assertEquals(1, frame.createCalls.get());
        assertSame(content, frame.currentContent().orElseThrow());
        assertFalse(frame.loadOnEdt, "loadInBackground should run on a background thread");
        assertTrue(frame.createOnEdt, "createContent should run on the EDT");
        assertTrue(frame.discardedLoaded.isEmpty(), "a normal load without reset should not call the discard hook");
        frame.dispose();
    }

    @Test
    void installsUnavailableOnFailureThenRetries() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TestLazyFrame frame = new TestLazyFrame();
        frame.failLoad = true;

        Throwable error = frame.ensureContentLoaded().handle((value, failure) -> failure).join();

        assertNotNull(error, "the future should complete exceptionally when loading fails");
        assertEquals("lazy-unavailable", ((JComponent) frame.getContentPane()).getName());
        assertTrue(frame.currentContent().isEmpty());

        frame.failLoad = false;
        JLabel content = frame.ensureContentLoaded().join();

        assertEquals("content", content.getText());
        assertSame(content, frame.getContentPane());
        assertEquals(2, frame.loadCalls.get(), "opening again after a failure should load again");
        frame.dispose();
    }

    @Test
    void createContentFailureReleasesDiscardedBackground() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TestLazyFrame frame = new TestLazyFrame();
        frame.failCreate = true;

        Throwable error = frame.ensureContentLoaded().handle((value, failure) -> failure).join();

        assertNotNull(error, "the future should complete exceptionally when createContent fails");
        assertEquals("lazy-unavailable", ((JComponent) frame.getContentPane()).getName(),
                "a createContent failure should show the unavailable panel");
        assertTrue(frame.currentContent().isEmpty());
        assertEquals(List.of("content"), frame.discardedLoaded,
                "the result discarded by a createContent failure should go to the discard hook");
        assertTrue(frame.disposeOnEdt, "disposeDiscardedBackground should be called on the EDT");
        frame.dispose();
    }

    /** An Error from createContent, such as a failed backend class load, still ends in the unavailable panel. */
    @Test
    void createContentErrorStillEndsInTheUnavailablePanel() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TestLazyFrame frame = new TestLazyFrame();
        frame.createError = true;

        Throwable error = frame.ensureContentLoaded().handle((value, failure) -> failure)
                .orTimeout(10, TimeUnit.SECONDS).join();

        assertNotNull(error);
        assertEquals("lazy-unavailable", ((JComponent) frame.getContentPane()).getName());
        assertEquals(List.of("content"), frame.discardedLoaded);
        frame.dispose();
    }

    @Test
    void resetLazyContentAllowsReassembleWithNewBackend() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TestLazyFrame frame = new TestLazyFrame();

        JLabel first = frame.ensureContentLoaded().join();
        assertEquals(1, frame.loadCalls.get());
        assertSame(first, frame.getContentPane());

        SwingUtilities.invokeAndWait(frame::resetLazyContent);

        // After reset the placeholder is back, no content is loaded, and the old content has left the hierarchy.
        assertEquals("lazy-loading", ((JComponent) frame.getContentPane()).getName());
        assertTrue(frame.currentContent().isEmpty(), "no content should be loaded after reset");
        assertNull(first.getParent(), "the old content should be removed from the contentPane");

        // Each createContent makes a new instance, so assertNotSame plus loadCalls == 2 proves a fresh load.
        JLabel second = frame.ensureContentLoaded().join();

        assertNotSame(first, second, "reset should lead to a new content instance");
        assertSame(second, frame.getContentPane());
        assertEquals(2, frame.loadCalls.get(), "loading after reset should run the background step again");
        assertEquals(2, frame.createCalls.get());
        assertTrue(frame.discardedLoaded.isEmpty(), "reset with no load in flight should not call the discard hook");
        frame.dispose();
    }

    @Test
    void resetLazyContentRejectsCallsOffEdt() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TestLazyFrame frame = new TestLazyFrame();
        frame.ensureContentLoaded().join();

        assertThrows(IllegalStateException.class, frame::resetLazyContent);
        frame.dispose();
    }

    @Test
    void resetDiscardsInFlightAssemblyAndReleasesOrphan() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TestLazyFrame frame = new TestLazyFrame();
        CountDownLatch gate = new CountDownLatch(1);
        frame.firstLoadGate = gate; // The first load blocks here, and each load returns a distinct backend value.

        // 1. Start the first load: F1's loadInBackground blocks on the latch and stays in flight.
        CompletableFuture<JLabel> first = frame.ensureContentLoaded();
        awaitCount(frame.loadCalls, 1);

        // 2. Reset on the EDT while the window is hidden, discarding F1's in-flight load.
        SwingUtilities.invokeAndWait(frame::resetLazyContent);

        // 3. The second load doesn't block; the contentPane then holds its content.
        JLabel second = frame.ensureContentLoaded().join();
        assertEquals("backend-2", second.getText());
        assertSame(second, frame.getContentPane());

        // 4. Release F1; its result reaches the EDT stale and must not be installed.
        gate.countDown();
        CancellationException cancelled =
                assertThrows(CancellationException.class, () -> first.get(5, TimeUnit.SECONDS));
        assertEquals("lazy content was reset", cancelled.getMessage());

        // Flush the EDT so F1's install callback, including disposeDiscardedBackground, has run.
        SwingUtilities.invokeAndWait(() -> { });

        // 5. The discard hook got the first load's result, and the second content stays in place.
        assertEquals(List.of("backend-1"), frame.discardedLoaded, "the stale result should go to the discard hook");
        assertTrue(frame.disposeOnEdt, "disposeDiscardedBackground should be called on the EDT");
        assertSame(second, frame.getContentPane());
        assertSame(second, frame.currentContent().orElseThrow());
        frame.dispose();
    }

    /**
     * A load result arriving after dispose goes to the discard hook instead of the window; a WebView created on a
     * disposed window would never be released.
     */
    @Test
    void disposeDiscardsInFlightAssemblyAndReleasesOrphan() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TestLazyFrame frame = new TestLazyFrame();
        CountDownLatch gate = new CountDownLatch(1);
        frame.firstLoadGate = gate;

        CompletableFuture<JLabel> inFlight = frame.ensureContentLoaded();
        awaitCount(frame.loadCalls, 1);
        SwingUtilities.invokeAndWait(frame::dispose);

        gate.countDown();
        CancellationException cancelled =
                assertThrows(CancellationException.class, () -> inFlight.get(5, TimeUnit.SECONDS));
        assertEquals("lazy content was reset", cancelled.getMessage());
        SwingUtilities.invokeAndWait(() -> { });   // Flush the EDT so the install callback has run.

        assertEquals(0, frame.createCalls.get(), "no content should be created on a disposed window");
        assertEquals(List.of("backend-1"), frame.discardedLoaded, "the late result should go to the discard hook");
        assertTrue(frame.currentContent().isEmpty());
    }

    /**
     * A load discarded by reset or dispose may <b>still be writing to disk</b>, so {@code pendingContentLoad} must
     * still report it; otherwise shutdown won't wait and System.exit cuts the write short.
     */
    @Test
    void pendingContentLoadCoversTheDiscardedButStillRunningAssembly() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TestLazyFrame frame = new TestLazyFrame();
        CountDownLatch gate = new CountDownLatch(1);
        frame.firstLoadGate = gate;

        frame.ensureContentLoaded();
        awaitCount(frame.loadCalls, 1);
        SwingUtilities.invokeAndWait(frame::resetLazyContent);

        CompletableFuture<?> pending = frame.pendingContentLoad().toCompletableFuture();
        assertFalse(pending.isDone(), "the discarded load is still running, so shutdown must still wait for it");

        gate.countDown();
        pending.get(5, TimeUnit.SECONDS);
        frame.dispose();
    }

    private static void awaitCount(AtomicInteger counter, int target) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (counter.get() < target) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("timed out waiting for count " + target + ", current=" + counter.get());
            }
            Thread.sleep(5);
        }
    }

    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the first load timed out waiting for release");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Test double with B=String and C=JLabel that tracks call counts and threads and can be made to fail. */
    private static final class TestLazyFrame extends JdfLazyContentFrame<String, JLabel> {
        private final AtomicInteger loadCalls = new AtomicInteger();
        private final AtomicInteger createCalls = new AtomicInteger();
        private volatile boolean failLoad;
        private volatile boolean failCreate;
        private volatile boolean createError;
        private volatile boolean loadOnEdt;
        private volatile boolean createOnEdt;
        /** When set, the first loadInBackground blocks on it and each load returns a distinct backend value. */
        private volatile CountDownLatch firstLoadGate;
        /** Whether the latest disposeDiscardedBackground call ran on the EDT. */
        private volatile boolean disposeOnEdt;
        /** Results passed to disposeDiscardedBackground, in call order. */
        private final List<String> discardedLoaded = Collections.synchronizedList(new ArrayList<>());

        private TestLazyFrame() {
            super("test-lazy", new FakeApplication(), "test.load");
        }

        @Override
        protected String loadInBackground() {
            loadOnEdt = SwingUtilities.isEventDispatchThread();
            int call = loadCalls.incrementAndGet();
            if (failLoad) {
                throw new IllegalStateException("boom");
            }
            CountDownLatch gate = firstLoadGate;
            if (gate == null) {
                return "content";
            }
            if (call == 1) {
                awaitGate(gate);
            }
            return "backend-" + call;
        }

        @Override
        protected void disposeDiscardedBackground(String loaded) {
            disposeOnEdt = SwingUtilities.isEventDispatchThread();
            discardedLoaded.add(loaded);
        }

        @Override
        protected JLabel createContent(String loaded) {
            createOnEdt = SwingUtilities.isEventDispatchThread();
            createCalls.incrementAndGet();
            if (failCreate) {
                throw new IllegalStateException("create-boom");
            }
            if (createError) {
                throw new NoClassDefFoundError("create-error");
            }
            JLabel label = new JLabel(loaded);
            label.setName("lazy-content");
            return label;
        }

        @Override
        protected JComponent createLoadingPlaceholder() {
            JLabel label = new JLabel("loading");
            label.setName("lazy-loading");
            return label;
        }

        @Override
        protected JComponent createUnavailableContent(Throwable failure) {
            JLabel label = new JLabel("unavailable");
            label.setName("lazy-unavailable");
            return label;
        }
    }

    private static final class FakeApplication implements Application {
        private final ApplicationTaskScope taskScope = new ApplicationTaskScope("lazy-frame-test");

        @Override public void init() { }
        @Override public void start() { }
        @Override public void shutdown() { }
        @Override public void addLifecycleListener(ApplicationLifecycleListener listener) { }
        @Override public void removeLifecycleListener(ApplicationLifecycleListener listener) { }
        @Override public boolean isDisposed() { return false; }
        @Override public State getState() { return State.RUNNING; }
        @Override public ClassLoader getApplicationClassLoader() { return getClass().getClassLoader(); }
        @Override public ApplicationTaskScope getTaskScope() { return taskScope; }
        @Override public ServiceContainer getServiceContainer() { return null; }
        @Override public <T> T getService(Class<T> serviceClass) {
            throw new IllegalStateException("No fake service: " + serviceClass.getName());
        }
        @Override public ExtensionRegistry getExtensionRegistry() { return null; }
        @Override public MessageBus getMessageBus() { return null; }
    }
}
