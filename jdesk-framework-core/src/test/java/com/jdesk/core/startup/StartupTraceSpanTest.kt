package com.jdesk.core.startup

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StartupTraceSpanTest {

    @Test
    fun spanRecordsSuccessAndReturnsValue() {
        val trace = StartupTrace.report()

        val result = trace.span("app info") {
            detail("source", "shadow")
            "loaded"
        }

        assertEquals("loaded", result)
        assertNull(trace.currentSpanId)
        val event = trace.events.single()
        assertEquals("app info", event.name)
        assertEquals("OK", event.status)
        assertEquals("shadow", event.details["source"])
    }

    @Test
    fun spanRecordsFailureAndRethrows() {
        val trace = StartupTrace.report()

        val failure = assertThrows(IllegalStateException::class.java) {
            trace.span("broken span") {
                error("boom")
            }
        }

        assertEquals("boom", failure.message)
        assertNull(trace.currentSpanId)
        val event = trace.events.single()
        assertEquals("broken span", event.name)
        assertEquals("ERROR", event.status)
        assertEquals("java.lang.IllegalStateException: boom", event.error)
    }

    @Test
    fun suspendChildSpanUsesExplicitParent() = runBlocking {
        val trace = StartupTrace.report()

        trace.suspendSpan("root") {
            val parentId = id
            trace.suspendChildSpan(parentId, "async child") {
                detail("thread", Thread.currentThread().name)
            }
        }

        val root = trace.events.single { it.name == "root" }
        val child = trace.events.single { it.name == "async child" }
        assertEquals(root.id, child.parentId)
        assertEquals("OK", child.status)
    }

    @Test
    fun suspendSpanPropagatesParentAcrossDispatchers() = runBlocking {
        val trace = StartupTrace.report()

        trace.suspendSpan("root") {
            withContext(Dispatchers.Default) {
                trace.suspendSpan("child") {
                    detail("dispatcher", "default")
                }
            }
        }

        val root = trace.events.single { it.name == "root" }
        val child = trace.events.single { it.name == "child" }
        assertEquals(root.id, child.parentId)
        assertEquals("OK", child.status)
    }
}
