package com.jdesk.frame.ui.scale;

import com.jdesk.core.app.ApplicationStartupConfig;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.core.startup.StartupTrace;
import com.jdesk.frame.runtime.params.FrameRuntimeParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdfScaleTest {

    @TempDir
    Path homePath;

    @AfterEach
    void clearScaleProperties() {
        PathManager.resetForTests();
        RuntimeParamRegistry.reset();
        JdfFonts.resetForTests();
        JdfScale.resetForTests();
    }

    @Test
    void defaultUserScaleIsOneWithoutExplicitProperty() {
        PathManager.setHomePathForTests(homePath);
        JdfScale.resetForTests();

        assertEquals(1.0f, JdfScale.userScale(), 0.001f);
    }

    @Test
    void configuredUserScaleIsAppliedToLayoutSizes() throws Exception {
        writeApplicationProperties(FrameRuntimeParams.UI_SCALE_KEY + "=1.25\n");
        JdfScale.resetForTests();

        assertEquals(1.25f, JdfScale.userScale(), 0.001f);
        assertEquals(20, JdfScale.scale(16));
    }

    @Test
    void invalidUserScaleFallsBackToOne() throws Exception {
        writeApplicationProperties(FrameRuntimeParams.UI_SCALE_KEY + "=8\n");
        JdfScale.resetForTests();

        assertEquals(1.0f, JdfScale.userScale(), 0.001f);

        writeApplicationProperties(FrameRuntimeParams.UI_SCALE_KEY + "=bad\n");
        JdfScale.resetForTests();

        assertEquals(1.0f, JdfScale.userScale(), 0.001f);
    }

    @Test
    void systemFontCanDriveUserScaleWhenNoExplicitPropertyIsSet() {
        PathManager.setHomePathForTests(homePath);
        JdfScale.resetForTests();

        JdfScale.updateFromSystemFont(new Font(Font.DIALOG, Font.PLAIN, 15));

        assertEquals(1.25f, JdfScale.userScale(), 0.001f);
        assertEquals(20, JdfScale.scale(16));
    }

    @Test
    void fontInstallationReportsFineGrainedStartupSpans() {
        StartupTrace trace = StartupTrace.report();

        JdfFonts.installDefaults(trace);

        assertTrue(trace.getEvents().stream()
                .anyMatch(event -> "frame.fonts.detectSystemFont".equals(event.getName())));
        assertTrue(trace.getEvents().stream()
                .anyMatch(event -> "frame.fonts.installUiDefaults".equals(event.getName())));
    }

    private void writeApplicationProperties(String content) throws Exception {
        PathManager.setHomePathForTests(homePath);
        Path systemDir = homePath.resolve("system");
        Files.createDirectories(systemDir);
        Files.writeString(systemDir.resolve("application.properties"), content);
        ApplicationStartupConfig.load();
    }
}
