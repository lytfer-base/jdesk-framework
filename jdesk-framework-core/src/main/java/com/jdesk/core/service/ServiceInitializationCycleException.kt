package com.jdesk.core.service

class ServiceInitializationCycleException(
    cycle: List<Class<*>>,
) : RuntimeException(
    "Service initialization cycle: " + cycle.joinToString(" -> ") { it.name },
)
