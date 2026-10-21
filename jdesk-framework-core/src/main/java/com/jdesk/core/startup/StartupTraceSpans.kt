@file:JvmName("StartupTraceSpans")

package com.jdesk.core.startup

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Kotlin-friendly startup tracing helpers.
 */
inline fun <T> StartupTrace.span(
    name: String,
    details: Map<String, String> = emptyMap(),
    action: StartupSpan.() -> T,
): T {
    return begin(name, details).runSpanAction(action)
}

inline fun <T> StartupTrace.childSpan(
    parentId: Int?,
    name: String,
    details: Map<String, String> = emptyMap(),
    action: StartupSpan.() -> T,
): T {
    return beginChild(parentId, name, details).runSpanAction(action)
}

suspend fun <T> StartupTrace.suspendSpan(
    name: String,
    details: Map<String, String> = emptyMap(),
    action: suspend StartupSpan.() -> T,
): T {
    val span = begin(name, details)
    if (span.id < 0) {
        return span.runSuspendSpanAction(action)
    }
    return withContext(StartupSpanCoroutineContext(this, span.id)) {
        span.runSuspendSpanAction(action)
    }
}

suspend fun <T> StartupTrace.suspendChildSpan(
    parentId: Int?,
    name: String,
    details: Map<String, String> = emptyMap(),
    action: suspend StartupSpan.() -> T,
): T {
    val span = beginChild(parentId, name, details)
    if (span.id < 0) {
        return span.runSuspendSpanAction(action)
    }
    return withContext(StartupSpanCoroutineContext(this, span.id)) {
        span.runSuspendSpanAction(action)
    }
}

inline fun <T> StartupSpan.runSpanAction(action: StartupSpan.() -> T): T {
    try {
        return action()
    } catch (failure: Throwable) {
        failed(failure)
        throw failure
    } finally {
        close()
    }
}

suspend fun <T> StartupSpan.runSuspendSpanAction(
    action: suspend StartupSpan.() -> T,
): T {
    try {
        return action()
    } catch (failure: Throwable) {
        failed(failure)
        throw failure
    } finally {
        close()
    }
}

private class StartupSpanCoroutineContext(
    private val trace: StartupTrace,
    private val spanId: Int,
) : ThreadContextElement<Int?>,
    AbstractCoroutineContextElement(StartupSpanCoroutineContext) {

    override fun updateThreadContext(context: CoroutineContext): Int? {
        return trace.installCurrentSpanForCoroutine(spanId)
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Int?) {
        trace.restoreCurrentSpanForCoroutine(oldState)
    }

    companion object Key : CoroutineContext.Key<StartupSpanCoroutineContext>
}
