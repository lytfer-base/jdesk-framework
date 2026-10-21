package com.jdesk.core.app

import com.jdesk.core.service.getServiceAsync

/**
 * Kotlin-facing marker for applications that are expected to be driven from
 * coroutine startup APIs.
 */
interface CoroutineApplication : Application

suspend fun <T : Any> CoroutineApplication.getServiceAsync(serviceClass: Class<T>): T {
    return serviceContainer.getServiceAsync(serviceClass)
}

suspend inline fun <reified T : Any> CoroutineApplication.getServiceAsync(): T {
    return getServiceAsync(T::class.java)
}
