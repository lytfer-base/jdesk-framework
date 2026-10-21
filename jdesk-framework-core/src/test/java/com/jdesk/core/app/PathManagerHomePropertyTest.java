package com.jdesk.core.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathManagerHomePropertyTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearState() {
        System.clearProperty(PathManager.HOME_PROPERTY);
        PathManager.resetForTests();
    }

    @Test
    void systemPropertyHomeOverridesAutomaticDetection() {
        Path home = tempDir.resolve("dist").resolve("demo-app");
        System.setProperty(PathManager.HOME_PROPERTY, home.toString());
        PathManager.resetForTests();
        PathManager.setApplicationIdForCurrentProcess("demo-app");

        assertEquals(home.toAbsolutePath().normalize(), PathManager.getHomePath());
        assertEquals(home.resolve("plugins").toAbsolutePath().normalize(), PathManager.getPluginsDir());
        assertEquals(home.resolve("config").toAbsolutePath().normalize(), PathManager.getConfigDir());
        assertEquals(home.resolve("log").toAbsolutePath().normalize(), PathManager.getLogDir());
        assertEquals(home.resolve("system").toAbsolutePath().normalize(), PathManager.getSystemDir());

        assertTrue(Files.isDirectory(home));
        assertTrue(Files.isDirectory(home.resolve("plugins")));
        assertTrue(Files.isDirectory(home.resolve("config")));
        assertTrue(Files.isDirectory(home.resolve("log")));
        assertTrue(Files.isDirectory(home.resolve("system")));
    }

    @Test
    void blankSystemPropertyIsIgnored() {
        System.clearProperty(PathManager.HOME_PROPERTY);
        PathManager.resetForTests();
        Path automaticHome = PathManager.getHomePath();

        System.setProperty(PathManager.HOME_PROPERTY, "   ");
        PathManager.resetForTests();
        Path blankHome = PathManager.getHomePath();

        assertEquals(automaticHome, blankHome);
        assertTrue(Files.isDirectory(blankHome));
    }

    @Test
    void projectRootIsFoundByWalkingUpFromTheWorkingDirectory() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot.resolve(".root"));
        Path moduleDir = Files.createDirectories(projectRoot.resolve("some-module").resolve("src"));

        assertEquals(projectRoot.toAbsolutePath().normalize(), PathManager.findDevProjectRoot(moduleDir));
        assertEquals(projectRoot.toAbsolutePath().normalize(), PathManager.findDevProjectRoot(projectRoot));
    }

    @Test
    void nearestProjectRootWins() throws Exception {
        Path outer = tempDir.resolve("outer");
        Path inner = outer.resolve("inner");
        Files.createDirectories(outer.resolve(".root"));
        Files.createDirectories(inner.resolve(".root"));

        assertEquals(inner.toAbsolutePath().normalize(),
                PathManager.findDevProjectRoot(Files.createDirectories(inner.resolve("module"))));
    }

    @Test
    void workingDirectoryInsideRootStillResolvesToTheProject() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path appHome = Files.createDirectories(projectRoot.resolve(".root").resolve("demo-app"));

        assertEquals(projectRoot.toAbsolutePath().normalize(), PathManager.findDevProjectRoot(appHome));
    }

    @Test
    void aRootFileIsNotARootDirectory() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(projectRoot.resolve(".root"), "not a directory");

        Path found = PathManager.findDevProjectRoot(projectRoot);

        assertTrue(found == null || !found.equals(projectRoot.toAbsolutePath().normalize()),
                "A plain file named .root must not mark a project root: " + found);
    }

    @Test
    void applicationIdScopesDevelopmentHomeBelowRootDirectory() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot.resolve(".root"));
        PathManager.setApplicationIdForCurrentProcess("demo-app");

        Path home = PathManager.resolveDevHomePath(projectRoot);

        assertEquals(projectRoot.resolve(".root").resolve("demo-app").toAbsolutePath().normalize(), home);
        assertTrue(Files.isDirectory(home));
    }

    @Test
    void devHomeResolutionRequiresRootDirectory() {
        Path projectRoot = tempDir.resolve("project-without-root");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PathManager.resolveDevHomePath(projectRoot));

        assertTrue(error.getMessage().contains(".root"),
                "The message should name the missing .root directory: " + error.getMessage());
    }

    @Test
    void invalidApplicationIdIsRejectedBeforeItCanAffectPaths() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PathManager.setApplicationIdForCurrentProcess("../bad"));

        assertTrue(error.getMessage().contains("application id"));
    }
}
