package com.jdesk.util;

import java.util.Locale;

/**
 * Provides structured access to JVM and OS system properties.
 * Inspired by IntelliJ Platform's {@code SystemInfo}.
 */
public final class SystemInfo {

    private SystemInfo() {}

    // --- OS Detection ---

    public static final String OS_NAME = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
    public static final String OS_VERSION = System.getProperty("os.version", "unknown");
    public static final String OS_ARCH = System.getProperty("os.arch", "unknown");

    public static final boolean isWindows = OS_NAME.startsWith("windows");
    public static final boolean isMac = OS_NAME.startsWith("mac");
    public static final boolean isLinux = OS_NAME.startsWith("linux");

    // --- JVM Info ---

    public static final String JAVA_VERSION = System.getProperty("java.version", "unknown");
    public static final String JAVA_VENDOR = System.getProperty("java.vendor", "unknown");
    public static final String JAVA_HOME = System.getProperty("java.home", "unknown");

    public static final int JAVA_RUNTIME_VERSION = parseJavaVersion();

    private static int parseJavaVersion() {
        String version = JAVA_VERSION;
        // Handle "1.8.0_292" style
        if (version.startsWith("1.")) {
            try {
                return Integer.parseInt(version.substring(2, version.indexOf('.', 2)));
            } catch (Exception e) {
                return 8;
            }
        }
        // Handle "17.0.1" style
        int dot = version.indexOf('.');
        if (dot > 0) {
            try {
                return Integer.parseInt(version.substring(0, dot));
            } catch (Exception e) {
                return 17;
            }
        }
        return 17;
    }

    // --- Runtime ---

    public static final int AVAILABLE_PROCESSORS = Runtime.getRuntime().availableProcessors();
    public static final long TOTAL_MEMORY = Runtime.getRuntime().maxMemory();

    public static String formatMemory(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }

    // --- User ---

    public static final String USER_NAME = System.getProperty("user.name", "unknown");
    public static final String USER_HOME = System.getProperty("user.home", ".");
    public static final String USER_DIR = System.getProperty("user.dir", ".");

    // --- Line separator ---

    public static final String LINE_SEPARATOR = System.lineSeparator();

    // --- Summary ---

    public static String getSummary() {
        return String.format("""
                        OS: %s %s (%s)
                        Java: %s (%s, %s)
                        CPUs: %d | Memory: %s
                        User: %s""",
                capitalizeOS(OS_NAME), OS_VERSION, OS_ARCH,
                JAVA_VERSION, JAVA_VENDOR, JAVA_RUNTIME_VERSION,
                AVAILABLE_PROCESSORS, formatMemory(TOTAL_MEMORY),
                USER_NAME);
    }

    private static String capitalizeOS(String name) {
        if (name.startsWith("windows")) return "Windows";
        if (name.startsWith("mac")) return "macOS";
        if (name.startsWith("linux")) return "Linux";
        return name;
    }
}
