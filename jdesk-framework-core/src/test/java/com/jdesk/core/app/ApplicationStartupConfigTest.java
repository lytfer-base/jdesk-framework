package com.jdesk.core.app;

import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.core.runtime.RuntimeParamSource;
import com.jdesk.core.runtime.params.CoreRuntimeParams;
import com.jdesk.core.startup.StartupSpan;
import com.jdesk.core.startup.StartupTrace;
import com.jdesk.core.startup.StartupTraceMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationStartupConfigTest {

    @TempDir
    Path homePath;

    @AfterEach
    void clearState() {
        RuntimeParamRegistry.reset();
        PathManager.resetForTests();
    }

    @Test
    void readsApplicationPropertiesFromSystemDirectory() throws Exception {
        useHomePath();
        Files.writeString(homePath.resolve("application.properties"),
                CoreRuntimeParams.STARTUP_TRACE_MODE_KEY + "=report\n");
        writeSystemApplicationProperties(CoreRuntimeParams.STARTUP_TRACE_MODE_KEY + "=false\n");

        Properties properties = ApplicationStartupConfig.readProperties();

        assertEquals("false", properties.getProperty(CoreRuntimeParams.STARTUP_TRACE_MODE_KEY));
        assertEquals(homePath.resolve("system").resolve("application.properties"),
                ApplicationStartupConfig.getApplicationPropertiesPath());
    }

    @Test
    void missingPropertiesLeavesRegistryEmptyAndStartupTraceDefaultsToTimeMode() {
        useHomePath();

        ApplicationStartupConfig.load();
        StartupTrace trace = StartupTrace.create();

        assertTrue(RuntimeParamRegistry.getKeys().isEmpty());
        assertFalse(RuntimeParamRegistry.contains(CoreRuntimeParams.STARTUP_TRACE_MODE_KEY));
        assertEquals(StartupTraceMode.TIME, trace.getMode());
        assertTrue(trace.isTimeEnabled());
    }

    @Test
    void applicationPropertiesAreWrittenToRuntimeRegistryAsStrings() throws Exception {
        useHomePath();
        writeSystemApplicationProperties(
                CoreRuntimeParams.STARTUP_TRACE_MODE_KEY + "=report\n"
                        + "custom.example=value\n"
                        + "blank.example=\n");

        ApplicationStartupConfig.load();
        StartupTrace.create();

        assertEquals("report", RuntimeParamRegistry.getString(CoreRuntimeParams.STARTUP_TRACE_MODE_KEY));
        assertEquals(RuntimeParamSource.STARTUP_CONFIG,
                RuntimeParamRegistry.get(CoreRuntimeParams.STARTUP_TRACE_MODE_KEY).getSource());
        assertEquals("value", RuntimeParamRegistry.getString("custom.example"));
        assertEquals("", RuntimeParamRegistry.getString("blank.example"));
    }

    @Test
    void reportModeIsInterpretedByStartupTraceConsumer() throws Exception {
        useHomePath();
        writeSystemApplicationProperties(CoreRuntimeParams.STARTUP_TRACE_MODE_KEY + "=report\n");

        ApplicationStartupConfig.load();
        StartupTrace trace = StartupTrace.create();

        assertEquals(StartupTraceMode.REPORT, trace.getMode());

        try (StartupSpan ignored = trace.begin("sample")) {
            trace.incrementCounter("sample.counter");
        }

        assertEquals(1, trace.getEvents().size());
        assertEquals(1L, trace.getCounters().get("sample.counter"));
    }

    @Test
    void falseModeIsInterpretedByStartupTraceConsumer() throws Exception {
        useHomePath();
        writeSystemApplicationProperties(CoreRuntimeParams.STARTUP_TRACE_MODE_KEY + "=false\n");

        ApplicationStartupConfig.load();
        StartupTrace trace = StartupTrace.create();

        assertEquals("false", RuntimeParamRegistry.getString(CoreRuntimeParams.STARTUP_TRACE_MODE_KEY));
        assertEquals(RuntimeParamSource.STARTUP_CONFIG,
                RuntimeParamRegistry.get(CoreRuntimeParams.STARTUP_TRACE_MODE_KEY).getSource());
        assertEquals(StartupTraceMode.OFF, trace.getMode());
        assertFalse(trace.isEnabled());

        try (StartupSpan ignored = trace.begin("sample")) {
            trace.incrementCounter("sample.counter");
        }

        assertTrue(trace.getEvents().isEmpty());
        assertTrue(trace.getCounters().isEmpty());
    }

    @Test
    void invalidModeFallsBackToTimeButRawValueRemainsRegistered() throws Exception {
        useHomePath();
        writeSystemApplicationProperties(CoreRuntimeParams.STARTUP_TRACE_MODE_KEY + "=verbose\n");

        ApplicationStartupConfig.load();
        StartupTrace trace = StartupTrace.create();

        assertEquals("verbose", RuntimeParamRegistry.getString(CoreRuntimeParams.STARTUP_TRACE_MODE_KEY));
        assertEquals(StartupTraceMode.TIME, trace.getMode());
    }

    @Test
    void chineseKeysAndValuesComeBackIntactBecauseTheFileIsReadAsUtf8() throws Exception {
        useHomePath();
        writeSystemApplicationProperties("custom.path=D:/资料/平台项目\ncustom.name=示例资料\n");

        ApplicationStartupConfig.load();

        assertEquals("D:/资料/平台项目", RuntimeParamRegistry.getString("custom.path"));
        assertEquals("示例资料", RuntimeParamRegistry.getString("custom.name"));
    }

    /** Editors on Chinese Windows often save GBK; bad bytes may spoil their own line but must not wipe the file. */
    @Test
    void bytesThatAreNotUtf8DoNotWipeTheRestOfTheFile() throws Exception {
        useHomePath();
        Path systemDir = Files.createDirectories(homePath.resolve("system"));
        byte[] gbkComment = "# 中文注释\n".getBytes(java.nio.charset.Charset.forName("GBK"));
        byte[] asciiEntry = "custom.example=value\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] both = new byte[gbkComment.length + asciiEntry.length];
        System.arraycopy(gbkComment, 0, both, 0, gbkComment.length);
        System.arraycopy(asciiEntry, 0, both, gbkComment.length, asciiEntry.length);
        Files.write(systemDir.resolve("application.properties"), both);

        ApplicationStartupConfig.load();

        assertEquals("value", RuntimeParamRegistry.getString("custom.example"));
    }

    private void useHomePath() {
        PathManager.setHomePathForTests(homePath);
    }

    private void writeSystemApplicationProperties(String content) throws Exception {
        Path systemDir = homePath.resolve("system");
        Files.createDirectories(systemDir);
        Files.writeString(systemDir.resolve("application.properties"), content);
    }
}
