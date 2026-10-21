package com.jdesk.core.service.impl

import com.jdesk.core.app.ApplicationTaskScope
import com.jdesk.core.app.ApplicationTaskStatus
import com.jdesk.core.app.ApplicationManager
import com.jdesk.core.app.TestApplication
import com.jdesk.core.disposer.Disposer
import com.jdesk.core.service.CoroutineServiceContainer
import com.jdesk.core.service.ServiceDescriptor
import com.jdesk.core.service.ServiceInitializationCycleException
import com.jdesk.core.service.ServicePreloadMode
import com.jdesk.core.service.getServiceAsync
import com.jdesk.core.startup.StartupTrace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ServiceContainerImplAsyncTest {

    companion object {
        @Volatile
        var mutualContainer: ServiceContainerImpl? = null

        @Volatile
        var mutualLatchA: CountDownLatch? = null

        @Volatile
        var mutualLatchB: CountDownLatch? = null

        @Volatile
        var ringLatchB: CountDownLatch? = null

        @Volatile
        var ringLatchC: CountDownLatch? = null
    }

    @AfterEach
    fun clearState() {
        ApplicationManager.getApplicationIfCreated()?.shutdown()
        Disposer.clearAll()
        ConcurrentAsyncService.creationCount.set(0)
        AsyncPreloadService.releaseIfInitialized()
        TracePreloadService.releaseIfInitialized()
        RetryAsyncService.reset()
        mutualContainer = null
        mutualLatchA = null
        mutualLatchB = null
        ringLatchB = null
        ringLatchC = null
    }

    @Test
    fun coroutineServiceContainerExposesNativeAsyncAccess() = runBlocking {
        val container: CoroutineServiceContainer = ServiceContainerImpl()
        container.registerService(
            ConcurrentAsyncService::class.java,
            ServiceDescriptor.ofClass(ConcurrentAsyncService::class.java, ConcurrentAsyncService::class.java),
        )

        val service = container.getServiceAsync(ConcurrentAsyncService::class.java)

        assertSame(service, container.getServiceIfCreated(ConcurrentAsyncService::class.java))
    }

    @Test
    fun getServiceAsyncCreatesLazyServiceOnlyOnceUnderConcurrentCalls() = runBlocking {
        val container = ServiceContainerImpl()
        container.registerService(
            ConcurrentAsyncService::class.java,
            ServiceDescriptor.ofClass(ConcurrentAsyncService::class.java, ConcurrentAsyncService::class.java),
        )

        val services = coroutineScope {
            (1..16).map {
                async(Dispatchers.Default) {
                    container.getServiceAsync(ConcurrentAsyncService::class.java)
                }
            }.awaitAll()
        }

        val first = services.first()
        services.forEach { assertSame(first, it) }
        assertEquals(1, ConcurrentAsyncService.creationCount.get())
    }

    @Test
    fun initializeAsyncAwaitsAwaitPreloadAndStartsAsyncPreloadInBackground() = runBlocking {
        AsyncPreloadService.reset()
        val taskScope = ApplicationTaskScope("test-app")
        val container = ServiceContainerImpl(taskScope)
        container.registerService(
            AwaitPreloadService::class.java,
            ServiceDescriptor.builder(AwaitPreloadService::class.java)
                .implementationClass(AwaitPreloadService::class.java)
                .preload(ServicePreloadMode.AWAIT)
                .build(),
        )
        container.registerService(
            AsyncPreloadService::class.java,
            ServiceDescriptor.builder(AsyncPreloadService::class.java)
                .implementationClass(AsyncPreloadService::class.java)
                .preload(ServicePreloadMode.ASYNC)
                .build(),
        )

        container.initializeAsync()

        assertNotNull(container.getServiceIfCreated(AwaitPreloadService::class.java))
        withTimeout(1_000) {
            AsyncPreloadService.started.await()
        }
        assertFalse(AsyncPreloadService.finished.isCompleted)
        assertEquals(1, taskScope.tasksSnapshot.size)
        assertEquals(ApplicationTaskStatus.RUNNING, taskScope.tasksSnapshot.single().status)

        AsyncPreloadService.release.countDown()
        withTimeout(1_000) {
            AsyncPreloadService.finished.await()
        }
    }

    @Test
    fun asyncPreloadServiceCreationKeepsStartupTraceParentSpan() = runBlocking {
        TracePreloadService.reset()
        val trace = StartupTrace.report()
        val app = TestApplication(trace)
        app.init()
        val container = app.serviceContainer

        trace.runSpan("test.preload.parent") {
            container.registerService(
                TracePreloadService::class.java,
                ServiceDescriptor.builder(TracePreloadService::class.java)
                    .implementationClass(TracePreloadService::class.java)
                    .preload(ServicePreloadMode.ASYNC)
                    .build(),
            )
        }

        withTimeout(1_000) {
            TracePreloadService.started.await()
        }
        TracePreloadService.release.countDown()
        withTimeout(1_000) {
            TracePreloadService.finished.await()
        }

        val events = trace.events
        val parent = events.single { it.name == "test.preload.parent" }
        val serviceCreate = events.single { it.name == "service.create:${TracePreloadService::class.java.name}" }
        assertEquals(parent.id, serviceCreate.parentId)

        app.shutdown()
    }

    @Test
    fun failedAsyncCreationIsClearedSoNextAccessCanRetry() = runBlocking {
        RetryAsyncService.reset()
        val container: CoroutineServiceContainer = ServiceContainerImpl()
        container.registerService(
            RetryAsyncService::class.java,
            ServiceDescriptor.ofClass(RetryAsyncService::class.java, RetryAsyncService::class.java),
        )

        assertThrows<IllegalStateException> {
            runBlocking {
                container.getServiceAsync(RetryAsyncService::class.java)
            }
        }

        val service = container.getServiceAsync(RetryAsyncService::class.java)

        assertNotNull(service)
        assertEquals(2, RetryAsyncService.attempts.get())
    }

    @Test
    fun serviceInitializationCycleFailsFast() {
        val app = TestApplication()
        app.init()
        val container = app.serviceContainer
        container.registerService(
            CycleAService::class.java,
            ServiceDescriptor.ofClass(CycleAService::class.java, CycleAService::class.java),
        )
        container.registerService(
            CycleBService::class.java,
            ServiceDescriptor.ofClass(CycleBService::class.java, CycleBService::class.java),
        )

        val failure = assertThrows<ServiceInitializationCycleException> {
            container.getService(CycleAService::class.java)
        }

        assertTrue(failure.message!!.contains(CycleAService::class.java.name))
        assertTrue(failure.message!!.contains(CycleBService::class.java.name))
        app.shutdown()
    }

    /**
     * Two mutually dependent `preload=ASYNC` services created at once on the IO pool must fail fast with
     * ServiceInitializationCycleException, found on the wait graph, instead of deadlocking on `future.get()`.
     */
    @Test
    @Timeout(30)
    fun asyncPreloadMutualDependencyFailsFastWithCycleException() {
        val container = ServiceContainerImpl()
        mutualContainer = container
        try {
            container.registerService(
                MutualAsyncA::class.java,
                ServiceDescriptor.builder(MutualAsyncA::class.java)
                    .implementationClass(MutualAsyncA::class.java)
                    .preload(ServicePreloadMode.ASYNC)
                    .build(),
            )
            container.registerService(
                MutualAsyncB::class.java,
                ServiceDescriptor.builder(MutualAsyncB::class.java)
                    .implementationClass(MutualAsyncB::class.java)
                    .preload(ServicePreloadMode.ASYNC)
                    .build(),
            )

            container.initializeAsync()

            val failure = assertThrows<ServiceInitializationCycleException> {
                container.getService(MutualAsyncA::class.java)
            }
            assertTrue(failure.message!!.contains(MutualAsyncA::class.java.name))
            assertTrue(failure.message!!.contains(MutualAsyncB::class.java.name))
            assertNull(container.getServiceIfCreated(MutualAsyncA::class.java))
            assertNull(container.getServiceIfCreated(MutualAsyncB::class.java))
        } finally {
            mutualContainer = null
            container.dispose()
        }
    }

    /**
     * Two threads lazily creating mutually dependent services form the same cross-thread cycle; both must fail fast
     * instead of hanging. The latches line the two constructors up so that they run at once.
     */
    @Test
    @Timeout(30)
    fun concurrentLazyAccessOfMutuallyDependentServicesFailsFast() {
        val container = ServiceContainerImpl()
        mutualContainer = container
        mutualLatchA = CountDownLatch(1)
        mutualLatchB = CountDownLatch(1)
        try {
            container.registerService(
                LatchMutualA::class.java,
                ServiceDescriptor.ofClass(LatchMutualA::class.java, LatchMutualA::class.java),
            )
            container.registerService(
                LatchMutualB::class.java,
                ServiceDescriptor.ofClass(LatchMutualB::class.java, LatchMutualB::class.java),
            )

            val failures = java.util.Collections.synchronizedList(ArrayList<Throwable>())
            val threadA = Thread {
                try {
                    container.getService(LatchMutualA::class.java)
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
            }
            val threadB = Thread {
                try {
                    container.getService(LatchMutualB::class.java)
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
            }
            threadA.start()
            threadB.start()
            threadA.join(TimeUnit.SECONDS.toMillis(10))
            threadB.join(TimeUnit.SECONDS.toMillis(10))

            assertEquals(2, failures.size, "both threads must terminate with a failure instead of hanging")
            failures.forEach { failure ->
                assertTrue(failure is ServiceInitializationCycleException, "unexpected failure: $failure")
            }
        } finally {
            mutualContainer = null
            mutualLatchA = null
            mutualLatchB = null
            container.dispose()
        }
    }

    /**
     * In an A -> B -> C -> A ring, thread 1 creates B nested inside A while thread 2 creates C; both must fail fast
     * instead of hanging. The latches line up the B and C constructors so that they run at once.
     */
    @Test
    @Timeout(30)
    fun threeServiceRingWithNestedCreationFailsFastAcrossThreads() {
        val container = ServiceContainerImpl()
        mutualContainer = container
        ringLatchB = CountDownLatch(1)
        ringLatchC = CountDownLatch(1)
        try {
            container.registerService(RingA::class.java, ServiceDescriptor.ofClass(RingA::class.java, RingA::class.java))
            container.registerService(RingB::class.java, ServiceDescriptor.ofClass(RingB::class.java, RingB::class.java))
            container.registerService(RingC::class.java, ServiceDescriptor.ofClass(RingC::class.java, RingC::class.java))

            val failures = java.util.Collections.synchronizedList(ArrayList<Throwable>())
            val first = Thread {
                try {
                    container.getService(RingA::class.java)
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
            }
            val second = Thread {
                try {
                    container.getService(RingC::class.java)
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
            }
            first.start()
            second.start()
            first.join(TimeUnit.SECONDS.toMillis(10))
            second.join(TimeUnit.SECONDS.toMillis(10))

            assertEquals(2, failures.size, "both threads must terminate with a failure instead of hanging")
            failures.forEach { failure ->
                assertTrue(failure is ServiceInitializationCycleException, "unexpected failure: $failure")
            }
        } finally {
            mutualContainer = null
            ringLatchB = null
            ringLatchC = null
            container.dispose()
        }
    }

    @Test
    fun disposingContainerInterruptsInFlightAsyncPreload() {
        AsyncPreloadService.reset()
        // Don't use the no-arg constructor: dispose would cancel the container's own scope, hiding this step.
        val taskScope = ApplicationTaskScope("test-app")
        val container = ServiceContainerImpl(taskScope)
        try {
            container.registerService(
                AsyncPreloadService::class.java,
                ServiceDescriptor.builder(AsyncPreloadService::class.java)
                    .implementationClass(AsyncPreloadService::class.java)
                    .preload(ServicePreloadMode.ASYNC)
                    .build(),
            )

            container.initializeAsync()
            runBlocking {
                withTimeout(5_000) {
                    AsyncPreloadService.started.await()
                }
            }

            container.dispose()

            runBlocking {
                withTimeout(5_000) {
                    AsyncPreloadService.interrupted.await()
                }
            }
        } finally {
            taskScope.cancelAll()
        }
    }

    class AwaitPreloadService

    class TracePreloadService {
        companion object {
            lateinit var started: CompletableDeferred<Unit>
            lateinit var finished: CompletableDeferred<Unit>
            lateinit var release: CountDownLatch

            fun reset() {
                started = CompletableDeferred()
                finished = CompletableDeferred()
                release = CountDownLatch(1)
            }

            fun releaseIfInitialized() {
                if (::release.isInitialized) {
                    release.countDown()
                }
            }
        }

        init {
            started.complete(Unit)
            release.await(5, TimeUnit.SECONDS)
            finished.complete(Unit)
        }
    }

    class RetryAsyncService {
        companion object {
            val attempts = AtomicInteger()

            fun reset() {
                attempts.set(0)
            }
        }

        init {
            if (attempts.incrementAndGet() == 1) {
                throw IllegalStateException("first attempt fails")
            }
        }
    }

    class CycleAService {
        init {
            com.jdesk.core.app.ApplicationManager.getApplication().getService(CycleBService::class.java)
        }
    }

    class CycleBService {
        init {
            com.jdesk.core.app.ApplicationManager.getApplication().getService(CycleAService::class.java)
        }
    }

    class MutualAsyncA {
        init {
            mutualContainer!!.getService(MutualAsyncB::class.java)
        }
    }

    class MutualAsyncB {
        init {
            mutualContainer!!.getService(MutualAsyncA::class.java)
        }
    }

    class LatchMutualA {
        init {
            mutualLatchA!!.countDown()
            mutualLatchB!!.await(5, TimeUnit.SECONDS)
            mutualContainer!!.getService(LatchMutualB::class.java)
        }
    }

    class LatchMutualB {
        init {
            mutualLatchB!!.countDown()
            mutualLatchA!!.await(5, TimeUnit.SECONDS)
            mutualContainer!!.getService(LatchMutualA::class.java)
        }
    }

    class RingA {
        init {
            mutualContainer!!.getService(RingB::class.java)
        }
    }

    class RingB {
        init {
            ringLatchB!!.countDown()
            ringLatchC!!.await(5, TimeUnit.SECONDS)
            mutualContainer!!.getService(RingC::class.java)
        }
    }

    class RingC {
        init {
            ringLatchC!!.countDown()
            ringLatchB!!.await(5, TimeUnit.SECONDS)
            mutualContainer!!.getService(RingA::class.java)
        }
    }

    class ConcurrentAsyncService {
        companion object {
            val creationCount = AtomicInteger()
        }

        init {
            creationCount.incrementAndGet()
            Thread.sleep(50)
        }
    }

    class AsyncPreloadService {
        companion object {
            lateinit var started: CompletableDeferred<Unit>
            lateinit var finished: CompletableDeferred<Unit>
            lateinit var interrupted: CompletableDeferred<Unit>
            lateinit var release: CountDownLatch

            fun reset() {
                started = CompletableDeferred()
                finished = CompletableDeferred()
                interrupted = CompletableDeferred()
                release = CountDownLatch(1)
            }

            fun releaseIfInitialized() {
                if (::release.isInitialized) {
                    release.countDown()
                }
            }
        }

        init {
            started.complete(Unit)
            try {
                release.await(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                interrupted.complete(Unit)
                throw e
            }
            finished.complete(Unit)
        }
    }
}
