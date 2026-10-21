package com.jdesk.frame.icon;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationInfo;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.app.ApplicationStartupConfig;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.frame.boot.JdeskApplication;
import com.jdesk.frame.runtime.params.FrameRuntimeParams;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.ui.scale.JdfScale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.Icon;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdfIconManagerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearGlobalState() {
        JdfFonts.resetForTests();
        JdfScale.resetForTests();

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
    }

    @Test
    void svgIconLoadsScalesAndCaches() {
        JdfIconManager manager = new JdfIconManager((Application) null);

        Icon first = manager.getIcon("icons/test-icon.svg", 24);
        Icon second = manager.getIcon("icons/test-icon.svg", 24);

        assertSame(first, second);
        assertEquals(24, first.getIconWidth());
        assertEquals(24, first.getIconHeight());
        assertTrue(countPaintedPixels(first) > 20);

        Icon resized = manager.getIcon("icons/test-icon.svg", 32);
        assertNotSame(first, resized);
        assertEquals(32, resized.getIconWidth());
    }

    /** Broken path data yields a cached placeholder of the same size instead of throwing on every lookup. */
    @Test
    void anIconThatFailsToParseFallsBackToACachedPlaceholder() {
        JdfIconManager manager = new JdfIconManager((Application) null);

        Icon first = manager.getIcon("icons/broken-path.svg", 16);

        assertEquals(16, first.getIconWidth());
        assertSame(first, manager.getIcon("icons/broken-path.svg", 16));
    }

    @Test
    void disabledAndTransparentVariantsKeepIconSize() {
        JdfIconManager manager = new JdfIconManager((Application) null);
        Icon icon = manager.getIcon("icons/test-icon.svg", 20);

        Icon disabled = manager.getDisabledIcon(icon);
        Icon transparent = manager.getTransparentIcon(icon, 0.5f);

        assertEquals(20, disabled.getIconWidth());
        assertEquals(20, disabled.getIconHeight());
        assertEquals(20, transparent.getIconWidth());
        assertEquals(20, transparent.getIconHeight());
    }

    @Test
    void highPixelScaleKeepsLogicalIconSize() throws Exception {
        writeApplicationProperties(FrameRuntimeParams.UI_SCALE_KEY + "=2.0\n");
        JdfScale.resetForTests();
        JdfIconManager manager = new JdfIconManager((Application) null);

        Icon icon = manager.getIcon("icons/test-icon.svg", 16);

        assertEquals(16, icon.getIconWidth());
        assertEquals(16, icon.getIconHeight());
        assertTrue(countPaintedPixels(icon) > 20);
    }

    @Test
    void applicationIconsUseApplicationInfoPaths() {
        PathManager.setHomePathForTests(tempDir.resolve("home"));
        JdeskApplication app = new JdeskApplication(JdfIconManagerTest.class.getClassLoader());
        app.init();
        app.getServiceContainer().registerInstance(ApplicationInfo.class,
                ApplicationInfo.builder()
                        .productName("Icon Test")
                        .fullProductName("Icon Test")
                        .iconSvgPath("icons/test-icon.svg")
                        .smallIconSvgPath("icons/test-icon.svg")
                        .build());

        JdfIconManager manager = new JdfIconManager(app);

        Icon small = manager.getSmallApplicationIcon();
        List<Image> windowImages = manager.getApplicationIconImages();

        assertEquals(22, small.getIconWidth());
        assertEquals(6, windowImages.size());
    }

    private static int countPaintedPixels(Icon icon) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            icon.paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }

        int painted = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    painted++;
                }
            }
        }
        return painted;
    }

    private void writeApplicationProperties(String content) throws Exception {
        Path homeDir = tempDir.resolve("home");
        PathManager.setHomePathForTests(homeDir);
        Path systemDir = homeDir.resolve("system");
        Files.createDirectories(systemDir);
        Files.writeString(systemDir.resolve("application.properties"), content);
        ApplicationStartupConfig.load();
    }
}
