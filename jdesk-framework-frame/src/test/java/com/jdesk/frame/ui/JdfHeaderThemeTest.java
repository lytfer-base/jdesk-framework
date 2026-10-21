package com.jdesk.frame.ui;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.frame.boot.JdeskApplication;
import com.jdesk.frame.laf.JdfColors;
import com.jdesk.frame.laf.JdfLafManager;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.ui.scale.JdfScale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Tests that the header follows runtime theme switches and drops its subscription on dispose. Its background is a
 * plain {@code Color}, which {@code SwingUtilities.updateComponentTreeUI} doesn't replace.
 */
class JdfHeaderThemeTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearGlobalState() {
        new JdfLafManager((Application) null).applyConfiguredTheme();
        Application app = ApplicationManager.getApplicationIfCreated();
        if (app != null) {
            if (!app.isDisposed()) {
                app.shutdown();
            }
            ApplicationManager.clearApplication(app);
        }
        Disposer.clearAll();
        RuntimeParamRegistry.reset();
        PathManager.resetForTests();
        JdfFonts.resetForTests();
        JdfScale.resetForTests();
    }

    @Test
    void headerFollowsRuntimeThemeSwitchAndUnsubscribesOnDispose() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        PathManager.setHomePathForTests(tempDir);
        JdeskApplication app = new JdeskApplication(JdfHeaderThemeTest.class.getClassLoader());
        app.init();
        JdfLafManager manager = app.getService(JdfLafManager.class);
        manager.applyConfiguredTheme();

        JdfWindowFrame frame = new JdfWindowFrame("主题跟随测试");
        JdfFrameShell shell = JdfFrameShell.install(frame, app, JdfHeaderOptions.defaults());
        frame.pack(); // Triggers addNotify, which subscribes.
        Color lightBackground = shell.header().getBackground();

        manager.setCurrentTheme(JdfLafManager.DARK);
        flushEdt();

        assertNotEquals(lightBackground, shell.header().getBackground(),
                "the header didn't follow the theme; its plain-color background needs JdfThemeListener");
        assertEquals(JdfColors.background(), shell.header().getBackground());

        // removeNotify must release the subscription, or every window opened leaks one.
        frame.dispose();
        Color afterDispose = shell.header().getBackground();

        manager.setCurrentTheme(JdfLafManager.LIGHT);
        flushEdt();

        assertEquals(afterDispose, shell.header().getBackground(),
                "a disposed window still reacts to theme events; removeNotify didn't release the bus connection");
    }

    /** Theme events arrive through invokeLater, so the EDT queue must drain before asserting. */
    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }
}
