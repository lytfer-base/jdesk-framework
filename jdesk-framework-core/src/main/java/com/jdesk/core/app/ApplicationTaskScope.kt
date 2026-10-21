package com.jdesk.core.app

import com.jdesk.core.i18n.LocalizedException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.swing.Swing
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Background task scope bound to the application lifecycle and cancelled as a whole on shutdown or startup rollback.
 * Tasks are supervised: a failure is recorded on its {@link ApplicationTask} handle and logged without cancelling
 * sibling tasks. A finished task leaves the scope at once, so the scope never holds its handle or result.
 * Use {@code Default} for CPU work, {@code IO} for interruptible blocking I/O and {@code Swing} for EDT work.
 */
class ApplicationTaskScope @JvmOverloads constructor(
    /** Names the coroutines and identifies the scope in logs. */
    private val scopeName: String = "application",
) : AutoCloseable {

    private val rootJob: Job = SupervisorJob()

    private val coroutineScope = CoroutineScope(rootJob + CoroutineName(scopeName))

    private val acceptingTasks = AtomicBoolean(true)

    /**
     * Makes the check, launch and register steps of [submit] atomic with the close and snapshot steps of [cancelAll].
     * Don't cancel tasks while holding it: cancellation runs the callbacks attached to the task futures synchronously.
     */
    private val submitLock = Any()

    /** Tasks submitted and not yet finished; the completion callback removes each one. */
    private val tasks = CopyOnWriteArrayList<ApplicationTask<*>>()

    /** Returns a copy of the tasks that haven't finished yet; finished tasks drop out at once. */
    val tasksSnapshot: List<ApplicationTask<*>>
        get() = tasks.toList()

    /** Starts a non-interruptible task without a result on the default pool, for CPU-bound work. */
    fun launchDefault(name: String, action: Runnable): ApplicationTask<Void?> {
        return submit(name, Dispatchers.Default, interruptible = false) {
            action.run()
            null
        }
    }

    /** Starts a task without a result on the I/O pool, for blocking I/O; cancelling it interrupts the thread. */
    fun launchIo(name: String, action: Runnable): ApplicationTask<Void?> {
        return submit(name, Dispatchers.IO, interruptible = true) {
            action.run()
            null
        }
    }

    /** Starts a non-interruptible task without a result on the EDT, for UI updates; don't do slow work there. */
    fun launchEdt(name: String, action: Runnable): ApplicationTask<Void?> {
        return submit(name, Dispatchers.Swing, interruptible = false) {
            action.run()
            null
        }
    }

    /** Starts a non-interruptible task with a result on the default pool, for CPU-bound work. */
    fun <T> asyncDefault(name: String, action: Callable<T>): ApplicationTask<T> {
        return submit(name, Dispatchers.Default, interruptible = false) {
            action.call()
        }
    }

    /** Starts a task with a result on the I/O pool, for blocking I/O; cancelling it interrupts the thread. */
    fun <T> asyncIo(name: String, action: Callable<T>): ApplicationTask<T> {
        return submit(name, Dispatchers.IO, interruptible = true) {
            action.call()
        }
    }

    /**
     * Cancels every submitted task and rejects later {@code launch*} and {@code async*} calls with
     * IllegalStateException; calling it again does nothing.
     */
    fun cancelAll() {
        val pending: List<ApplicationTask<*>>
        synchronized(submitLock) {
            if (!acceptingTasks.getAndSet(false)) {
                return
            }
            pending = tasks.toList()
        }
        // Cancellation runs future callbacks synchronously; don't cancel while holding submitLock.
        pending.forEach { task ->
            task.cancel()
        }
        rootJob.cancel("Application task scope cancelled: $scopeName")
    }

    override fun close() {
        cancelAll()
    }

    /**
     * Launches {@code action} on {@code dispatcher}. A task is either registered before [cancelAll] takes its snapshot,
     * and is then cancelled by it, or rejected with IllegalStateException; it is never lost silently.
     */
    private fun <T> submit(
        name: String,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        interruptible: Boolean,
        action: Callable<T>,
    ): ApplicationTask<T> {
        val deferred: Deferred<T>
        val task: ApplicationTask<T>
        synchronized(submitLock) {
            ensureAcceptingTasks()

            val statusRef = AtomicReference(ApplicationTaskStatus.RUNNING)
            val failureRef = AtomicReference<Throwable?>(null)
            val future = CompletableFuture<T>()
            deferred = coroutineScope.async(dispatcher + CoroutineName(name)) {
                if (interruptible) {
                    runInterruptible {
                        callTaskAction(action, failureRef)
                    }
                } else {
                    callTaskAction(action, failureRef)
                }
            }
            task = ApplicationTask(name, deferred, future, statusRef, failureRef)
            tasks.add(task)
        }
        deferred.invokeOnCompletion { cause ->
            // Remove before completing, so the scope no longer holds the task once a consumer sees its result.
            tasks.remove(task)
            task.complete(cause) { failure ->
                val rejection = LocalizedException.rejectionOf(failure)
                if (rejection != null) {
                    LOG.warn("Application task rejected: {} reason={} message={}", name, rejection.reason(), rejection.message)
                } else {
                    LOG.error("Application task failed: {}", name, failure)
                }
            }
        }
        return task
    }

    private fun ensureAcceptingTasks() {
        if (!acceptingTasks.get()) {
            throw IllegalStateException("ApplicationTaskScope is cancelled: $scopeName")
        }
    }

    private fun <T> callTaskAction(
        action: Callable<T>,
        failureRef: AtomicReference<Throwable?>,
    ): T {
        try {
            return action.call()
        } catch (failure: Throwable) {
            failureRef.compareAndSet(null, failure)
            throw failure
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(ApplicationTaskScope::class.java)
    }
}
