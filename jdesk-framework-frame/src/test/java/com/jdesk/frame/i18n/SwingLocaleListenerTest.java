package com.jdesk.frame.i18n;

import com.jdesk.core.app.Application;
import com.jdesk.core.i18n.UiLanguage;
import com.jdesk.frame.laf.JdfLafManager;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.ui.scale.JdfScale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SwingLocaleListenerTest {
    private final Locale savedComponent = JComponent.getDefaultLocale();
    private final Locale savedDefaults = UIManager.getDefaults().getDefaultLocale();

    @AfterEach
    void restore() throws Exception {
        // Drain the EDT first: a listener task left queued by a failure must not write en_US back after the restore.
        SwingUtilities.invokeAndWait(() -> { });
        JComponent.setDefaultLocale(savedComponent);
        UIManager.getDefaults().setDefaultLocale(savedDefaults);
        JdfFonts.resetForTests();
        JdfFonts.installDefaults();
        JdfFonts.resetForTests();
        JdfScale.resetForTests();
    }

    @Test
    void runtimeSwitchIsAppliedOnTheEdtAfterThePublisherReturns() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Assumptions.assumeTrue(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)).contains("Microsoft YaHei UI"));
        JComponent.setDefaultLocale(Locale.CHINA);
        JdfFonts.resetForTests();
        JdfFonts.installDefaults();
        assertEquals("Microsoft YaHei UI", UIManager.getFont("Label.font").getFamily(Locale.ROOT), "precondition: CJK font");
        SwingLocaleListener listener = new SwingLocaleListener(new JdfLafManager((Application) null));

        CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> awaitQuietly(release));
        try {
            listener.localeChanged(UiLanguage.ZH_CN, UiLanguage.EN);
            assertEquals(Locale.CHINA, JComponent.getDefaultLocale(), "Swing must change on the EDT, not this thread");
        } finally {
            release.countDown();
        }
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(Locale.US, JComponent.getDefaultLocale());
        assertEquals(Locale.US, UIManager.getDefaults().getDefaultLocale());
        assertEquals("Segoe UI", UIManager.getFont("Label.font").getFamily(Locale.ROOT), "the font family must follow the language");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
