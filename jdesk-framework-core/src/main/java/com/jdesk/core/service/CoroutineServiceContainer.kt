package com.jdesk.core.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin-facing marker for service containers that are safe to use from
 * coroutine startup code. Suspend access is provided by [getServiceAsync].
 */
interface CoroutineServiceContainer : ServiceContainer {
    suspend fun <T : Any> getServiceAsync(serviceClass: Class<T>): T
}

suspend fun <T : Any> ServiceContainer.getServiceAsync(serviceClass: Class<T>): T {
    if (this is CoroutineServiceContainer) {
        return getServiceAsync(serviceClass)
    }
    return withContext(Dispatchers.Default) {
        getService(serviceClass)
    }
}

suspend inline fun <reified T : Any> ServiceContainer.getServiceAsync(): T {
    return getServiceAsync(T::class.java)
}
