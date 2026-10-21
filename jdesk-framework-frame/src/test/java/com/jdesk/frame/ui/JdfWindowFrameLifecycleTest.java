package com.jdesk.frame.ui;

import org.junit.jupiter.api.Test;

import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class JdfWindowFrameLifecycleTest {

    @Test
    void windowCloseDisposesFrameByDefault() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TrackingWindowFrame frame = new TrackingWindowFrame("test");

        assertTrue(frame.closeFrameAsync(JdfFrameCloseReason.WINDOW_CLOSE).join());

        assertTrue(frame.disposed);
    }

    @Test
    void applicationExitDisposesFrameByDefault() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TrackingWindowFrame frame = new TrackingWindowFrame("test");

        assertTrue(frame.closeFrameAsync(JdfFrameCloseReason.APPLICATION_EXIT).join());

        assertTrue(frame.disposed);
    }

    @Test
    void vetoSkipsCloseAction() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TrackingWindowFrame frame = new TrackingWindowFrame("test");
        frame.beforeCloseResult = CompletableFuture.completedFuture(false);

        assertFalse(frame.closeFrameAsync(JdfFrameCloseReason.APPLICATION_EXIT).join());

        assertFalse(frame.disposed);
    }

    /** A second close while the before-close hook is pending joins the first; the hook and the close run once. */
    @Test
    void aRepeatedCloseWhileTheHookIsPendingJoinsTheFirst() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TrackingWindowFrame frame = new TrackingWindowFrame("test");
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        frame.beforeCloseResult = answer;

        CompletableFuture<Boolean> first = frame.closeFrameAsync(JdfFrameCloseReason.WINDOW_CLOSE);
        CompletableFuture<Boolean> second = frame.closeFrameAsync(JdfFrameCloseReason.WINDOW_CLOSE);
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        answer.complete(true);

        assertTrue(first.join());
        assertTrue(second.join());
        assertEquals(1, frame.beforeCloseCalls);
        assertEquals(1, frame.disposeCalls);
    }

    @Test
    void openRunsOnEdtAndCallsHookAfterVisible() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TrackingWindowFrame frame = new TrackingWindowFrame("test");

        frame.openFrame();
        assertTrue(frame.opened.join());

        assertTrue(frame.isVisible());
        assertTrue(frame.openedOnEdt);
        frame.dispose();
    }

    @Test
    void openFrameRestoresMinimizedWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TrackingWindowFrame frame = new TrackingWindowFrame("test");
        frame.openFrame();
        assertTrue(frame.opened.join());
        javax.swing.SwingUtilities.invokeAndWait(
                () -> frame.setExtendedState(frame.getExtendedState() | Frame.ICONIFIED));

        frame.openFrame();

        java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger();
        javax.swing.SwingUtilities.invokeAndWait(() -> state.set(frame.getExtendedState()));
        assertEquals(0, state.get() & Frame.ICONIFIED);
        frame.dispose();
    }

    @Test
    void beforeCloseRunsOnEdtWhenClosedFromBackgroundThread() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        TrackingWindowFrame frame = new TrackingWindowFrame("test");
        CompletableFuture<Boolean> closed = new CompletableFuture<>();
        Thread caller = new Thread(() -> {
            try {
                closed.complete(frame.closeFrameAsync(JdfFrameCloseReason.WINDOW_CLOSE).join());
            } catch (RuntimeException failure) {
                closed.completeExceptionally(failure);
            }
        }, "jdf-frame-close-test");

        caller.start();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(closed.join()));
        assertTrue(frame.beforeCloseOnEdt);
    }

    private static final class TrackingWindowFrame extends JdfWindowFrame {
        private CompletableFuture<Boolean> beforeCloseResult = CompletableFuture.completedFuture(true);
        private final CompletableFuture<Boolean> opened = new CompletableFuture<>();
        private boolean disposed;
        private int disposeCalls;
        private int beforeCloseCalls;
        private boolean beforeCloseOnEdt;
        private boolean openedOnEdt;

        private TrackingWindowFrame(String title) {
            super(title);
        }

        @Override
        protected CompletionStage<Boolean> beforeFrameClose(JdfFrameCloseReason reason) {
            beforeCloseOnEdt = javax.swing.SwingUtilities.isEventDispatchThread();
            beforeCloseCalls++;
            return beforeCloseResult;
        }

        @Override
        protected void onFrameOpened() {
            openedOnEdt = javax.swing.SwingUtilities.isEventDispatchThread();
            opened.complete(isVisible());
        }

        @Override
        public void dispose() {
            disposed = true;
            disposeCalls++;
            super.dispose();
        }
    }
}
