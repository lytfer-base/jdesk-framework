package com.jdesk.concurrency.task

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable

/**
 * Coroutine-backed scope for named framework tasks.
 *
 * Critical tasks are fail-fast: a failed child cancels sibling critical work.
 * Background tasks are supervised, so non-critical failures do not cancel other
 * background work or the owner by themselves.
 */
class JdeskTaskScope @JvmOverloads constructor(
    name: String = "jdesk-task",
) : AutoCloseable {

    private val criticalJob = Job()
    private val backgroundJob = SupervisorJob()
    private val backgroundExceptionHandler = CoroutineExceptionHandler { context, throwable ->
        // A failure doesn't cancel siblings but must be logged, or a launch nobody awaits fails silently.
        val taskName = context[CoroutineName]?.name ?: "unnamed"
        LOG.error("Background task failed: {}", taskName, throwable)
    }
    private val scope = CoroutineScope(criticalJob + CoroutineName(name))
    private val backgroundScope = CoroutineScope(
        backgroundJob + CoroutineName("$name-background") + backgroundExceptionHandler,
    )

    fun <T> asyncIo(name: String, action: Callable<T>): TaskDeferred<T> {
        val deferred = scope.async(Dispatchers.IO + CoroutineName(name)) {
            action.call()
        }
        return TaskDeferred(name, deferred)
    }

    fun <T> asyncDefault(name: String, action: Callable<T>): TaskDeferred<T> {
        val deferred = scope.async(Dispatchers.Default + CoroutineName(name)) {
            action.call()
        }
        return TaskDeferred(name, deferred)
    }

    fun launchDefault(name: String, action: suspend CoroutineScope.() -> Unit): Job {
        return scope.launch(Dispatchers.Default + CoroutineName(name)) {
            action()
        }
    }

    fun launchIo(name: String, action: suspend CoroutineScope.() -> Unit): Job {
        return scope.launch(Dispatchers.IO + CoroutineName(name)) {
            action()
        }
    }

    fun <T> asyncDefault(name: String, action: suspend CoroutineScope.() -> T): Deferred<T> {
        return scope.async(Dispatchers.Default + CoroutineName(name)) {
            action()
        }
    }

    fun <T> asyncIo(name: String, action: suspend CoroutineScope.() -> T): Deferred<T> {
        return scope.async(Dispatchers.IO + CoroutineName(name)) {
            action()
        }
    }

    fun <T> asyncEdt(name: String, action: suspend CoroutineScope.() -> T): Deferred<T> {
        return scope.async(Dispatchers.Swing + CoroutineName(name)) {
            action()
        }
    }

    fun launchBackgroundDefault(name: String, action: suspend CoroutineScope.() -> Unit): Job {
        return backgroundScope.launch(Dispatchers.Default + CoroutineName(name)) {
            action()
        }
    }

    fun launchBackgroundIo(name: String, action: suspend CoroutineScope.() -> Unit): Job {
        return backgroundScope.launch(Dispatchers.IO + CoroutineName(name)) {
            action()
        }
    }

    fun launchBackgroundDefault(name: String, action: Callable<*>): Job {
        return backgroundScope.launch(Dispatchers.Default + CoroutineName(name)) {
            action.call()
        }
    }

    fun launchBackgroundIo(name: String, action: Callable<*>): Job {
        return backgroundScope.launch(Dispatchers.IO + CoroutineName(name)) {
            action.call()
        }
    }

    @Throws(Exception::class)
    fun <T> await(task: TaskDeferred<T>): T = runBlocking {
        task.deferred.await()
    }

    suspend fun <T> await(task: Deferred<T>): T = task.await()

    suspend fun await(job: Job) {
        job.join()
    }

    @Throws(Exception::class)
    fun <T> awaitAll(tasks: Collection<TaskDeferred<T>>): List<T> = runBlocking {
        tasks.map { it.deferred.await() }
    }

    suspend fun <T> awaitDeferredAll(tasks: Collection<Deferred<T>>): List<T> {
        return tasks.map { it.await() }
    }

    fun cancel() {
        scope.cancel()
        backgroundScope.cancel()
    }

    override fun close() {
        cancel()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(JdeskTaskScope::class.java)

        @JvmStatic
        @Throws(Exception::class)
        fun <T> runBlockingTask(name: String, action: Callable<T>): T = runBlocking(CoroutineName(name)) {
            action.call()
        }
    }
}

class TaskDeferred<T> internal constructor(
    val name: String,
    internal val deferred: Deferred<T>,
)
