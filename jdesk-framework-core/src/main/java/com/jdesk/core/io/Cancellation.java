package com.jdesk.core.io;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileLockInterruptionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Cancellation conventions for cancellable threads: thread interruption is the only cancel signal, and checkpoints
 * test the interrupt flag without clearing it. Use {@link #checkpoint} where IOException is declared and
 * {@link #checkpointUnchecked} in loops that don't declare it. Checkpoints don't fire inside a masked section; a wait
 * there that gets interrupted must call {@link #deferInterrupt()} and keep waiting while {@link #masked()} is true.
 */
public final class Cancellation {

    private static final ThreadLocal<Mask> MASK = new ThreadLocal<>();

    private Cancellation() {
    }

    /** Throws InterruptedIOException, keeping the flag, if the thread is interrupted outside a masked section; {@code doing} goes into the message. */
    public static void checkpoint(String doing) throws InterruptedIOException {
        if (stopRequested()) {
            throw new InterruptedIOException("Cancelled while " + doing);
        }
    }

    /** Like {@link #checkpoint} but throws CancellationException, for loops that don't declare IOException. */
    public static void checkpointUnchecked(String doing) {
        if (stopRequested()) {
            throw new CancellationException("Cancelled while " + doing);
        }
    }

    /**
     * Returns whether {@code failure} or its cause chain is a cancellation: InterruptedException, InterruptedIOException
     * (except SocketTimeoutException), ClosedByInterruptException, FileLockInterruptionException or
     * CancellationException; {@code false} for null.
     */
    public static boolean isCancellation(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = failure; link != null && seen.add(link); link = link.getCause()) {
            if (link instanceof InterruptedException
                    || link instanceof InterruptedIOException && !(link instanceof SocketTimeoutException)
                    || link instanceof ClosedByInterruptException
                    || link instanceof FileLockInterruptionException
                    || link instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs {@code work} with interruption masked: clears the flag on entry, suppresses checkpoints, and on exit restores
     * any interrupt seen before or during the section. Nestable.
     * Wrap only short multi-step operations; long reads, copies and walks inside the section won't stop.
     */
    public static <T, E extends Exception> T uninterruptibly(Work<T, E> work) throws E {
        Objects.requireNonNull(work, "work");
        Mask mask = MASK.get();
        if (mask == null) {
            mask = new Mask();
            MASK.set(mask);
        }
        mask.depth++;
        if (Thread.interrupted()) {
            mask.interruptSeen = true;
        }
        try {
            return work.run();
        } finally {
            if (--mask.depth == 0) {
                MASK.remove();
                if (mask.interruptSeen) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Returns whether the current thread is inside a masked section; an interrupted wait uses it to decide whether to keep waiting. */
    public static boolean masked() {
        Mask mask = MASK.get();
        return mask != null && mask.depth > 0;
    }

    /** Call when a wait is interrupted: inside a masked section the interrupt is restored when the section ends, otherwise at once. */
    public static void deferInterrupt() {
        if (masked()) {
            MASK.get().interruptSeen = true;
        } else {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean stopRequested() {
        return Thread.currentThread().isInterrupted() && !masked();
    }

    /** Work that runs inside a masked section. */
    @FunctionalInterface
    public interface Work<T, E extends Exception> {
        T run() throws E;
    }

    private static final class Mask {
        private int depth;
        private boolean interruptSeen;
    }
}
