package com.jdesk.core.service.impl

import com.jdesk.core.app.ApplicationTask
import com.jdesk.core.app.ApplicationTaskScope
import com.jdesk.core.config.ComponentStore
import com.jdesk.core.config.PersistentStateComponent
import com.jdesk.core.disposer.Disposable
import com.jdesk.core.disposer.Disposer
import com.jdesk.core.service.CoroutineServiceContainer
import com.jdesk.core.service.ServiceContainer
import com.jdesk.core.service.ServiceDescriptor
import com.jdesk.core.service.ServiceInitializationCycleException
import com.jdesk.core.service.ServicePreloadMode
import com.jdesk.core.startup.StartupTrace
import com.jdesk.core.startup.StartupTraces
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class ServiceContainerImpl : ServiceContainer, CoroutineServiceContainer {

    private val descriptors = ConcurrentHashMap<Class<*>, ServiceDescriptor<*>>()
    private val instances = ConcurrentHashMap<Class<*>, Any>()
    private val creations = ConcurrentHashMap<Class<*>, ServiceCreation<*>>()
    private val serviceLocks = ConcurrentHashMap<String, Any>()

    /**
     * Guards the cross-thread wait graph: [ServiceCreation.waitingOn] edges are read and written only under it, so
     * checking for a cycle and adding an edge are atomic and the last edge of a mutual wait always finds the cycle.
     */
    private val waitGraphLock = Any()

    private val registrationOrder = Collections.synchronizedList(ArrayList<Class<*>>())
    private val serviceParentDisposable = Disposer.newDisposable("application services")
    private val taskScope: ApplicationTaskScope
    private val ownsTaskScope: Boolean

    @Volatile
    private var initialized = false

    @Volatile
    private var disposed = false

    constructor() : this(ApplicationTaskScope("service-container"), true)

    constructor(taskScope: ApplicationTaskScope) : this(taskScope, false)

    private constructor(taskScope: ApplicationTaskScope, ownsTaskScope: Boolean) {
        this.taskScope = taskScope
        this.ownsTaskScope = ownsTaskScope
    }

    fun initialize() {
        initializeAsync()
    }

    fun initializeAsync() {
        if (initialized) {
            return
        }
        initialized = true
        LOG.info("Service container initialized")

        for ((serviceClass, descriptor) in descriptors.entries) {
            when (descriptor.preloadMode) {
                ServicePreloadMode.AWAIT -> getServiceUntyped(serviceClass)
                ServicePreloadMode.ASYNC -> startAsyncPreload(serviceClass)
                ServicePreloadMode.NONE -> Unit
            }
        }
    }

    override fun dispose() {
        if (disposed) {
            return
        }
        disposed = true
        LOG.info("Disposing service container...")

        cancelPendingCreations("ServiceContainer is disposed")
        if (ownsTaskScope) {
            taskScope.cancelAll()
        }

        val reversed = ArrayList(registrationOrder)
        reversed.reverse()
        for (serviceClass in reversed) {
            val lock = serviceLock(serviceClass)
            synchronized(lock) {
                disposeServiceInstance(serviceClass, instances[serviceClass])
            }
        }

        try {
            Disposer.dispose(serviceParentDisposable)
        } catch (e: Throwable) {
            LOG.error("Error disposing remaining application services", e)
        }
        instances.clear()
        descriptors.clear()
        creations.clear()
        serviceLocks.clear()
        registrationOrder.clear()
    }

    override fun <T : Any> getService(serviceClass: Class<T>): T {
        checkNotDisposed()
        return when (val access = prepareAccess(serviceClass)) {
            is ServiceAccess.Existing -> access.instance
            is ServiceAccess.Await -> awaitCreationBlocking(access.creation)
            is ServiceAccess.Create -> completeCreationInCurrentThread(access.creation, access.descriptor)
        }
    }

    override suspend fun <T : Any> getServiceAsync(serviceClass: Class<T>): T {
        checkNotDisposed()
        val access = prepareAccess(serviceClass)
        when (access) {
            is ServiceAccess.Existing -> return access.instance
            is ServiceAccess.Await -> return awaitCreationSuspending(access.creation)
            is ServiceAccess.Create -> {
                startBackgroundCreation(
                    access.creation,
                    access.descriptor,
                    "service.create.async:${serviceClass.name}",
                )
                return awaitCreationSuspending(access.creation)
            }
        }
    }

    override fun <T : Any> getServiceIfCreated(serviceClass: Class<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return instances[serviceClass] as T?
    }

    override fun getCreatedServicesSnapshot(): Collection<Any> {
        return instances.values.toList()
    }

    override fun <T : Any> registerInstance(serviceClass: Class<T>, instance: T) {
        val lock = serviceLock(serviceClass)
        synchronized(lock) {
            checkNotDisposed()
            cancelCreationLocked(serviceClass, "Service instance registered while creation was in progress")
            cacheInstance(serviceClass, instance)
            descriptors[serviceClass] = ServiceDescriptor.ofInstance(serviceClass, instance)
            registrationOrder.add(serviceClass)
            StartupTraces.getCurrent().incrementCounter("services.instances.registered")
            LOG.debug("Registered service instance: {}", serviceClass.simpleName)
        }
    }

    override fun <T : Any> registerService(serviceClass: Class<T>, descriptor: ServiceDescriptor<T>) {
        var preloadMode = ServicePreloadMode.NONE
        val lock = serviceLock(serviceClass)
        synchronized(lock) {
            checkNotDisposed()
            cancelCreationLocked(serviceClass, "Service descriptor replaced while creation was in progress")
            descriptors[serviceClass] = descriptor

            descriptor.prebuiltInstance?.let {
                cacheInstance(serviceClass, it)
            }

            registrationOrder.add(serviceClass)
            StartupTraces.getCurrent().incrementCounter("services.descriptors.registered")
            LOG.debug("Registered service descriptor: {}", serviceClass.simpleName)
            if (initialized) {
                preloadMode = descriptor.preloadMode
            }
        }

        when (preloadMode) {
            ServicePreloadMode.AWAIT -> getService(serviceClass)
            ServicePreloadMode.ASYNC -> startAsyncPreload(serviceClass)
            ServicePreloadMode.NONE -> Unit
        }
    }

    override fun <T : Any> unregisterService(serviceClass: Class<T>): Boolean {
        val lock = serviceLock(serviceClass)
        synchronized(lock) {
            cancelCreationLocked(serviceClass, "Service unregistered while creation was in progress")
            val descriptor = descriptors.remove(serviceClass)
            val instance = instances.remove(serviceClass)
            registrationOrder.removeIf { registeredClass -> registeredClass === serviceClass }

            if (descriptor == null && instance == null) {
                return false
            }

            if (!disposed && instance != null && !isInstanceRegisteredElsewhere(instance)) {
                tryUnregisterPersistentStateComponent(instance)
                disposeServiceInstance(serviceClass, instance)
            }

            LOG.debug("Unregistered service: {}", serviceClass.simpleName)
            return true
        }
    }

    override fun <T : Any> hasService(serviceClass: Class<T>): Boolean {
        return descriptors.containsKey(serviceClass)
    }

    fun getRegisteredServiceCount(): Int = descriptors.size

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> prepareAccess(serviceClass: Class<T>): ServiceAccess<T> {
        instances[serviceClass]?.let {
            return ServiceAccess.Existing(it as T)
        }

        val lock = serviceLock(serviceClass)
        synchronized(lock) {
            checkNotDisposed()
            instances[serviceClass]?.let {
                return ServiceAccess.Existing(it as T)
            }

            ServiceCreationContext.cycleFor(serviceClass)?.let {
                throw ServiceInitializationCycleException(it)
            }

            creations[serviceClass]?.let {
                return ServiceAccess.Await(it as ServiceCreation<T>)
            }

            val descriptor = descriptors[serviceClass] as ServiceDescriptor<T>?
                ?: throw IllegalStateException(
                    "Service not registered: ${serviceClass.name}. " +
                        "Register it via registerInstance() or registerService().",
                )
            val creation = ServiceCreation(
                serviceClass = serviceClass,
                future = CompletableFuture<T>(),
                parentSpanId = StartupTraces.getCurrent().currentSpanId,
            )
            creations[serviceClass] = creation
            return ServiceAccess.Create(descriptor, creation)
        }
    }

    private fun <T : Any> completeCreationInCurrentThread(
        creation: ServiceCreation<T>,
        descriptor: ServiceDescriptor<T>,
    ): T {
        try {
            val instance = instantiateAndCacheService(creation, descriptor)
            creation.future.complete(instance)
            return instance
        } catch (failure: Throwable) {
            val normalized = unwrapDescriptorCreationFailure(failure)
            clearCreationIfCurrent(creation)
            creation.future.completeExceptionally(normalized)
            throwUnchecked(normalized)
        }
    }

    /**
     * Blocks until the shared creation finishes. A caller inside a service creation first adds a wait edge, so a
     * cross-thread dependency cycle throws [ServiceInitializationCycleException] instead of hanging forever.
     */
    private fun <T : Any> awaitCreationBlocking(creation: ServiceCreation<T>): T {
        val waiter = registerWaitEdge(creation)
        try {
            return creation.future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        } catch (e: ExecutionException) {
            throw unwrapServiceCreationFailure(e.cause ?: e)
        } catch (e: CancellationException) {
            throw e
        } finally {
            clearWaitEdge(waiter)
        }
    }

    /**
     * Suspending version of [awaitCreationBlocking]. The cycle check must run on the calling thread before suspending,
     * while [ServiceCreationContext] still sees its stack.
     */
    private suspend fun <T : Any> awaitCreationSuspending(creation: ServiceCreation<T>): T {
        val waiter = registerWaitEdge(creation)
        try {
            return suspendCancellableCoroutine { continuation ->
                creation.future.whenComplete { value, failure ->
                    if (failure == null) {
                        continuation.resume(value)
                    } else {
                        continuation.resumeWithException(unwrapServiceCreationFailure(failure))
                    }
                }
            }
        } finally {
            clearWaitEdge(waiter)
        }
    }

    /**
     * Adds the edge from the current creation to {@code target} unless it would close a cycle, which throws
     * [ServiceInitializationCycleException] with the full path. Returns the creation that got the edge, or {@code null}
     * outside a service creation.
     */
    private fun registerWaitEdge(target: ServiceCreation<*>): ServiceCreation<*>? {
        val current = ServiceCreationContext.currentCreation() ?: return null
        synchronized(waitGraphLock) {
            val path = ArrayList<Class<*>>()
            var node: ServiceCreation<*>? = target
            while (node != null) {
                path.add(node.serviceClass)
                if (node === current) {
                    path.add(target.serviceClass)
                    throw ServiceInitializationCycleException(path)
                }
                node = node.waitingOn
            }
            current.waitingOn = target
        }
        return current
    }

    private fun clearWaitEdge(waiter: ServiceCreation<*>?) {
        if (waiter == null) {
            return
        }
        synchronized(waitGraphLock) {
            waiter.waitingOn = null
        }
    }

    /**
     * Adds the edge from the outer to the inner creation when creations nest on one thread and returns the outer one,
     * or {@code null}. Don't drop it, or cross-thread cycles through nested creations go undetected
     * (threeServiceRingWithNestedCreationFailsFastAcrossThreads).
     */
    private fun registerNestingEdge(inner: ServiceCreation<*>): ServiceCreation<*>? {
        val outer = ServiceCreationContext.currentCreation() ?: return null
        synchronized(waitGraphLock) {
            outer.waitingOn = inner
        }
        return outer
    }

    private fun <T : Any> instantiateAndCacheService(
        creation: ServiceCreation<T>,
        descriptor: ServiceDescriptor<T>,
    ): T {
        val serviceClass = creation.serviceClass
        val startupTrace = StartupTraces.getCurrent()
        val outer = registerNestingEdge(creation)
        try {
            return ServiceCreationContext.run(creation) {
                startupTrace.withParentSpan(creation.parentSpanId, Callable {
                    startupTrace.runSpan("service.create:${serviceClass.name}") { span ->
                        span.detail("service", serviceClass.name)
                        val instance = descriptor.createInstance()
                        cacheCreatedInstance(creation, descriptor, instance)
                        startupTrace.incrementCounter("services.created")
                        LOG.debug("Created service: {} (lazy)", serviceClass.simpleName)
                        instance
                    }
                })
            }
        } finally {
            clearWaitEdge(outer)
        }
    }

    private fun <T : Any> cacheCreatedInstance(
        creation: ServiceCreation<T>,
        descriptor: ServiceDescriptor<T>,
        instance: T,
    ) {
        val serviceClass = creation.serviceClass
        val lock = serviceLock(serviceClass)
        synchronized(lock) {
            if (disposed) {
                disposeServiceInstance(serviceClass, instance)
                throw CancellationException("Service creation cancelled because container is disposed: ${serviceClass.name}")
            }
            if (creations[serviceClass] !== creation || descriptors[serviceClass] !== descriptor) {
                disposeServiceInstance(serviceClass, instance)
                throw CancellationException("Service creation cancelled: ${serviceClass.name}")
            }
            cacheInstance(serviceClass, instance)
            creations.remove(serviceClass, creation)
        }
    }

    private fun <T : Any> startBackgroundCreation(
        creation: ServiceCreation<T>,
        descriptor: ServiceDescriptor<T>,
        taskName: String,
    ) {
        if (!creation.started.compareAndSet(false, true)) {
            return
        }

        try {
            creation.task = taskScope.asyncIo(taskName, Callable {
                completeCreationInCurrentThread(creation, descriptor)
            })
        } catch (failure: Throwable) {
            clearCreationIfCurrent(creation)
            creation.future.completeExceptionally(failure)
            throw failure
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun startAsyncPreload(serviceClass: Class<*>) {
        val typedClass = serviceClass as Class<Any>
        when (val access = prepareAccess(typedClass)) {
            is ServiceAccess.Existing -> Unit
            is ServiceAccess.Await -> Unit
            is ServiceAccess.Create -> startBackgroundCreation(
                access.creation,
                access.descriptor,
                "service.preload.async:${serviceClass.name}",
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> getServiceUntyped(serviceClass: Class<*>): T {
        return getService(serviceClass as Class<T>)
    }

    private fun clearCreationIfCurrent(creation: ServiceCreation<*>) {
        val lock = serviceLock(creation.serviceClass)
        synchronized(lock) {
            creations.remove(creation.serviceClass, creation)
        }
    }

    private fun cancelPendingCreations(reason: String) {
        val snapshot = creations.values.toList()
        for (creation in snapshot) {
            cancelCreation(creation, reason)
        }
    }

    private fun cancelCreationLocked(serviceClass: Class<*>, reason: String) {
        val creation = creations.remove(serviceClass) ?: return
        cancelCreation(creation, reason)
    }

    private fun cancelCreation(creation: ServiceCreation<*>, reason: String) {
        creation.task?.cancel()
        creation.future.completeExceptionally(CancellationException("$reason: ${creation.serviceClass.name}"))
    }

    private fun checkNotDisposed() {
        if (disposed) {
            throw IllegalStateException("ServiceContainer is disposed")
        }
    }

    private fun serviceLock(serviceClass: Class<*>): Any {
        return serviceLocks.computeIfAbsent(serviceClass.name) { Any() }
    }

    private fun <T : Any> cacheInstance(serviceClass: Class<T>, instance: T) {
        instances[serviceClass] = instance
        if (instance is Disposable) {
            Disposer.tryRegister(serviceParentDisposable, instance)
        }
        tryRegisterPersistentStateComponent(instance)
    }

    private fun tryRegisterPersistentStateComponent(instance: Any) {
        if (instance !is PersistentStateComponent<*>) {
            return
        }
        if (ComponentStore.findStateAnnotation(instance.javaClass) == null) {
            return
        }

        val store = getServiceIfCreated(ComponentStore::class.java)
        if (store == null || store === instance) {
            return
        }
        store.registerServiceIfPersistent(instance)
    }

    /** Moves an unregistered service out of the ComponentStore, which saves its state once first. */
    private fun tryUnregisterPersistentStateComponent(instance: Any) {
        if (instance !is PersistentStateComponent<*>) {
            return
        }
        val store = getServiceIfCreated(ComponentStore::class.java)
        if (store == null || store === instance) {
            return
        }
        store.unregister(instance)
    }

    private fun isInstanceRegisteredElsewhere(target: Any): Boolean {
        return instances.values.any { it === target }
    }

    private fun disposeServiceInstance(serviceClass: Class<*>, instance: Any?) {
        if (instance == null) {
            return
        }

        // Catch Errors too: the services after a failing one must still be disposed.
        if (instance is Disposable) {
            try {
                Disposer.dispose(instance)
            } catch (e: Throwable) {
                LOG.error("Error disposing Disposable service: {}", serviceClass.name, e)
            }
            return
        }

        if (instance is AutoCloseable) {
            try {
                instance.close()
            } catch (e: Throwable) {
                LOG.error("Error closing service: {}", serviceClass.name, e)
            }
        }
    }

    private sealed interface ServiceAccess<T : Any> {
        class Existing<T : Any>(val instance: T) : ServiceAccess<T>
        class Await<T : Any>(val creation: ServiceCreation<T>) : ServiceAccess<T>
        class Create<T : Any>(
            val descriptor: ServiceDescriptor<T>,
            val creation: ServiceCreation<T>,
        ) : ServiceAccess<T>
    }

    private class ServiceCreation<T : Any>(
        val serviceClass: Class<T>,
        val future: CompletableFuture<T>,
        val parentSpanId: Int?,
    ) {
        val started = AtomicBoolean(false)

        @Volatile
        var task: ApplicationTask<T>? = null

        /**
         * The creation this one waits for, including an inner creation nested on the same thread; read and written
         * only under [waitGraphLock].
         */
        @Volatile
        var waitingOn: ServiceCreation<*>? = null
    }

    /**
     * Per-thread stack of running creations, which catches cycles nested on one thread; cross-thread cycles are found
     * through the wait graph of [ServiceCreation.waitingOn].
     */
    private object ServiceCreationContext {
        private val stack = ThreadLocal<MutableList<ServiceCreation<*>>>()

        fun cycleFor(serviceClass: Class<*>): List<Class<*>>? {
            val currentStack = stack.get() ?: return null
            val index = currentStack.indexOfFirst { it.serviceClass == serviceClass }
            if (index < 0) {
                return null
            }
            return currentStack.subList(index, currentStack.size).map { it.serviceClass } + serviceClass
        }

        fun currentCreation(): ServiceCreation<*>? = stack.get()?.lastOrNull()

        fun <T> run(creation: ServiceCreation<*>, action: () -> T): T {
            cycleFor(creation.serviceClass)?.let {
                throw ServiceInitializationCycleException(it)
            }
            val currentStack = stack.get() ?: ArrayList<ServiceCreation<*>>().also(stack::set)
            currentStack.add(creation)
            try {
                return action()
            } finally {
                currentStack.removeAt(currentStack.lastIndex)
                if (currentStack.isEmpty()) {
                    stack.remove()
                }
            }
        }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(ServiceContainerImpl::class.java)
    }
}

private fun unwrapServiceCreationFailure(failure: Throwable): RuntimeException {
    val cause = unwrapDescriptorCreationFailure(
        if (failure is ExecutionException && failure.cause != null) failure.cause!! else failure,
    )
    return when (cause) {
        is RuntimeException -> cause
        is Error -> throw cause
        else -> RuntimeException(cause)
    }
}

private fun unwrapDescriptorCreationFailure(failure: Throwable): Throwable {
    var current = failure
    if (current is RuntimeException && current.cause is InvocationTargetException) {
        current = current.cause!!
    }
    while ((current is InvocationTargetException || current is ExecutionException) && current.cause != null) {
        current = current.cause!!
    }
    return current
}

private fun throwUnchecked(failure: Throwable): Nothing {
    when (failure) {
        is RuntimeException -> throw failure
        is Error -> throw failure
        else -> throw RuntimeException(failure)
    }
}
