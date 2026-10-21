package com.jdesk.core.app;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationTaskScopeTest {

    @Test
    void launchDefaultRunsJavaRunnableAndRecordsCompletion() throws Exception {
        ApplicationTaskScope scope = new ApplicationTaskScope("test-app");
        CountDownLatch ran = new CountDownLatch(1);

        try {
            ApplicationTask<Void> task = scope.launchDefault("java-runnable", ran::countDown);

            assertTrue(ran.await(2, TimeUnit.SECONDS));
            assertNull(task.await());

            assertEquals("java-runnable", task.getName());
            assertEquals(ApplicationTaskStatus.COMPLETED, task.getStatus());
            assertTrue(task.isDone());
            assertNull(task.getFailure());
            assertTrue(task.asFuture().isDone());
            // A finished task leaves the scope, which then holds neither its handle nor its result.
            assertTrue(scope.getTasksSnapshot().isEmpty());
        } finally {
            scope.cancelAll();
        }
    }

    @Test
    void asyncIoReturnsJavaCallableResult() throws Exception {
        ApplicationTaskScope scope = new ApplicationTaskScope("test-app");

        try {
            ApplicationTask<String> task = scope.asyncIo("java-callable", () -> "done");

            assertEquals("done", task.await());
            assertEquals(ApplicationTaskStatus.COMPLETED, task.getStatus());
            assertEquals("done", task.asFuture().get(1, TimeUnit.SECONDS));
        } finally {
            scope.cancelAll();
        }
    }

    @Test
    void failedTaskRecordsFailureWithoutCancellingScope() throws Exception {
        ApplicationTaskScope scope = new ApplicationTaskScope("test-app");
        RuntimeException failure = new RuntimeException("boom");

        try {
            ApplicationTask<Void> failed = scope.launchDefault("failing-task", () -> {
                throw failure;
            });
            RuntimeException thrown = assertThrows(RuntimeException.class, failed::await);
            assertSame(failure, thrown);
            assertEquals(ApplicationTaskStatus.FAILED, failed.getStatus());
            assertSame(failure, failed.getFailure());
            assertFalse(scope.getTasksSnapshot().contains(failed));

            ApplicationTask<String> healthy = scope.asyncDefault("healthy-task", () -> "still-running");
            assertEquals("still-running", healthy.await());
            assertEquals(ApplicationTaskStatus.COMPLETED, healthy.getStatus());
            assertFalse(scope.getTasksSnapshot().contains(healthy));
        } finally {
            scope.cancelAll();
        }
    }

    @Test
    void cancelAllCancelsUnfinishedInterruptibleTasks() throws Exception {
        ApplicationTaskScope scope = new ApplicationTaskScope("test-app");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        ApplicationTask<Void> task = scope.launchIo("blocking-task", () -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(started.await(2, TimeUnit.SECONDS));
        scope.cancelAll();

        assertTrue(task.asFuture().isCancelled());
        assertEquals(ApplicationTaskStatus.CANCELLED, task.getStatus());
        // cancel() sets the status and the future itself; this latch proves the blocked thread was really interrupted.
        assertTrue(interrupted.await(2, TimeUnit.SECONDS), "cancelling should interrupt the blocked task thread");
    }

    @Test
    void submitAfterCancelAllThrowsIllegalState() {
        ApplicationTaskScope scope = new ApplicationTaskScope("test-app");

        scope.cancelAll();

        assertThrows(IllegalStateException.class, () -> scope.launchDefault("late-task", () -> {}));
        assertThrows(IllegalStateException.class, () -> scope.asyncIo("late-async", () -> "nope"));
    }

    @Test
    void submitRacingCancelAllNeverSilentlyLosesTask() throws Exception {
        ApplicationTaskScope scope = new ApplicationTaskScope("test-app");
        int submissions = 64;
        CountDownLatch ready = new CountDownLatch(submissions);
        java.util.List<ApplicationTask<Void>> submitted = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<RuntimeException> rejected = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch done = new CountDownLatch(submissions);

        for (int i = 0; i < submissions; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    submitted.add(scope.launchIo("race-task", () -> {}));
                } catch (RuntimeException e) {
                    rejected.add(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS));
        scope.cancelAll();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        // Each submission is either rejected or finishes; none is silently left RUNNING under the cancelled root job.
        assertEquals(submissions, submitted.size() + rejected.size());
        for (ApplicationTask<Void> task : submitted) {
            // Wait for the future before checking the status: isDone turns true before the status is updated.
            task.asFuture().handle((value, failure) -> null).get(5, TimeUnit.SECONDS);
            assertNotEquals(ApplicationTaskStatus.RUNNING, task.getStatus(),
                    "task should reach terminal state: " + task.getName());
        }
    }

    /** Callbacks on task futures run outside the submit lock, so one can wait for another thread's submission. */
    @Test
    void cancelAllRunsTaskCallbacksOutsideSubmitLock() throws Exception {
        ApplicationTaskScope scope = new ApplicationTaskScope("test-app");
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean submitterFinished = new AtomicBoolean();
        ApplicationTask<Void> task = scope.launchIo("blocked-task", () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        task.asFuture().whenComplete((value, failure) -> {
            Thread submitter = new Thread(() -> {
                try {
                    scope.launchIo("late-task", () -> {});
                } catch (IllegalStateException expected) {
                    // The scope is closed, so the rejection is expected.
                }
            });
            submitter.start();
            try {
                submitter.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            submitterFinished.set(!submitter.isAlive());
        });

        try {
            scope.cancelAll();
            assertTrue(submitterFinished.get(), "the submitter was blocked: the callback ran inside the submit lock");
        } finally {
            release.countDown();
        }
    }
}
