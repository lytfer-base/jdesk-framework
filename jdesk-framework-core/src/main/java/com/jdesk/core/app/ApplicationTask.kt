package com.jdesk.core.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference

/**
 * Java-friendly handle of a task submitted to [ApplicationTaskScope]: the work runs in a coroutine {@link Deferred},
 * and a {@link CompletableFuture} lets Java callers block or chain. The handle is thread-safe and its status moves
 * once from {@code RUNNING} to {@code COMPLETED}, {@code FAILED} or {@code CANCELLED}; repeated cancels or
 * completions are no-ops. It holds no reference to its scope.
 */
class ApplicationTask<T> internal constructor(
    private val taskName: String,
    private val deferred: Deferred<T>,
    private val future: CompletableFuture<T>,
    private val statusRef: AtomicReference<ApplicationTaskStatus>,
    private val failureRef: AtomicReference<Throwable?>,
) {
    val name: String
        get() = taskName

    val status: ApplicationTaskStatus
        get() = statusRef.get()

    /** Returns whether the task has finished, as reported first by its status, its future or its coroutine. */
    val isDone: Boolean
        get() = status != ApplicationTaskStatus.RUNNING || future.isDone || deferred.isCompleted

    /** Returns the first failure of the task, a cancellation included, or {@code null} if there is none. */
    val failure: Throwable?
        get() = failureRef.get()

    /** Cancels a running task together with its future and coroutine; returns false if it had already finished. */
    fun cancel(): Boolean {
        val cancelled = statusRef.compareAndSet(
            ApplicationTaskStatus.RUNNING,
            ApplicationTaskStatus.CANCELLED,
        )
        if (!cancelled) {
            return false
        }

        val cancellation = CancellationException("Application task cancelled: $taskName")
        failureRef.compareAndSet(null, cancellation)
        future.cancel(true)
        deferred.cancel(cancellation)
        return true
    }

    /**
     * Blocks until the task finishes and returns its result, rethrowing the exception the task threw; a throwable
     * that is neither an Error nor an Exception arrives wrapped in RuntimeException.
     *
     * @throws InterruptedException if the waiting thread is interrupted; the interrupt flag is restored
     */
    @Throws(Exception::class)
    fun await(): T {
        try {
            return future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            when (cause) {
                is Error -> throw cause
                is Exception -> throw cause
                else -> throw RuntimeException(cause)
            }
        }
    }

    /** Returns the underlying future, for chaining and callbacks from Java. */
    fun asFuture(): CompletableFuture<T> = future

    /**
     * Called by [ApplicationTaskScope] when the coroutine ends with {@code cause} ({@code null} on success): success
     * completes the future, a CancellationException marks the task cancelled, and any other failure marks it failed
     * and goes to {@code onFailure} once. {@code onFailure} runs synchronously; don't do slow work in it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun complete(cause: Throwable?, onFailure: (Throwable) -> Unit) {
        when {
            cause == null -> {
                if (statusRef.compareAndSet(
                        ApplicationTaskStatus.RUNNING,
                        ApplicationTaskStatus.COMPLETED,
                    )
                ) {
                    future.complete(deferred.getCompleted())
                }
            }

            cause is CancellationException -> {
                markCancelled(cause)
            }

            statusRef.compareAndSet(
                ApplicationTaskStatus.RUNNING,
                ApplicationTaskStatus.FAILED,
            ) -> {
                val failure = failureRef.get() ?: cause
                failureRef.compareAndSet(null, failure)
                onFailure(failure)
                future.completeExceptionally(failure)
            }
        }
    }

    private fun markCancelled(cause: CancellationException) {
        if (statusRef.compareAndSet(
                ApplicationTaskStatus.RUNNING,
                ApplicationTaskStatus.CANCELLED,
            )
        ) {
            failureRef.compareAndSet(null, cause)
            future.cancel(true)
        }
    }
}
