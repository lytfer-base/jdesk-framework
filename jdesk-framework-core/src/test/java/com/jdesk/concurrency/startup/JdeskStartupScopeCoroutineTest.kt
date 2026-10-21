package com.jdesk.concurrency.startup

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

class JdeskStartupScopeCoroutineTest {

    @Test
    fun criticalTaskFailureCancelsSiblingTasks() = runBlocking {
        val siblingCancelled = CompletableDeferred<Unit>()

        JdeskStartupScope("fail-fast-startup").use { scope ->
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
    fun closeCancelsBackgroundScope() = runBlocking {
        val backgroundCancelled = CompletableDeferred<Unit>()
        val scope = JdeskStartupScope("background-startup")

        scope.launchBackgroundDefault("background-worker") {
            try {
                delay(Long.MAX_VALUE)
            } finally {
                backgroundCancelled.complete(Unit)
            }
        }
        scope.close()

        withTimeout(1_000) {
            backgroundCancelled.await()
        }
    }

    @Test
    fun edtTasksRunOnSwingEventDispatchThread() = runBlocking(Dispatchers.Default) {
        JdeskStartupScope("edt-startup").use { scope ->
            val result = scope.asyncEdt("edt-check") {
                SwingUtilities.isEventDispatchThread()
            }

            assertTrue(withTimeout(10_000) { scope.await(result) })
        }
    }
}
