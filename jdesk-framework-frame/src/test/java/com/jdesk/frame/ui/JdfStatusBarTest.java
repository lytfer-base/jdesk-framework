package com.jdesk.frame.ui;

import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.UiLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JdfStatusBarTest {

    @BeforeEach
    void registerFrameMessages() {
        I18n.resetForTests();
        I18n.registerRoot("jdesk/i18n", JdfStatusBarTest.class.getClassLoader(), UiLanguage.EN);
    }

    @AfterEach
    void resetMessages() {
        I18n.resetForTests();
    }

    @Test
    void presentationWidgetCanBeAddedReplacedAndRemoved() {
        JdfStatusBar statusBar = new JdfStatusBar();

        statusBar.addWidget(new PresentationStatusBarWidget(
                "mode",
                JdfStatusBar.Position.RIGHT,
                10,
                JdfStatusBarWidgetPresentation.builder().text("Local").tooltip("Runtime mode").build()));

        JLabel first = (JLabel) statusBar.getWidget("mode");
        assertNotNull(first);
        assertEquals("Local", first.getText());
        assertEquals("Runtime mode", first.getToolTipText());

        statusBar.addWidget(new PresentationStatusBarWidget(
                "mode",
                JdfStatusBar.Position.RIGHT,
                10,
                JdfStatusBarWidgetPresentation.builder().text("Cloud").build()));

        JLabel replacement = (JLabel) statusBar.getWidget("mode");
        assertEquals("Cloud", replacement.getText());

        statusBar.removeWidget("mode");
        assertNull(statusBar.getWidget("mode"));
        assertNull(statusBar.getStatusBarWidget("mode"));
    }

    @Test
    void widgetsAreOrderedInsideTheirStatusBarZone() {
        JdfStatusBar statusBar = new JdfStatusBar();

        statusBar.addWidget(new PresentationStatusBarWidget(
                "late",
                JdfStatusBar.Position.LEFT,
                20,
                JdfStatusBarWidgetPresentation.builder().text("Late").build()));
        statusBar.addWidget(new PresentationStatusBarWidget(
                "early",
                JdfStatusBar.Position.LEFT,
                10,
                JdfStatusBarWidgetPresentation.builder().text("Early").build()));

        JPanel leftPanel = (JPanel) statusBar.getComponent(0);

        assertEquals(I18n.message("frame.statusBar.ready"), ((JLabel) leftPanel.getComponent(0)).getText().trim());
        assertEquals("Early", ((JLabel) leftPanel.getComponent(1)).getText());
        assertEquals("Late", ((JLabel) leftPanel.getComponent(2)).getText());
    }
}
