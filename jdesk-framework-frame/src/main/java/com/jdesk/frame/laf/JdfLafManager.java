package com.jdesk.frame.laf;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.config.PersistentStateComponent;
import com.jdesk.core.config.State;
import com.jdesk.core.config.Storage;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.i18n.I18n;
import com.jdesk.frame.icon.JdfIconManager;
import com.jdesk.frame.ui.NativeTitleBar;
import com.jdesk.frame.ui.NativeWindowBackground;
import com.jdesk.frame.ui.components.JdfScrollBarUI;
import com.jdesk.frame.ui.components.JdfToggleIcon;
import com.jdesk.frame.ui.scale.JdfFonts;

import javax.swing.BorderFactory;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.basic.BasicPopupMenuUI;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Application service that manages JDesk themes on top of the current JDK look and feel. Switching a theme writes
 * the {@link JdfThemePalette} into the {@link UIManager} defaults, updates the dark-icon choice and refreshes the
 * component trees of all open windows. The theme ID is saved as a core persistent component, and the startup
 * sequence applies it through {@link #applyConfiguredTheme()}.
 */
@State(name = "JdfLafManager", storages = @Storage("jdf-laf.xml"))
public class JdfLafManager implements Disposable, PersistentStateComponent<JdfLafManager.StateData> {

    public static final String LIGHT = "light";
    public static final String DARK = "dark";
    public static final String DEFAULT_THEME_ID = LIGHT;

    private final Application application;

    /** In registration order, which is the order the settings page shows. */
    private final Map<String, JdfTheme> themes = new LinkedHashMap<>();

    private String currentThemeId = DEFAULT_THEME_ID;
    private String appliedThemeId;

    /**
     * Gives windows opened later the dark system title bar ({@link #installNativeTitleBarListener()}).
     * Installed on the EDT and removed in {@link #dispose()} on any thread, so both sides must synchronize on this.
     */
    private AWTEventListener nativeTitleBarListener;

    public JdfLafManager() {
        this(ApplicationManager.getApplicationIfCreated());
    }

    public JdfLafManager(Application application) {
        this.application = application;
        registerBuiltInThemes();
    }

    /** Returns a snapshot of the registered themes in registration order. */
    public synchronized List<JdfTheme> getThemes() {
        return List.copyOf(themes.values());
    }

    /** Finds a theme by ID. */
    public synchronized Optional<JdfTheme> findTheme(String themeId) {
        return Optional.ofNullable(themes.get(themeId));
    }

    /** Returns the configured theme, falling back to the default theme when it isn't registered. */
    public synchronized JdfTheme getCurrentTheme() {
        return resolveThemeOrDefault(currentThemeId);
    }

    /** Returns the configured theme ID. */
    public synchronized String getCurrentThemeId() {
        return currentThemeId;
    }

    /**
     * Registers a selectable theme; the settings page lists themes in registration order.
     *
     * @throws IllegalStateException if a theme with the same ID is already registered
     */
    public synchronized void registerTheme(JdfTheme theme) {
        Objects.requireNonNull(theme, "theme");
        JdfTheme existing = themes.putIfAbsent(theme.getId(), theme);
        if (existing != null) {
            throw new IllegalStateException("Theme already registered: " + theme.getId());
        }
    }

    /**
     * Removes a registered theme, such as a built-in one the application replaces, and returns whether it was there.
     * A removed current theme falls back to {@link #DEFAULT_THEME_ID}, or else the first registered, when next used.
     * After removing the built-in themes, call {@link #setCurrentTheme(String)} with your own default.
     *
     * @param themeId theme ID; legacy aliases are accepted as in {@link #findTheme(String)}
     */
    public synchronized boolean unregisterTheme(String themeId) {
        return themes.remove(requireText(themeId, "themeId")) != null;
    }

    /**
     * Applies the persisted or default theme, writing its colors into the UI defaults and refreshing open windows.
     * Call it after the persisted state is loaded.
     */
    public void applyConfiguredTheme() {
        JdfTheme theme;
        synchronized (this) {
            theme = resolveThemeOrDefault(currentThemeId);
            appliedThemeId = theme.getId();
        }
        installTheme(theme, true);
    }

    /**
     * Switches to a theme and applies it at once (UI defaults, dark-icon choice, all window component trees), then
     * publishes {@link JdfThemeListener} on the message bus. Returns false if the theme is already applied.
     *
     * @throws IllegalArgumentException if no theme has this ID
     */
    public boolean setCurrentTheme(String themeId) {
        JdfTheme oldTheme;
        JdfTheme newTheme;
        synchronized (this) {
            newTheme = themes.get(requireText(themeId, "themeId"));
            if (newTheme == null) {
                throw new IllegalArgumentException("Unknown frame theme: " + themeId);
            }
            if (newTheme.getId().equals(currentThemeId) && newTheme.getId().equals(appliedThemeId)) {
                return false;
            }
            oldTheme = resolveThemeOrDefault(appliedThemeId == null ? currentThemeId : appliedThemeId);
            currentThemeId = newTheme.getId();
            appliedThemeId = newTheme.getId();
        }

        installTheme(newTheme, true);
        publishThemeChanged(oldTheme, newTheme);
        return true;
    }

    @Override
    public synchronized StateData getState() {
        StateData state = new StateData();
        state.currentThemeId = currentThemeId;
        return state;
    }

    @Override
    public synchronized void loadState(StateData state) {
        currentThemeId = state == null || state.currentThemeId == null || state.currentThemeId.isBlank()
                ? DEFAULT_THEME_ID
                : state.currentThemeId;
    }

    @Override
    public StateData getDefaultState() {
        return new StateData();
    }

    @Override
    public synchronized void dispose() {
        themes.clear();
        if (nativeTitleBarListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(nativeTitleBarListener);
            nativeTitleBarListener = null;
        }
    }

    private void registerBuiltInThemes() {
        registerTheme(JdfTheme.of(
                LIGHT,
                () -> I18n.message("frame.theme.light"),
                false,
                JdfThemePalette.builder()
                        .background(new Color(251, 252, 252))
                        .panelBackground(Color.WHITE)
                        .cardBackground(Color.WHITE)
                        .surface(new Color(255, 255, 255))
                        .surfaceAlt(new Color(241, 244, 247))
                        .controlBackground(Color.WHITE)
                        .scrollbarThumb(new Color(201, 207, 210))
                        .hoverBackground(new Color(234, 246, 249))
                        .pressedBackground(new Color(211, 237, 243))
                        .activeBackground(new Color(226, 247, 251))
                        .accent(new Color(8, 153, 182))
                        .accentForeground(Color.WHITE)
                        .border(new Color(222, 229, 233))
                        .focus(new Color(8, 153, 182))
                        .text(new Color(16, 36, 46))
                        .mutedText(new Color(96, 115, 125))
                        .weakText(new Color(148, 160, 166))
                        .selectionBackground(new Color(226, 247, 251))
                        .selectionForeground(new Color(8, 67, 82))
                        .disabledText(new Color(148, 160, 166))
                        .success(new Color(36, 183, 120))
                        .warning(new Color(201, 135, 24))
                        .danger(new Color(219, 77, 92))
                        .build()));

        registerTheme(JdfTheme.of(
                DARK,
                () -> I18n.message("frame.theme.dark"),
                true,
                JdfThemePalette.builder()
                        .background(new Color(17, 24, 28))
                        .panelBackground(new Color(24, 33, 38))
                        .cardBackground(new Color(29, 39, 44))
                        .surface(new Color(24, 33, 38))
                        .surfaceAlt(new Color(31, 42, 48))
                        .controlBackground(new Color(35, 47, 53))
                        .scrollbarThumb(new Color(66, 82, 90))
                        .hoverBackground(new Color(40, 58, 66))
                        .pressedBackground(new Color(45, 72, 82))
                        .activeBackground(new Color(17, 78, 91))
                        .accent(new Color(53, 195, 221))
                        .accentForeground(new Color(5, 37, 45))
                        .border(new Color(55, 69, 76))
                        .focus(new Color(53, 195, 221))
                        .text(new Color(231, 238, 242))
                        .mutedText(new Color(157, 173, 181))
                        .weakText(new Color(101, 116, 123))
                        .selectionBackground(new Color(17, 78, 91))
                        .selectionForeground(new Color(223, 251, 255))
                        .disabledText(new Color(101, 116, 123))
                        .success(new Color(66, 205, 142))
                        .warning(new Color(228, 173, 67))
                        .danger(new Color(240, 100, 115))
                        .build()));
    }

    private JdfTheme resolveThemeOrDefault(String themeId) {
        JdfTheme theme = themes.get(themeId);
        if (theme != null) {
            return theme;
        }
        JdfTheme fallback = themes.get(DEFAULT_THEME_ID);
        if (fallback != null) {
            return fallback;
        }
        // The default may be unregistered too; don't return null while any theme remains, callers dereference it.
        return themes.values().stream().findFirst().orElse(null);
    }

    private void installTheme(JdfTheme theme, boolean updateWindows) {
        runOnEdtAndWait(() -> {
            writeUiDefaults(theme);
            updateIconTheme(theme);
            installNativeTitleBarListener();
            if (updateWindows) {
                updateAllWindows();
                NativeTitleBar.setDarkModeForAllWindows(theme.isDark());
            }
        });
    }

    /**
     * Gives windows created later, such as dialogs, the dark system title bar: a global window listener applies the
     * current mode when a window first opens, right after it gets its native handle. Installed once and removed in
     * {@link #dispose()}; off Windows, {@link NativeTitleBar} does nothing.
     */
    private synchronized void installNativeTitleBarListener() {
        if (nativeTitleBarListener != null) {
            return;
        }
        nativeTitleBarListener = event -> {
            if (event.getID() == WindowEvent.WINDOW_OPENED && event.getSource() instanceof Window window) {
                NativeTitleBar.setDarkMode(window, JdfColors.isDark());
                // JdfWindowFrame and MainFrame color their class brush in addNotify; this covers dialogs and the rest.
                NativeWindowBackground.apply(window, JdfColors.background());
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(nativeTitleBarListener, AWTEvent.WINDOW_EVENT_MASK);
    }

    private void updateIconTheme(JdfTheme theme) {
        if (application == null || application.isDisposed()
                || !application.getServiceContainer().hasService(JdfIconManager.class)) {
            return;
        }
        application.getService(JdfIconManager.class).setUseDarkIcons(theme.isDark());
    }

    private void writeUiDefaults(JdfTheme theme) {
        JdfThemePalette palette = theme.getPalette();
        UIDefaults defaults = UIManager.getDefaults();
        palette.asMap().forEach(defaults::put);
        defaults.put(JdfThemePalette.DARK, theme.isDark());

        Color background = palette.getColor(JdfThemePalette.BACKGROUND);
        Color panelBackground = palette.getColor(JdfThemePalette.PANEL_BACKGROUND);
        Color surface = palette.getColor(JdfThemePalette.SURFACE);
        Color controlBackground = palette.getColor(JdfThemePalette.CONTROL_BACKGROUND);
        Color hoverBackground = palette.getColor(JdfThemePalette.HOVER_BACKGROUND);
        Color activeBackground = palette.getColor(JdfThemePalette.ACTIVE_BACKGROUND);
        Color text = palette.getColor(JdfThemePalette.TEXT);
        Color mutedText = palette.getColor(JdfThemePalette.MUTED_TEXT);
        Color selectionBackground = palette.getColor(JdfThemePalette.SELECTION_BACKGROUND);
        Color selectionForeground = palette.getColor(JdfThemePalette.SELECTION_FOREGROUND);
        Color disabledText = palette.getColor(JdfThemePalette.DISABLED_TEXT);

        putAll(defaults, panelBackground,
                "Panel.background",
                "MenuBar.background",
                "Menu.background",
                "PopupMenu.background",
                "ToolBar.background",
                "OptionPane.background");
        putAll(defaults, background,
                "Viewport.background",
                "ScrollPane.background",
                "TabbedPane.background",
                "Desktop.background");
        putAll(defaults, surface,
                "TableHeader.background");
        putAll(defaults, controlBackground,
                "Button.background",
                "ToggleButton.background",
                "TextField.background",
                "TextArea.background",
                "PasswordField.background",
                "ComboBox.background",
                "List.background",
                "Table.background",
                "Tree.background",
                "Spinner.background");
        putAll(defaults, hoverBackground,
                "Button.hoverBackground",
                "ToggleButton.hoverBackground",
                "MenuItem.selectionBackground");
        putAll(defaults, activeBackground,
                "Button.focus",
                "ToggleButton.focus");
        putAll(defaults, text,
                "Label.foreground",
                "Button.foreground",
                "ToggleButton.foreground",
                "TextField.foreground",
                "TextField.caretForeground",
                "TextArea.foreground",
                "TextArea.caretForeground",
                "PasswordField.foreground",
                "PasswordField.caretForeground",
                "ComboBox.foreground",
                "List.foreground",
                "Table.foreground",
                "Tree.foreground",
                "Menu.foreground",
                "MenuItem.foreground",
                "TabbedPane.foreground");
        putAll(defaults, mutedText,
                "TitledBorder.titleColor",
                "Separator.foreground");
        putAll(defaults, disabledText,
                "Label.disabledForeground",
                "Button.disabledText",
                "TextField.inactiveForeground",
                "TextArea.inactiveForeground",
                "ComboBox.disabledForeground");
        putAll(defaults, selectionBackground,
                "TextField.selectionBackground",
                "TextArea.selectionBackground",
                "PasswordField.selectionBackground",
                "ComboBox.selectionBackground",
                "List.selectionBackground",
                "Table.selectionBackground",
                "Tree.selectionBackground");
        putAll(defaults, selectionForeground,
                "TextField.selectionForeground",
                "TextArea.selectionForeground",
                "PasswordField.selectionForeground",
                "ComboBox.selectionForeground",
                "List.selectionForeground",
                "Table.selectionForeground",
                "Tree.selectionForeground");
        writeNativelyPaintedControlDefaults(defaults, palette);
        JdfFonts.installDefaults();
    }

    /**
     * Themes the controls the base look and feel paints natively. Tooltips only need the right keys, but scroll bars,
     * check boxes and popup menus ignore {@code ScrollBar.*} and {@code PopupMenu.background} under the system visual
     * style, so their UI delegates or icons are replaced too, which makes them flat in light themes as well.
     */
    private static void writeNativelyPaintedControlDefaults(UIDefaults defaults, JdfThemePalette palette) {
        Color panelBackground = palette.getColor(JdfThemePalette.PANEL_BACKGROUND);
        Color surfaceAlt = palette.getColor(JdfThemePalette.SURFACE_ALT);
        Color scrollbarThumb = palette.getColor(JdfThemePalette.SCROLLBAR_THUMB);
        Color border = palette.getColor(JdfThemePalette.BORDER);
        Color text = palette.getColor(JdfThemePalette.TEXT);

        defaults.put("ScrollBarUI", JdfScrollBarUI.class.getName());
        putAll(defaults, palette.getColor(JdfThemePalette.BACKGROUND),
                "ScrollBar.background",
                "ScrollBar.track");
        putAll(defaults, scrollbarThumb,
                "ScrollBar.thumb",
                "ScrollBar.thumbDarkShadow",
                "ScrollBar.thumbHighlight",
                "ScrollBar.thumbShadow");

        defaults.put("CheckBox.icon", JdfToggleIcon.checkBox());
        defaults.put("RadioButton.icon", JdfToggleIcon.radioButton());
        putAll(defaults, panelBackground,
                "CheckBox.background",
                "RadioButton.background",
                "CheckBoxMenuItem.background",
                "RadioButtonMenuItem.background");
        putAll(defaults, text,
                "CheckBox.foreground",
                "RadioButton.foreground",
                "CheckBoxMenuItem.foreground",
                "RadioButtonMenuItem.foreground");

        // The native skin paints popup menus and ignores their background keys; the Basic UI follows the theme.
        defaults.put("PopupMenuUI", BasicPopupMenuUI.class.getName());

        putAll(defaults, surfaceAlt, "ToolTip.background");
        putAll(defaults, text, "ToolTip.foreground");
        defaults.put("ToolTip.border", new BorderUIResource(BorderFactory.createLineBorder(border)));
    }

    /** Must use {@link ColorUIResource}: components keep a plain color as explicit across theme switches. */
    private static void putAll(UIDefaults defaults, Color value, String... keys) {
        ColorUIResource resource = new ColorUIResource(value);
        for (String key : keys) {
            defaults.put(key, resource);
        }
    }

    /** Reinstalls fonts after a language change and refreshes all windows, without publishing a theme change. */
    public void refreshFontsForLanguage() {
        runOnEdtAndWait(() -> {
            JdfFonts.installDefaults();
            updateAllWindows();
        });
    }

    private void updateAllWindows() {
        Color windowBackground = JdfColors.background();
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
            // Refresh the AWT window background, or minimize and restore animations keep the old theme's color.
            window.setBackground(windowBackground);
            // Recolor the shared Win32 class brush; calling it once per window is idempotent.
            NativeWindowBackground.apply(window, windowBackground);
            window.invalidate();
            window.validate();
            window.repaint();
        }
    }

    private void publishThemeChanged(JdfTheme oldTheme, JdfTheme newTheme) {
        if (application == null || application.isDisposed()) {
            return;
        }
        application.getMessageBus()
                .syncPublisher(JdfThemeListener.class)
                .themeChanged(oldTheme, newTheme);
    }

    private static void runOnEdtAndWait(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during frame UI update", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Frame UI update failed", cause);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    /** Persisted state: the current theme ID. */
    public static final class StateData {
        public String currentThemeId = DEFAULT_THEME_ID;
    }
}
