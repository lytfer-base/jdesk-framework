package com.jdesk.core.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathManagerSplitTest {

    @TempDir
    Path tempDir;

    private Path home;
    private Path program;
    private Path local;

    @BeforeEach
    void setUp() {
        clearState();
        home = tempDir.resolve("home");
        program = tempDir.resolve("program");
        local = tempDir.resolve("local");
    }

    @AfterEach
    void clearState() {
        System.clearProperty(PathManager.HOME_PROPERTY);
        System.clearProperty(PathManager.PROGRAM_DIR_PROPERTY);
        System.clearProperty(PathManager.LOCAL_DIR_PROPERTY);
        PathManager.resetForTests();
    }

    @Test
    void defaultsKeepEverythingUnderHome() {
        System.setProperty(PathManager.HOME_PROPERTY, home.toString());

        assertEquals(home, PathManager.getProgramDir());
        assertEquals(home, PathManager.getLocalDir());
        assertEquals(home.resolve("system"), PathManager.getSystemDir());
        assertEquals(home.resolve("log"), PathManager.getLogDir());
        assertFalse(PathManager.hasSeparateLocalDir());
    }

    @Test
    void separateDirsFollowProperties() {
        useSeparateDirs();

        assertEquals(home.resolve("config"), PathManager.getConfigDir());
        assertEquals(home.resolve("plugins"), PathManager.getPluginsDir());
        assertEquals(local.resolve("system"), PathManager.getSystemDir());
        assertEquals(local.resolve("log"), PathManager.getLogDir());
        assertEquals(program, PathManager.getProgramDir());
        assertEquals(local, PathManager.getLocalDir());
        assertTrue(PathManager.hasSeparateLocalDir());
    }

    @Test
    void separateLocalDirKeepsSystemAndLogOutOfTheHome() {
        useSeparateDirs();

        PathManager.getSystemDir();
        PathManager.getLogDir();

        assertTrue(Files.isDirectory(local.resolve("system")));
        assertTrue(Files.isDirectory(local.resolve("log")));
        assertFalse(Files.exists(home.resolve("system")), "system/ must not appear in the home");
        assertFalse(Files.exists(home.resolve("log")), "log/ must not appear in the home");
    }

    @Test
    void blankPropertiesFallBackToHome() {
        System.setProperty(PathManager.HOME_PROPERTY, home.toString());
        System.setProperty(PathManager.PROGRAM_DIR_PROPERTY, "  ");
        System.setProperty(PathManager.LOCAL_DIR_PROPERTY, "  ");

        assertEquals(home, PathManager.getLocalDir());
        assertEquals(home, PathManager.getProgramDir());
        assertFalse(PathManager.hasSeparateLocalDir());
    }

    @Test
    void applicationPropertiesComeFromProgramDir() throws Exception {
        useSeparateDirs();
        writeStartupProperties(program, "program");
        writeStartupProperties(local, "local");
        writeStartupProperties(home, "home");

        assertEquals(program.resolve("system").resolve("application.properties"),
                ApplicationStartupConfig.getApplicationPropertiesPath());
        Properties read = ApplicationStartupConfig.readProperties();
        assertEquals("program", read.getProperty("which.dir"));
    }

    @Test
    void relativePropertyValuesBecomeAbsolute() {
        System.setProperty(PathManager.HOME_PROPERTY, home.toString());
        System.setProperty(PathManager.PROGRAM_DIR_PROPERTY, "some/../relative-program");

        assertEquals(Path.of("relative-program").toAbsolutePath().normalize(), PathManager.getProgramDir());
    }

    @Test
    void homeOverrideForTestsMovesTheDefaultsWithIt() {
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");

        PathManager.setHomePathForTests(first);
        assertEquals(first, PathManager.getProgramDir());
        assertEquals(first.resolve("system"), PathManager.getSystemDir());
        assertEquals(first.resolve("log"), PathManager.getLogDir());

        PathManager.setHomePathForTests(second);
        assertEquals(second, PathManager.getProgramDir());
        assertEquals(second, PathManager.getLocalDir());
        assertEquals(second.resolve("system"), PathManager.getSystemDir());
        assertEquals(second.resolve("log"), PathManager.getLogDir());
    }

    private void useSeparateDirs() {
        System.setProperty(PathManager.HOME_PROPERTY, home.toString());
        System.setProperty(PathManager.PROGRAM_DIR_PROPERTY, program.toString());
        System.setProperty(PathManager.LOCAL_DIR_PROPERTY, local.toString());
    }

    private static void writeStartupProperties(Path base, String marker) throws Exception {
        Path systemDir = Files.createDirectories(base.resolve("system"));
        Files.writeString(systemDir.resolve("application.properties"), "which.dir=" + marker + "\n");
    }
}
