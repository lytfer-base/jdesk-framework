package com.jdesk.core.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.regex.Pattern;

/**
 * Resolves the application home, the program directory and the machine-local directory, and the subdirectories below them.
 *
 * <p>The home is, in order: {@code -Djdesk.home}; in development mode {@code <project>/.root/<appId>/}, where
 * the project is the nearest ancestor of the working directory that contains {@code .root/}; otherwise the
 * working directory. Results are cached; tests reset them with {@link #resetForTests()}.
 */
public final class PathManager {

    private static final Logger LOG = LoggerFactory.getLogger(PathManager.class);

    private static final String ROOT_DIR = ".root";
    public static final String HOME_PROPERTY = "jdesk.home";
    public static final String PROGRAM_DIR_PROPERTY = "jdesk.program.dir";
    public static final String LOCAL_DIR_PROPERTY = "jdesk.local.dir";
    private static final String PLUGINS_DIR = "plugins";
    private static final String CONFIG_DIR = "config";
    private static final String LOG_DIR = "log";
    static final String SYSTEM_DIR = "system";
    private static final Pattern APPLICATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static volatile String applicationId = "";
    private static volatile Path homePath;
    private static volatile Path programPath;
    private static volatile Path localPath;
    private static volatile Path pluginsPath;
    private static volatile Path configPath;
    private static volatile Path logPath;
    private static volatile Path systemPath;
    private static volatile Boolean developmentMode;

    private static final Object LOCK = new Object();

    private PathManager() {
    }

    /**
     * Sets the id that scopes the development home to {@code .root/<appId>/}.
     * Call it before the home is first resolved; {@code -Djdesk.home} still wins.
     */
    public static void setApplicationIdForCurrentProcess(String appId) {
        String normalized = normalizeApplicationId(appId);
        synchronized (LOCK) {
            applicationId = normalized;
        }
    }

    /** Returns whether the process runs from a source checkout: some ancestor of the working directory contains {@code .root/}. */
    public static boolean isDevelopmentMode() {
        Boolean result = developmentMode;
        if (result != null) return result;

        synchronized (LOCK) {
            result = developmentMode;
            if (result != null) return result;

            result = findDevProjectRoot(workingDirectory()) != null;
            developmentMode = result;
            return result;
        }
    }

    /** Returns the application home; see the class comment for the resolution order. */
    public static Path getHomePath() {
        Path result = homePath;
        if (result != null) return result;

        synchronized (LOCK) {
            result = homePath;
            if (result != null) return result;

            Path explicitHome = resolveHomePathFromSystemProperty();
            if (explicitHome != null) {
                result = explicitHome;
            } else if (isDevelopmentMode()) {
                result = resolveDevHomePath(findDevProjectRoot(workingDirectory()));
            } else {
                result = workingDirectory();
                LOG.info("Home path (production): {}", result);
            }

            homePath = result;
            return result;
        }
    }

    private static Path resolveHomePathFromSystemProperty() {
        Path result = pathFromProperty(HOME_PROPERTY);
        if (result == null) {
            return null;
        }

        ensureDir(result);
        LOG.info("Home path (system property {}): {}", HOME_PROPERTY, result);
        return result;
    }

    private static Path pathFromProperty(String property) {
        String configured = System.getProperty(property);
        if (configured == null || configured.trim().isEmpty()) {
            return null;
        }
        return Path.of(configured.trim()).toAbsolutePath().normalize();
    }

    /**
     * Returns the directory of the program's own files: {@code -Djdesk.program.dir}, else the home.
     * Don't resolve program files against the home; the two may differ.
     */
    public static Path getProgramDir() {
        Path result = programPath;
        if (result != null) return result;

        synchronized (LOCK) {
            result = programPath;
            if (result != null) return result;

            Path configured = pathFromProperty(PROGRAM_DIR_PROPERTY);
            if (configured != null) {
                result = configured;
                LOG.info("Program path (system property {}): {}", PROGRAM_DIR_PROPERTY, result);
            } else {
                result = getHomePath();
            }

            programPath = result;
            return result;
        }
    }

    /** Returns the machine-local directory: {@code -Djdesk.local.dir}, else the home. It is created on first access. */
    public static Path getLocalDir() {
        Path result = localPath;
        if (result != null) return result;

        synchronized (LOCK) {
            result = localPath;
            if (result != null) return result;

            Path configured = pathFromProperty(LOCAL_DIR_PROPERTY);
            if (configured != null) {
                result = configured;
                ensureDir(result);
                LOG.info("Local path (system property {}): {}", LOCAL_DIR_PROPERTY, result);
            } else {
                result = getHomePath();
            }

            localPath = result;
            return result;
        }
    }

