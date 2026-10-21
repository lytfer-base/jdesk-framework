package com.jdesk.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Schedules work on the Swing Event Dispatch Thread (EDT).
 * Inspired by IntelliJ Platform's EDT scheduling utilities.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link #invokeLater(Runnable)} — deferred EDT execution</li>
 *   <li>{@link #invokeAndWait(Runnable)} — blocking EDT execution</li>
 *   <li>{@link #schedule(Runnable, long, TimeUnit)} — delayed EDT execution</li>
 *   <li>{@link #schedulePeriodic(Runnable, long, long, TimeUnit)} — periodic EDT execution</li>
 * </ul>
 */
public class EdtScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(EdtScheduler.class);

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "EdtScheduler-Timer");
        t.setDaemon(true);
        return t;
    });

    /**
     * Run on the EDT as soon as possible.
     */
    public void invokeLater(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(safeWrap(action));
        }
    }

    /**
     * Run on the EDT and block until done.
     */
    public void invokeAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(safeWrap(action));
            } catch (Exception e) {
                LOG.error("invokeAndWait failed", e);
            }
        }
    }

    /**
     * Run on the EDT after a delay.
     */
    public ScheduledFuture<?> schedule(Runnable action, long delay, TimeUnit unit) {
        return timer.schedule(() -> invokeLater(action), delay, unit);
    }

    /**
     * Run periodically on the EDT.
     */
    public ScheduledFuture<?> schedulePeriodic(Runnable action, long initialDelay, long period, TimeUnit unit) {
        return timer.scheduleAtFixedRate(() -> invokeLater(action), initialDelay, period, unit);
    }

    /**
     * Assert that the current thread is the EDT. Throws if not.
     */
    public static void assertEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Must be called on EDT. Current: " + Thread.currentThread().getName());
        }
    }

    /**
     * Check if currently on the EDT.
     */
    public static boolean isEdt() {
        return SwingUtilities.isEventDispatchThread();
    }

    /**
     * Shutdown the internal timer.
     */
    public void dispose() {
        timer.shutdown();
    }

    private Runnable safeWrap(Runnable action) {
        return () -> {
            try {
                action.run();
            } catch (Exception e) {
                LOG.error("Unhandled exception on EDT", e);
            }
        };
    }
}
