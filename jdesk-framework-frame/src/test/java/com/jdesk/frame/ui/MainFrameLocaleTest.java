package com.jdesk.frame.ui;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.UiLanguage;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.frame.boot.JdeskApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Tests that MainFrame resets its default texts on a language switch; the status bar changes only while it still
 * shows the default text.
 */
class MainFrameLocaleTest {

    @BeforeEach
    void registerFrameMessages() {
        I18n.resetForTests();
        I18n.registerRoot("jdesk/i18n", MainFrameLocaleTest.class.getClassLoader(), UiLanguage.EN);
    }

    @AfterEach
    void clearGlobalState() {
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
        I18n.resetForTests();
    }

    @Test
    void defaultTextsFollowALanguageSwitchButStatusSetByTheApplicationStays(@TempDir Path homeDir) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        PathManager.setHomePathForTests(homeDir);
        Application app = new JdeskApplication(MainFrameLocaleTest.class.getClassLoader());
        app.init();
        I18n.initialize(UiLanguage.ZH_CN);
        AtomicReference<MainFrame> frame = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.set(new MainFrame(app, "Locale Test"));
                assertEquals("就绪", frame.get().getJdfStatusBar().getInfo());
            });

            I18n.switchTo(UiLanguage.EN);

            SwingUtilities.invokeAndWait(() -> {
                MainFrame open = frame.get();
                assertEquals("Ready", open.getJdfStatusBar().getInfo());
                assertEquals("Ready", ((JLabel) open.getContentPanel().getComponent(0)).getText());
                TitledBorder leftTools = (TitledBorder) open.getToolWindowPanel(JdfToolWindowArea.LEFT).getBorder();
                assertEquals("Left Tools", leftTools.getTitle());
                open.setStatusText("Indexing");
            });

            I18n.switchTo(UiLanguage.JA);

            SwingUtilities.invokeAndWait(() -> assertEquals("Indexing", frame.get().getJdfStatusBar().getInfo(),
                    "status text set by the application must survive a language switch"));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (frame.get() != null) {
                    frame.get().dispose();
                }
            });
        }
    }
}
