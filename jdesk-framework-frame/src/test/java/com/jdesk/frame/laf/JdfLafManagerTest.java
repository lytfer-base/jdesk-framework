package com.jdesk.frame.laf;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.app.ApplicationStartupConfig;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.config.ComponentStore;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.frame.boot.JdeskApplication;
import com.jdesk.frame.runtime.params.FrameRuntimeParams;
import com.jdesk.frame.ui.NativeTitleBar;
import com.jdesk.frame.ui.components.JdfScrollBarUI;
import com.jdesk.frame.ui.components.JdfToggleIcon;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.ui.scale.JdfScale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class JdfLafManagerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearGlobalState() {
        JdfFonts.resetForTests();
        JdfScale.resetForTests();

        // Must dispose: applyConfiguredTheme hangs a window listener owned by this instance on the Toolkit.
        JdfLafManager reset = new JdfLafManager((Application) null);
        reset.applyConfiguredTheme();
        reset.dispose();

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
    void builtInThemesAreRegisteredWithLightDefault() {
        JdfLafManager manager = new JdfLafManager((Application) null);

        assertEquals(JdfLafManager.LIGHT, manager.getCurrentThemeId());
        assertEquals(2, manager.getThemes().size());
        assertFalse(manager.findTheme(JdfLafManager.LIGHT).orElseThrow().isDark());
        assertTrue(manager.findTheme(JdfLafManager.DARK).orElseThrow().isDark());
    }

    @Test
    void paletteBuilderRejectsMissingNewUiTokens() {
        JdfThemePalette.Builder builder = JdfThemePalette.builder()
                .background(Color.WHITE)
                .panelBackground(Color.WHITE)
                .cardBackground(Color.WHITE)
                .controlBackground(Color.WHITE)
                .accent(Color.BLUE)
                .accentForeground(Color.WHITE)
                .border(Color.GRAY)
                .text(Color.BLACK)
                .mutedText(Color.DARK_GRAY)
                .selectionBackground(Color.LIGHT_GRAY)
                .selectionForeground(Color.BLACK)
                .disabledText(Color.GRAY);

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void nativelyPaintedControlsAreRoutedThroughThePalette() {
        JdfLafManager manager = new JdfLafManager((Application) null);
        manager.setCurrentTheme(JdfLafManager.DARK);
        JdfThemePalette palette = manager.getCurrentTheme().getPalette();

        // Scroll bars and check glyphs need replaced painters: the Windows LaF paints them natively, ignoring keys.
        assertEquals(JdfScrollBarUI.class.getName(), UIManager.getString("ScrollBarUI"));
        assertTrue(UIManager.get("CheckBox.icon") instanceof JdfToggleIcon);
        assertTrue(UIManager.get("RadioButton.icon") instanceof JdfToggleIcon);
        assertEquals(palette.getColor(JdfThemePalette.SCROLLBAR_THUMB), UIManager.getColor("ScrollBar.thumb"));
        assertEquals(palette.getColor(JdfThemePalette.SURFACE_ALT), UIManager.getColor("ToolTip.background"));
        assertEquals(palette.getColor(JdfThemePalette.TEXT), UIManager.getColor("ToolTip.foreground"));
    }

    /**
     * Checks painted pixels, since asserting UIDefaults can't catch a base LaF that ignores them. Tests run on Metal,
     * not the Windows LaF, so this requires palette colors whatever LaF is underneath.
     */
    @Test
    void nativelyPaintedControlsActuallyPaintPaletteColors() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "offscreen painting needs a graphics environment");
        JdfLafManager manager = new JdfLafManager((Application) null);
        manager.setCurrentTheme(JdfLafManager.DARK);
        JdfThemePalette palette = manager.getCurrentTheme().getPalette();

        JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL, 0, 40, 0, 100);
        assertEquals(palette.getColor(JdfThemePalette.SCROLLBAR_THUMB), pixelAt(bar, 14, 120, 7, 4),
                "the scroll bar thumb ignores the palette; the UI delegate was probably not replaced");

        JCheckBox unchecked = new JCheckBox();
        assertEquals(palette.getColor(JdfThemePalette.CONTROL_BACKGROUND), pixelAt(unchecked, 22, 22, 8, 11),
                "the unchecked box ignores the palette; WindowsIconFactory probably still paints it");
    }

    /**
     * Must run under the system LaF: Metal fills with getBackground() and always passes, while the Windows LaF with
     * visual styles paints a native skin that ignores UIDefaults.
     */
    @Test
    void popupMenusPaintPaletteColorsUnderTheSystemLaf() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "offscreen painting needs a graphics environment");
        LookAndFeel previous = UIManager.getLookAndFeel();
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            JdfLafManager manager = new JdfLafManager((Application) null);
            manager.setCurrentTheme(JdfLafManager.DARK);
            JdfThemePalette palette = manager.getCurrentTheme().getPalette();

            assertEquals(palette.getColor(JdfThemePalette.PANEL_BACKGROUND), pixelAt(new JPopupMenu(), 160, 40, 80, 20),
                    "the popup menu ignores the palette; the UI delegate probably fell back to the native skin");
        }
        catch (ReflectiveOperationException | UnsupportedLookAndFeelException failure) {
            throw new AssertionError("could not install the system LaF", failure);
        }
        finally {
            assertDoesNotThrow(() -> UIManager.setLookAndFeel(previous));
        }
    }

    private static Color pixelAt(JComponent component, int width, int height, int x, int y) {
        component.setSize(width, height);
        component.doLayout();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            component.paint(g);
        }
        finally {
            g.dispose();
        }
        return new Color(image.getRGB(x, y));
    }

    /**
     * The AWT window background fills the native surface while a window opens; left at the Windows default white, a
     * dark theme flashes grey on every open. Swing backgrounds can't help, since Swing hasn't painted yet.
     */
    @Test
    void windowFramesGetAwtBackgroundFromThePalette() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "creating a window needs a graphics environment");
        JdfLafManager manager = new JdfLafManager((Application) null);
        manager.setCurrentTheme(JdfLafManager.DARK);
        Color dark = manager.getCurrentTheme().getPalette().getColor(JdfThemePalette.BACKGROUND);

        com.jdesk.frame.ui.JdfWindowFrame frame = new com.jdesk.frame.ui.JdfWindowFrame("probe");
        try {
            assertEquals(dark, frame.getBackground(), "set the theme background in the constructor, before first show");

            manager.setCurrentTheme(JdfLafManager.LIGHT);
            Color light = manager.getCurrentTheme().getPalette().getColor(JdfThemePalette.BACKGROUND);
            assertEquals(light, frame.getBackground(), "a theme switch must update the AWT background of open windows");
        }
        finally {
            frame.dispose();
        }
    }

    /**
     * Windows opened later get their dark native title bar from this listener; it must be removed with the manager,
     * or every JdfLafManager leaves an orphan on the Toolkit.
     */
    @Test
    void nativeTitleBarListenerIsOwnedByTheManager() {
        int before = windowListenerCount();
        JdfLafManager manager = new JdfLafManager((Application) null);
        manager.applyConfiguredTheme();
        assertEquals(before + 1, windowListenerCount(), "applying the theme should add exactly one window listener");

        manager.applyConfiguredTheme();
        assertEquals(before + 1, windowListenerCount(), "applying it again should not add another");

        manager.dispose();
        assertEquals(before, windowListenerCount(), "dispose should remove it");
    }

    @Test
    void nativeTitleBarIgnoresWindowsWithoutNativeHandle() {
        // Without a native handle there is no HWND, as on other platforms or before addNotify.
        assertDoesNotThrow(() -> NativeTitleBar.setDarkMode(null, true));
        assertDoesNotThrow(() -> NativeTitleBar.setDarkModeForAllWindows(true));
    }

    private static int windowListenerCount() {
        return Toolkit.getDefaultToolkit().getAWTEventListeners(AWTEvent.WINDOW_EVENT_MASK).length;
    }

    @Test
    void duplicateThemeIdsAreRejected() {
        JdfLafManager manager = new JdfLafManager((Application) null);
        JdfTheme duplicate = JdfTheme.of(
                JdfLafManager.LIGHT,
                "Duplicate",
                false,
                manager.getCurrentTheme().getPalette());

        assertThrows(IllegalStateException.class, () -> manager.registerTheme(duplicate));
    }

    @Test
    void switchingThemeUpdatesUiManagerTokens() {
        JdfLafManager manager = new JdfLafManager((Application) null);
        manager.applyConfiguredTheme();
        Color lightBackground = UIManager.getColor(JdfThemePalette.BACKGROUND);

        boolean changed = manager.setCurrentTheme(JdfLafManager.DARK);

        assertTrue(changed);
        assertTrue(JdfColors.isDark());
        assertEquals(new Color(17, 24, 28), JdfColors.background());
        assertNotEquals(lightBackground, UIManager.getColor(JdfThemePalette.BACKGROUND));
    }

    /** Components created before a theme switch take the new theme's colors rather than keeping the old ones. */
    @Test
    void componentsCreatedBeforeASwitchTakeTheNewThemeColors() throws Exception {
        JdfLafManager manager = new JdfLafManager((Application) null);
        manager.applyConfiguredTheme();
        JPanel panel = new JPanel();
        JLabel label = new JLabel("x");
        SwingUtilities.invokeAndWait(() -> {
            panel.updateUI();
            label.updateUI();
        });

        manager.setCurrentTheme(JdfLafManager.DARK);
        SwingUtilities.invokeAndWait(() -> {
            SwingUtilities.updateComponentTreeUI(panel);
            SwingUtilities.updateComponentTreeUI(label);
        });

        assertEquals(JdfColors.panelBackground(), panel.getBackground());
        assertEquals(JdfColors.text(), label.getForeground());
    }

    @Test
    void scaledFontDefaultsSurviveThemeSwitch() throws Exception {
        writeApplicationProperties(FrameRuntimeParams.UI_SCALE_KEY + "=1.25\n");
        JdfFonts.resetForTests();
        JdfScale.resetForTests();
        installDeterministicBaseFonts();
        JdfLafManager manager = new JdfLafManager((Application) null);

        manager.applyConfiguredTheme();

        assertScaledUiFont("Label.font");
        assertScaledUiFont("Button.font");
        assertScaledUiFont("Menu.font");
        assertScaledUiFont("Tree.font");
        int labelSize = UIManager.getFont("Label.font").getSize();

        assertTrue(manager.setCurrentTheme(JdfLafManager.DARK));

        assertEquals(labelSize, UIManager.getFont("Label.font").getSize());
        assertScaledUiFont("Label.font");
        assertScaledUiFont("Button.font");
        assertScaledUiFont("Menu.font");
        assertScaledUiFont("Tree.font");
    }

    @Test
    void themeChangeIsPublishedOnlyForActualChange() {
        PathManager.setHomePathForTests(tempDir);
        JdeskApplication app = new JdeskApplication(JdfLafManagerTest.class.getClassLoader());
        app.init();
        JdfLafManager manager = app.getService(JdfLafManager.class);
        manager.applyConfiguredTheme();

        List<String> events = new ArrayList<>();
        app.getMessageBus().connect().subscribe(JdfThemeListener.class,
                (oldTheme, newTheme) -> events.add(oldTheme.getId() + "->" + newTheme.getId()));

        assertFalse(manager.setCurrentTheme(JdfLafManager.LIGHT));
        assertTrue(manager.setCurrentTheme(JdfLafManager.DARK));
        assertFalse(manager.setCurrentTheme(JdfLafManager.DARK));

        assertEquals(List.of(JdfLafManager.LIGHT + "->" + JdfLafManager.DARK),
                events);
    }

    @Test
    void currentThemeIsPersistedThroughComponentStore() {
        ComponentStore store = new ComponentStore(tempDir.resolve("config"));
        JdfLafManager manager = new JdfLafManager((Application) null);
        store.register(manager);

        manager.setCurrentTheme(JdfLafManager.DARK);
        store.saveAll();

        ComponentStore reloadedStore = new ComponentStore(tempDir.resolve("config"));
        JdfLafManager reloaded = new JdfLafManager((Application) null);
        reloadedStore.register(reloaded);

        assertEquals(JdfLafManager.DARK, reloaded.getCurrentThemeId());

        reloaded.applyConfiguredTheme();
        assertTrue(JdfColors.isDark());
    }

    @Test
    void persistedCandidateIsAppliedSeparatelyAndPublishesThePreviouslyInstalledTheme() throws Exception {
        PathManager.setHomePathForTests(tempDir);
        JdeskApplication app = new JdeskApplication(JdfLafManagerTest.class.getClassLoader());
        app.init();
        JdfLafManager manager = app.getService(JdfLafManager.class);
        manager.applyConfiguredTheme();
        ComponentStore store = new ComponentStore(tempDir.resolve("settings-config"));
        store.register(manager);
        List<String> events = new ArrayList<>();
        app.getMessageBus().connect().subscribe(JdfThemeListener.class,
                (oldTheme, newTheme) -> events.add(oldTheme.getId() + "->" + newTheme.getId()));
        JdfLafManager.StateData candidate = new JdfLafManager.StateData();
        candidate.currentThemeId = JdfLafManager.DARK;

        store.saveState(manager, candidate);

        assertEquals(JdfLafManager.DARK, manager.getCurrentThemeId());
        assertFalse(JdfColors.isDark(), "Persisting configuration must not install the UI theme");
        assertTrue(events.isEmpty());
        assertTrue(manager.setCurrentTheme(JdfLafManager.DARK));
        assertTrue(JdfColors.isDark());
        assertEquals(List.of("light->dark"), events);
        assertFalse(manager.setCurrentTheme(JdfLafManager.DARK));
        assertEquals(1, events.size());
    }

    @Test
    void unregisterThemeRemovesItFromTheList() {
        JdfLafManager manager = new JdfLafManager((Application) null);
        assertTrue(manager.findTheme(JdfLafManager.LIGHT).isPresent());

        assertTrue(manager.unregisterTheme(JdfLafManager.LIGHT));

        assertTrue(manager.findTheme(JdfLafManager.LIGHT).isEmpty());
        assertEquals(1, manager.getThemes().size());
    }

    @Test
    void unregisterThemeReturnsFalseForUnknownId() {
        JdfLafManager manager = new JdfLafManager((Application) null);

        assertFalse(manager.unregisterTheme("no-such-theme"));
    }

    /**
     * An application may replace the built-in light and dark themes, and DEFAULT_THEME_ID is light; if the fallback
     * breaks, getCurrentTheme() returns null and its callers fail with an NPE.
     */
    @Test
    void currentThemeStaysResolvableAfterTheDefaultThemeIsUnregistered() {
        JdfLafManager manager = new JdfLafManager((Application) null);
        JdfThemePalette palette = manager.getCurrentTheme().getPalette();
        manager.registerTheme(JdfTheme.of("product-light", "产品浅色", false, palette));
        manager.unregisterTheme(JdfLafManager.LIGHT);
        manager.unregisterTheme(JdfLafManager.DARK);

        JdfTheme current = manager.getCurrentTheme();

        assertNotNull(current, "getCurrentTheme() must not return null while themes are registered");
        assertEquals("product-light", current.getId());
        assertDoesNotThrow(current::getPalette);
    }

    @Test
    void unregisteredIdCanBeRegisteredAgain() {
        JdfLafManager manager = new JdfLafManager((Application) null);
        JdfThemePalette palette = manager.getCurrentTheme().getPalette();
        manager.unregisterTheme(JdfLafManager.LIGHT);

        manager.registerTheme(JdfTheme.of(JdfLafManager.LIGHT, "自定义浅色", false, palette));

        assertEquals("自定义浅色", manager.findTheme(JdfLafManager.LIGHT).orElseThrow().getDisplayName());
    }

    private static void installDeterministicBaseFonts() {
        FontUIResource base = new FontUIResource(new Font(Font.DIALOG, Font.PLAIN, 12));
        UIManager.put("Label.font", base);
        UIManager.put("Button.font", base);
        UIManager.put("Menu.font", base);
        UIManager.put("Tree.font", base);
    }

    private static void assertScaledUiFont(String key) {
        assertTrue(UIManager.getFont(key) instanceof FontUIResource);
        assertEquals(JdfScale.scaleFontSize(12f), UIManager.getFont(key).getSize());
    }

    private void writeApplicationProperties(String content) throws Exception {
        PathManager.setHomePathForTests(tempDir);
        Path systemDir = tempDir.resolve("system");
        Files.createDirectories(systemDir);
        Files.writeString(systemDir.resolve("application.properties"), content);
        ApplicationStartupConfig.load();
    }
}
