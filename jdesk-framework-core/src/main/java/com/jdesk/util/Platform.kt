package com.jdesk.util

/**
 * Platform-related utilities using Kotlin features.
 * Tests mixed Kotlin/Java compilation — callable from Java.
 */
object Platform {

    /** Current platform name: "windows", "macos", "linux", or "unknown" */
    val name: String by lazy {
        when {
            SystemInfo.isWindows -> "windows"
            SystemInfo.isMac -> "macos"
            SystemInfo.isLinux -> "linux"
            else -> "unknown"
        }
    }

    /** Get a human-readable OS + version string. */
    fun osString(): String = "${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION}"

    /** Check if running on a Unix-like OS (macOS or Linux). */
    fun isUnix(): Boolean = SystemInfo.isMac || SystemInfo.isLinux

    /** Get system info as a map. */
    fun infoMap(): Map<String, String> = mapOf(
        "os" to Platform.name,
        "osVersion" to SystemInfo.OS_VERSION,
        "osArch" to SystemInfo.OS_ARCH,
        "javaVersion" to SystemInfo.JAVA_VERSION,
        "javaVendor" to SystemInfo.JAVA_VENDOR,
        "cpus" to SystemInfo.AVAILABLE_PROCESSORS.toString(),
        "userName" to SystemInfo.USER_NAME,
        "userHome" to SystemInfo.USER_HOME
    )
}
