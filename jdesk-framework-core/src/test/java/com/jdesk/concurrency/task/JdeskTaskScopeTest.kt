package com.jdesk.concurrency.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class JdeskTaskScopeTest {

    @Test
    fun criticalTaskFailureCancelsSiblingTasks() = runBlocking {
        val siblingCancelled = CompletableDeferred<Unit>()

        JdeskTaskScope("fail-fast-task").use { scope ->
            val sibling = scope.launchDefault("sibling") {
                try {
                    delay(Long.MAX_VALUE)
                } finally {
                    siblingCancelled.complete(Unit)
                }
            }
            val failing = scope.asyncDefault("failing") {
                delay(50)
                error("boom")
            }

            val failure = runCatching { scope.await(failing) }.exceptionOrNull()

            assertInstanceOf(IllegalStateException::class.java, failure)
            assertEquals("boom", failure?.message)
            withTimeout(1_000) {
                siblingCancelled.await()
            }
            assertTrue(sibling.isCancelled)
        }
    }

    @Test
    fun backgroundTaskFailureDoesNotCancelSiblingBackgroundTasks() = runBlocking {
        val siblingStillRunning = CompletableDeferred<Unit>()

        JdeskTaskScope("supervised-background").use { scope ->
            scope.launchBackgroundDefault("failing-background") {
                error("background boom")
            }
            val sibling = scope.launchBackgroundDefault("sibling-background") {
                delay(150)
                siblingStillRunning.complete(Unit)
            }

            withTimeout(1_000) {
                siblingStillRunning.await()
            }
            assertTrue(sibling.isCompleted)
        }
    }

    @Test
    fun edtTasksRunOnSwingEventDispatchThread() = runBlocking(Dispatchers.Default) {
        JdeskTaskScope("edt-task").use { scope ->
            val result = scope.asyncEdt("edt-check") {
                SwingUtilities.isEventDispatchThread()
            }

            assertTrue(withTimeout(10_000) { scope.await(result) })
        }
    }
}
