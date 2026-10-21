package com.jdesk.frame.webview.jcef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Tells whether this runtime can start JCEF and locates the JBR files it needs.
 * Don't judge by the classes alone: if the compile-time {@code org.cef} API leaks onto the class path, a plain JRE
 * looks capable but fails to start; the JBR's helper and resource packs decide.
 */
public final class JcefRuntime {

    private JcefRuntime() {
    }

    public static boolean isAvailable() {
        return classesPresent() && helper().isPresent() && resourcesDir().isPresent();
    }

    /** The CEF helper executable; without it CEF launches java itself as the subprocess. */
    static Optional<Path> helper() {
        Path bin = javaHome().resolve("bin").resolve(isWindows() ? "jcef_helper.exe" : "jcef_helper");
        return Files.isRegularFile(bin) ? Optional.of(bin) : Optional.empty();
    }

    /** The folder holding Chromium's {@code *.pak} files. */
    static Optional<Path> resourcesDir() {
        Path lib = javaHome().resolve("lib");
        return Files.isRegularFile(lib.resolve("resources.pak")) ? Optional.of(lib) : Optional.empty();
    }

    static Optional<Path> localesDir() {
        Path locales = javaHome().resolve("lib").resolve("locales");
        return Files.isDirectory(locales) ? Optional.of(locales) : Optional.empty();
    }

    private static boolean classesPresent() {
        try {
            Class.forName("org.cef.CefApp", false, JcefRuntime.class.getClassLoader());
            return true;
        } catch (LinkageError | ClassNotFoundException ignored) {
            return false;
        }
    }

    private static Path javaHome() {
        return Path.of(System.getProperty("java.home", ""));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
