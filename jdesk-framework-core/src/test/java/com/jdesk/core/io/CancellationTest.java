package com.jdesk.core.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileLockInterruptionException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CancellationTest {

    /** When interrupted, checkpoint throws InterruptedIOException naming the operation and keeps the flag. */
    @Test
    void checkpointThrowsAndKeepsTheFlag() {
        try {
            Thread.currentThread().interrupt();

            InterruptedIOException failure = assertThrows(InterruptedIOException.class,
                    () -> Cancellation.checkpoint("copying a.txt"));

            assertTrue(failure.getMessage().contains("copying a.txt"), failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "a checkpoint only reads the flag, so it stays set");
        } finally {
            Thread.interrupted();
        }
    }

    /** Both checkpoints pass when the thread isn't interrupted. */
    @Test
    void checkpointPassesWhenNotInterrupted() throws IOException {
        Thread.interrupted();

        Cancellation.checkpoint("copying a.txt");
        Cancellation.checkpointUnchecked("listing a folder");

        assertFalse(Thread.currentThread().isInterrupted());
    }

    /** The unchecked checkpoint throws CancellationException and keeps the flag. */
    @Test
    void uncheckedCheckpointThrowsCancellation() {
        try {
            Thread.currentThread().interrupt();

            CancellationException failure = assertThrows(CancellationException.class,
                    () -> Cancellation.checkpointUnchecked("listing a folder"));

            assertTrue(failure.getMessage().contains("listing a folder"), failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "a checkpoint only reads the flag, so it stays set");
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Each cancellation type is recognized bare or wrapped in CompletionException, UncheckedIOException or
     * RuntimeException, and a cyclic cause chain doesn't loop forever.
     */
    @Test
    void cancellationIsRecognizedThroughTheCauseChain() {
        List<Throwable> cancellations = List.of(
                new InterruptedException("waiting"),
                new InterruptedIOException("copying"),
                new ClosedByInterruptException(),
                new FileLockInterruptionException(),
                new CancellationException("closed"));

        for (Throwable cancellation : cancellations) {
            String name = cancellation.getClass().getSimpleName();
            assertTrue(Cancellation.isCancellation(cancellation), name);
            assertTrue(Cancellation.isCancellation(new CompletionException(cancellation)), name + " in CompletionException");
            assertTrue(Cancellation.isCancellation(uncheckedIo(cancellation)), name + " in UncheckedIOException");
            assertTrue(Cancellation.isCancellation(new RuntimeException("wrapped", cancellation)), name + " in RuntimeException");
            assertTrue(Cancellation.isCancellation(new CompletionException(new RuntimeException(uncheckedIo(cancellation)))),
                    name + " several levels down");
        }

        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertFalse(Cancellation.isCancellation(first), "a cyclic chain without a cancellation should not match");
        assertFalse(Cancellation.isCancellation(new RuntimeException(new IOException("disk full"))));
        assertFalse(Cancellation.isCancellation(null));
    }

    /** SocketTimeoutException extends InterruptedIOException, but it is a timeout, not a cancellation. */
    @Test
    void aSocketTimeoutIsNotACancellation() {
        SocketTimeoutException timeout = new SocketTimeoutException("Read timed out");

        assertFalse(Cancellation.isCancellation(timeout));
        assertFalse(Cancellation.isCancellation(new CompletionException(new UncheckedIOException(timeout))));
    }

    /**
     * An interrupt from before the section is cleared inside it, so checkpoints pass and waits work, and is restored
     * when the section ends, even by throwing.
     */
    @Test
    void uninterruptiblyRunsToTheEndAndRestoresTheFlag() throws Exception {
        try {
            Thread.currentThread().interrupt();

            String result = Cancellation.uninterruptibly(() -> {
                assertFalse(Thread.currentThread().isInterrupted(), "entering the section should clear the flag");
                Cancellation.checkpoint("moving a document");
                Cancellation.checkpointUnchecked("moving its folder");
                Thread.sleep(1);
                return "moved";
            });

            assertEquals("moved", result);
            assertFalse(Cancellation.masked(), "no longer masked after the section");
            assertTrue(Thread.currentThread().isInterrupted(), "the earlier interrupt should return after the section");

            IOException failure = assertThrows(IOException.class, () -> Cancellation.uninterruptibly(() -> {
                throw new IOException("disk full");
            }));
            assertEquals("disk full", failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "a throw inside the section should restore it too");
        } finally {
            Thread.interrupted();
        }
    }

    /** An interrupt from another thread during the section doesn't fail checkpoints; a wait defers it until the end. */
    @Test
    void anInterruptDuringTheSectionIsRestoredAtTheEnd() throws Exception {
        Thread section = Thread.currentThread();
        try {
            boolean maskedInside = Cancellation.uninterruptibly(() -> {
                Thread interrupter = new Thread(section::interrupt, "cancellation-test-interrupter");
                interrupter.start();
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.onSpinWait();
                }
                Cancellation.checkpoint("moving a document");
                Cancellation.checkpointUnchecked("moving its folder");
                try {
                    Thread.sleep(10_000);
                    fail("the interrupt has arrived, so the wait should end at once");
                } catch (InterruptedException interrupted) {
                    Cancellation.deferInterrupt();
                }
                interrupter.join();
                return Cancellation.masked();
            });

            assertTrue(maskedInside, "the section should be masked");
            assertTrue(Thread.currentThread().isInterrupted(), "the deferred interrupt should be restored at the end");
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Masked sections nest: an interrupt deferred in an inner section is restored when the outermost one exits, and
     * entering an inner section clears the flag as well.
     */
    @Test
    void sectionsNest() throws Exception {
        try {
            Cancellation.uninterruptibly(() -> {
                Cancellation.uninterruptibly(() -> {
                    assertTrue(Cancellation.masked());
                    Cancellation.deferInterrupt();
                    return null;
                });
                assertTrue(Cancellation.masked(), "still masked by the outer section after the inner one exits");
                assertFalse(Thread.currentThread().isInterrupted(), "the inner exit should not restore the flag yet");
                Cancellation.checkpoint("finishing the outer section");
                return null;
            });
            assertFalse(Cancellation.masked());
            assertTrue(Thread.currentThread().isInterrupted(), "the inner deferral should return at the outer exit");
            Thread.interrupted();

            Cancellation.uninterruptibly(() -> {
                Thread.currentThread().interrupt();
                Cancellation.uninterruptibly(() -> {
                    assertFalse(Thread.currentThread().isInterrupted(), "the inner entry should clear the flag too");
                    return null;
                });
                assertFalse(Thread.currentThread().isInterrupted(), "the inner exit should not restore the flag yet");
                return null;
            });
            assertTrue(Thread.currentThread().isInterrupted(), "the cleared interrupt should return at the outer exit");
        } finally {
            Thread.interrupted();
        }
    }

    private static UncheckedIOException uncheckedIo(Throwable cause) {
        return cause instanceof IOException io
                ? new UncheckedIOException(io)
                : new UncheckedIOException(new IOException(cause));
    }
}
