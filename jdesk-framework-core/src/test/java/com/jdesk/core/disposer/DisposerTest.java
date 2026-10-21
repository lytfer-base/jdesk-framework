package com.jdesk.core.disposer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisposerTest {

    @AfterEach
    void clearDisposerTree() {
        Disposer.clearAll();
    }

    @Test
    void disposeRemovesNodeFromActiveTreeButKeepsDisposedState() {
        CountingDisposable parent = new CountingDisposable();
        CountingDisposable child = new CountingDisposable();
        Disposer.register(parent, child);

        Disposer.dispose(child);
        Disposer.dispose(child);

        assertEquals(1, child.disposeCount);
        assertTrue(Disposer.isDisposed(child));
        assertEquals(1, Disposer.getTreeSize());

        Disposer.dispose(parent);

        assertEquals(1, parent.disposeCount);
        assertEquals(0, Disposer.getTreeSize());
    }

    /** A child that throws an Error doesn't stop its siblings or parent from disposing, and the tree is emptied. */
    @Test
    void aChildFailingWithAnErrorDoesNotStopItsSiblingsOrParent() {
        CountingDisposable parent = new CountingDisposable();
        CountingDisposable first = new CountingDisposable();
        Disposable failing = () -> {
            throw new AssertionError("dispose failed");
        };
        CountingDisposable last = new CountingDisposable();
        Disposer.register(parent, first);
        Disposer.register(parent, failing);
        Disposer.register(parent, last);

        Disposer.dispose(parent);

        assertEquals(1, first.disposeCount);
        assertEquals(1, last.disposeCount);
        assertEquals(1, parent.disposeCount);
        assertTrue(Disposer.isDisposed(failing));
        assertEquals(0, Disposer.getTreeSize());
    }

    /** An unregistered object that throws an Error is logged and marked disposed; the caller isn't interrupted. */
    @Test
    void anUnregisteredDisposableFailingWithAnErrorIsStillMarkedDisposed() {
        Disposable failing = () -> {
            throw new AssertionError("dispose failed");
        };

        Disposer.dispose(failing);

        assertTrue(Disposer.isDisposed(failing));
    }

    @Test
    void disposeUnregisteredDisposableIsIdempotent() {
        CountingDisposable disposable = new CountingDisposable();

        Disposer.dispose(disposable);
        Disposer.dispose(disposable);

        assertEquals(1, disposable.disposeCount);
        assertTrue(Disposer.isDisposed(disposable));
        assertEquals(0, Disposer.getTreeSize());
    }

    @Test
    void removeNodeRejectsUndisposedChildren() {
        CountingDisposable parent = new CountingDisposable();
        CountingDisposable child = new CountingDisposable();
        Disposer.register(parent, child);

        assertThrows(IllegalStateException.class, () -> Disposer.removeNode(parent));

        assertFalse(Disposer.isDisposed(parent));
        assertEquals(0, parent.disposeCount);
        assertEquals(0, child.disposeCount);
        assertEquals(2, Disposer.getTreeSize());
    }

    @Test
    void activeTreeUsesObjectIdentityEvenWhenEqualsMatches() {
        EqualDisposable first = new EqualDisposable(1);
        EqualDisposable second = new EqualDisposable(1);
        CountingDisposable firstChild = new CountingDisposable();
        CountingDisposable secondChild = new CountingDisposable();
        Disposer.register(first, firstChild);
        Disposer.register(second, secondChild);

        Disposer.dispose(first);

        assertEquals(1, first.disposeCount);
        assertEquals(1, firstChild.disposeCount);
        assertEquals(0, second.disposeCount);
        assertEquals(0, secondChild.disposeCount);
        assertTrue(Disposer.isDisposed(first));
        assertFalse(Disposer.isDisposed(second));
        assertEquals(2, Disposer.getTreeSize());
    }

    /**
     * A register racing dispose either attaches the child, which is then disposed exactly once with the parent, or
     * throws IllegalStateException so the caller disposes it, as with tryRegister. It never returns normally while
     * leaving the child outside both the disposal snapshot and the active tree.
     */
    @Test
    void concurrentRegisterDuringDisposeNeverLeaksChild() throws Exception {
        // Slow children stretch dispose's mark, snapshot and clear steps so that register races them more often.
        for (int round = 0; round < 200; round++) {
            CountingDisposable parent = new CountingDisposable();
            for (int i = 0; i < 30; i++) {
                Disposer.register(parent, new BusyChild(100_000));
            }
            CountingDisposable target = new CountingDisposable();

            AtomicReference<Throwable> registerFailure = new AtomicReference<>();
            CyclicBarrier barrier = new CyclicBarrier(2);
            Thread registerThread = new Thread(() -> {
                try {
                    barrier.await();
                    Disposer.register(parent, target);
                } catch (Throwable t) {
                    registerFailure.set(t);
                }
            }, "h4-register-" + round);
            registerThread.start();

            barrier.await();
            Disposer.dispose(parent);
            registerThread.join(10_000);

            if (registerFailure.get() != null) {
                // The re-check caught the race: dispose the child as a tryRegister caller would.
                assertTrue(registerFailure.get() instanceof IllegalStateException,
                        "unexpected register failure: " + registerFailure.get());
                Disposer.dispose(target);
            }
            assertEquals(1, target.disposeCount, "round " + round);
        }
        assertEquals(0, Disposer.getTreeSize(), "all nodes must leave the active tree");
    }

    /**
     * Two threads registering one child under different parents leave it under exactly one of them, as if the calls
     * ran in sequence, and disposing both parents then disposes the child exactly once.
     */
    @Test
    void concurrentReparentKeepsChildAttachedToExactlyOneParent() throws Exception {
        for (int round = 0; round < 200; round++) {
            CountingDisposable parentOne = new CountingDisposable();
            CountingDisposable parentTwo = new CountingDisposable();
            CountingDisposable child = new CountingDisposable();

            AtomicReference<Throwable> failureOne = new AtomicReference<>();
            AtomicReference<Throwable> failureTwo = new AtomicReference<>();
            CyclicBarrier barrier = new CyclicBarrier(2);
            Thread threadOne = new Thread(() -> {
                try {
                    barrier.await();
                    Disposer.register(parentOne, child);
                } catch (Throwable t) {
                    failureOne.set(t);
                }
            }, "h4-reparent-one-" + round);
            Thread threadTwo = new Thread(() -> {
                try {
                    barrier.await();
                    Disposer.register(parentTwo, child);
                } catch (Throwable t) {
                    failureTwo.set(t);
                }
            }, "h4-reparent-two-" + round);
            threadOne.start();
            threadTwo.start();
            threadOne.join(10_000);
            threadTwo.join(10_000);

            if (failureOne.get() != null) {
                throw new AssertionError("register to parentOne failed", failureOne.get());
            }
            if (failureTwo.get() != null) {
                throw new AssertionError("register to parentTwo failed", failureTwo.get());
            }
            assertEquals(1, Disposer.countParentsOf(child),
                    "child must attach to exactly one parent, round " + round);

            Disposer.dispose(parentOne);
            Disposer.dispose(parentTwo);
            assertEquals(1, child.disposeCount, "round " + round);
        }
        assertEquals(0, Disposer.getTreeSize(), "all nodes must leave the active tree");
    }

    /** Busy-waits in dispose() for the given time to widen the parent's disposal window; never throws or blocks. */
    private static final class BusyChild implements Disposable {
        private final long busyNanos;

        BusyChild(long busyNanos) {
            this.busyNanos = busyNanos;
        }

        @Override
        public void dispose() {
            long start = System.nanoTime();
            while (System.nanoTime() - start < busyNanos) {
                // Busy-wait.
            }
        }
    }

    private static class CountingDisposable implements Disposable {
        int disposeCount;

        @Override
        public void dispose() {
            disposeCount++;
        }
    }

    private static final class EqualDisposable extends CountingDisposable {
        private final int id;

        private EqualDisposable(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof EqualDisposable other && id == other.id;
        }

        @Override
        public int hashCode() {
            return id;
        }
    }
}
