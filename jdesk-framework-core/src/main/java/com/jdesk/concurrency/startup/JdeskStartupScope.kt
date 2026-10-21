package com.jdesk.concurrency.startup

import com.jdesk.concurrency.task.JdeskTaskScope
import com.jdesk.concurrency.task.TaskDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import java.util.concurrent.Callable

/**
 * Startup-specialized facade over [JdeskTaskScope].
 *
 * Startup code keeps the explicit startup naming and Java-compatible
 * [StartupDeferred] wrapper, while the reusable coroutine task mechanics live
 * in the generic task package.
 */
class JdeskStartupScope @JvmOverloads constructor(
    name: String = "jdesk-startup",
) : AutoCloseable {

    private val taskScope = JdeskTaskScope(name)

    fun <T> asyncIo(name: String, action: Callable<T>): StartupDeferred<T> {
        return StartupDeferred(taskScope.asyncIo(name, action))
    }

    fun <T> asyncDefault(name: String, action: Callable<T>): StartupDeferred<T> {
        return StartupDeferred(taskScope.asyncDefault(name, action))
    }

    fun launchDefault(name: String, action: suspend CoroutineScope.() -> Unit): Job {
        return taskScope.launchDefault(name, action)
    }

    fun launchIo(name: String, action: suspend CoroutineScope.() -> Unit): Job {
        return taskScope.launchIo(name, action)
    }

    fun <T> asyncDefault(name: String, action: suspend CoroutineScope.() -> T): Deferred<T> {
        return taskScope.asyncDefault(name, action)
    }

    fun <T> asyncIo(name: String, action: suspend CoroutineScope.() -> T): Deferred<T> {
        return taskScope.asyncIo(name, action)
    }

    fun <T> asyncEdt(name: String, action: suspend CoroutineScope.() -> T): Deferred<T> {
        return taskScope.asyncEdt(name, action)
    }

    fun launchBackgroundDefault(name: String, action: suspend CoroutineScope.() -> Unit): Job {
        return taskScope.launchBackgroundDefault(name, action)
    }

    fun launchBackgroundIo(name: String, action: suspend CoroutineScope.() -> Unit): Job {
        return taskScope.launchBackgroundIo(name, action)
    }

    fun launchBackgroundDefault(name: String, action: Callable<*>): Job {
        return taskScope.launchBackgroundDefault(name, action)
    }

    fun launchBackgroundIo(name: String, action: Callable<*>): Job {
        return taskScope.launchBackgroundIo(name, action)
    }

    @Throws(Exception::class)
    fun <T> await(task: StartupDeferred<T>): T {
        return taskScope.await(task.task)
    }

    suspend fun <T> await(task: Deferred<T>): T {
        return taskScope.await(task)
    }

    suspend fun await(job: Job) {
        taskScope.await(job)
    }

    @Throws(Exception::class)
    fun <T> awaitAll(tasks: Collection<StartupDeferred<T>>): List<T> {
        return taskScope.awaitAll(tasks.map { it.task })
    }

    suspend fun <T> awaitDeferredAll(tasks: Collection<Deferred<T>>): List<T> {
        return taskScope.awaitDeferredAll(tasks)
    }

    fun cancel() {
        taskScope.cancel()
    }

    override fun close() {
        taskScope.close()
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun <T> runBlockingStartup(name: String, action: Callable<T>): T {
            return JdeskTaskScope.runBlockingTask(name, action)
        }
    }
}

class StartupDeferred<T> internal constructor(
    internal val task: TaskDeferred<T>,
) {
    val name: String = task.name
}