    /** Returns whether the machine-local directory differs from the home. */
    public static boolean hasSeparateLocalDir() {
        return !getLocalDir().equals(getHomePath());
    }

    /** Returns the nearest directory at or above {@code start} that contains a {@code .root/} directory, or {@code null}. */
    static Path findDevProjectRoot(Path start) {
        Path dir = start == null ? null : start.toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve(ROOT_DIR))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** Returns {@code <projectRoot>/.root/<appId>/}, or {@code .root/} itself when no application id is set. */
    static Path resolveDevHomePath(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalStateException("No project root with a .root directory was found");
        }
        Path rootDir = projectRoot.resolve(ROOT_DIR).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootDir)) {
            throw new IllegalStateException(
                    ".root directory not found at " + rootDir +
                            " — in development mode, .root/ must exist under the project root. " +
                            "Create it at: " + rootDir);
        }

        String currentApplicationId = applicationId;
        Path result = currentApplicationId == null || currentApplicationId.isBlank()
                ? rootDir
                : rootDir.resolve(currentApplicationId).toAbsolutePath().normalize();
        if (!result.startsWith(rootDir)) {
            throw new IllegalStateException("Application home escaped .root: " + result);
        }
        ensureDir(result);
        LOG.info("Home path (dev): {}", result);
        return result;
    }

    private static Path workingDirectory() {
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /** Returns {@code <home>/plugins/}, creating it on first access. */
    public static Path getPluginsDir() {
        Path result = pluginsPath;
        if (result != null) return result;

        synchronized (LOCK) {
            result = pluginsPath;
            if (result != null) return result;
            result = getHomePath().resolve(PLUGINS_DIR);
            ensureDir(result);
            pluginsPath = result;
            return result;
        }
    }

    /** Overrides the home directory and re-derives the others; call it before the application initializes. */
    public static void setHomePathForTests(Path homeDir) {
        synchronized (LOCK) {
            Path normalized = homeDir.toAbsolutePath().normalize();
            ensureDir(normalized);
            homePath = normalized;
            programPath = null;
            localPath = null;
            pluginsPath = null;
            configPath = null;
            logPath = null;
            systemPath = null;
        }
    }

    /** Clears every cached path, the development-mode flag and the application id. */
    public static void resetForTests() {
        synchronized (LOCK) {
            homePath = null;
            programPath = null;
            localPath = null;
            pluginsPath = null;
            configPath = null;
            logPath = null;
            systemPath = null;
            developmentMode = null;
            applicationId = "";
        }
    }

    /** Returns {@code <home>/config/}, creating it on first access. */
    public static Path getConfigDir() {
        Path result = configPath;
        if (result != null) return result;

        synchronized (LOCK) {
            result = configPath;
            if (result != null) return result;
            result = getHomePath().resolve(CONFIG_DIR);
            ensureDir(result);
            configPath = result;
            return result;
        }
    }

    /** Returns {@code <local>/log/}, creating it on first access. */
    public static Path getLogDir() {
        Path result = logPath;
        if (result != null) return result;

        synchronized (LOCK) {
            result = logPath;
            if (result != null) return result;
            result = getLocalDir().resolve(LOG_DIR);
            ensureDir(result);
            logPath = result;
            return result;
        }
    }

    /** Returns {@code <local>/system/}, creating it on first access. */
    public static Path getSystemDir() {
        Path result = systemPath;
        if (result != null) return result;

        synchronized (LOCK) {
            result = systemPath;
            if (result != null) return result;
            result = getLocalDir().resolve(SYSTEM_DIR);
            ensureDir(result);
            systemPath = result;
            return result;
        }
    }

    private static void ensureDir(Path dir) {
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            LOG.warn("Failed to create directory: {}", dir, e);
        }
    }

    private static String normalizeApplicationId(String appId) {
        if (appId == null || appId.isBlank()) {
            return "";
        }
        String cleaned = appId.trim();
        if (!APPLICATION_ID_PATTERN.matcher(cleaned).matches()) {
            throw new IllegalArgumentException(
                    "Invalid application id '" + appId + "'. " +
                            "An application id must start with a letter or digit and contain only letters, digits, '.', '_' or '-'.");
        }
        return cleaned;
    }
}
