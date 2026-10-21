package com.jdesk.frame.jbr;

import com.jetbrains.JBR;
import com.jetbrains.WindowDecorations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Frame;
import java.util.Collections;
import java.util.Map;

/**
 * Thin adapter for the JetBrains Runtime custom title bar. The JBR API exists only on some runtimes, so detection,
 * installation and property updates are all guarded: a failure returns an unavailable handle or logs at debug level,
 * and the plain Swing window keeps working.
 */
public final class JbrWindowDecorations {

    private static final Logger LOG = LoggerFactory.getLogger(JbrWindowDecorations.class);

    private JbrWindowDecorations() {
    }

    /** Returns whether the runtime supports JBR custom title bars. */
    public static boolean isSupported() {
        try {
            return JBR.isAvailable()
                    && JBR.isWindowDecorationsSupported()
                    && JBR.getWindowDecorations() != null;
        }
        catch (Throwable e) {
            LOG.debug("JBR window decorations are not available", e);
            return false;
        }
    }

    /**
     * Installs a custom title bar of minimal height, so the main window can read the system button insets early and
     * set the real header height later.
     */
    public static TitleBarHandle primeCustomTitleBar(Frame frame, boolean darkControls) {
        return createAndInstallCustomTitleBar(frame, 1f, darkControls);
    }

    /** Installs a JBR custom title bar of the given height and returns a handle for later updates. */
    public static TitleBarHandle installCustomTitleBar(Frame frame, float height, boolean darkControls) {
        return createAndInstallCustomTitleBar(frame, height, darkControls);
    }

    private static TitleBarHandle createAndInstallCustomTitleBar(Frame frame, float height, boolean darkControls) {
        if (frame == null) {
            return TitleBarHandle.missing();
        }
        try {
            if (!JBR.isAvailable() || !JBR.isWindowDecorationsSupported()) {
                return TitleBarHandle.missing();
            }
            WindowDecorations decorations = JBR.getWindowDecorations();
            if (decorations == null) {
                return TitleBarHandle.missing();
            }
            WindowDecorations.CustomTitleBar titleBar = decorations.createCustomTitleBar();
            TitleBarHandle handle = new TitleBarHandle(decorations, titleBar, frame);
            handle.setHeight(height);
            handle.putProperty("controls.visible", Boolean.TRUE);
            handle.setControlsDark(darkControls);
            handle.install();
            return handle;
        }
        catch (Throwable e) {
            LOG.debug("Failed to install JBR custom title bar", e);
            return TitleBarHandle.missing();
        }
    }

    /** Handle to a custom title bar; when unavailable, every operation is a no-op. */
    public static final class TitleBarHandle {

        private static final TitleBarHandle MISSING = new TitleBarHandle(null, null, null);

        private final WindowDecorations decorations;
        private final WindowDecorations.CustomTitleBar titleBar;
        private final Frame frame;

        private TitleBarHandle(WindowDecorations decorations, WindowDecorations.CustomTitleBar titleBar, Frame frame) {
            this.decorations = decorations;
            this.titleBar = titleBar;
            this.frame = frame;
        }

        /** Returns the unavailable handle, so callers need no null checks. */
        public static TitleBarHandle missing() {
            return MISSING;
        }

        /** Returns whether the handle is bound to a JBR title bar and a window. */
        public boolean isAvailable() {
            return decorations != null && titleBar != null && frame != null;
        }

        /** Applies the title bar to the window. */
        public void install() {
            if (!isAvailable()) {
                return;
            }
            try {
                decorations.setCustomTitleBar(frame, titleBar);
            }
            catch (Throwable e) {
                LOG.debug("Failed to apply JBR custom title bar", e);
            }
        }

        /** Sets the height of the title bar's hit-test area; non-positive values are ignored. */
        public void setHeight(float height) {
            if (!isAvailable() || height <= 0) {
                return;
            }
            try {
                titleBar.setHeight(height);
            }
            catch (Throwable e) {
                LOG.debug("Failed to update JBR custom title bar height", e);
            }
        }

        /** Sets whether the system window buttons use their dark-theme look, for a dark title bar. */
        public void setControlsDark(boolean dark) {
            putProperty("controls.dark", dark);
        }

        /** Sets the background of the system window buttons when normal, hovered and pressed. */
        public void setControlsBackground(Color normal, Color hovered, Color pressed) {
            putProperty("controls.background.normal", normal);
            putProperty("controls.background.hovered", hovered);
            putProperty("controls.background.pressed", pressed);
        }

        /** Sets a JBR title bar property; a property the runtime rejects is logged at debug level. */
        public void putProperty(String key, Object value) {
            if (!isAvailable() || key == null) {
                return;
            }
            try {
                titleBar.putProperty(key, value);
            }
            catch (Throwable e) {
                LOG.debug("Failed to update JBR custom title bar property: {}", key, e);
            }
        }

        /** Returns the title bar properties, or an empty map when unavailable or on failure. */
        public Map<String, Object> getProperties() {
            if (!isAvailable()) {
                return Collections.emptyMap();
            }
            try {
                return titleBar.getProperties();
            }
            catch (Throwable e) {
                LOG.debug("Failed to read JBR custom title bar properties", e);
                return Collections.emptyMap();
            }
        }

        /** Returns the left inset taken by the system buttons, or 0 on failure. */
        public int getLeftInset() {
            if (!isAvailable()) {
                return 0;
            }
            try {
                return Math.max(0, Math.round(titleBar.getLeftInset()));
            }
            catch (Throwable e) {
                LOG.debug("Failed to read JBR custom title bar left inset", e);
                return 0;
            }
        }

        /** Returns the right inset taken by the system buttons, or 0 on failure. */
        public int getRightInset() {
            if (!isAvailable()) {
                return 0;
            }
            try {
                return Math.max(0, Math.round(titleBar.getRightInset()));
            }
            catch (Throwable e) {
                LOG.debug("Failed to read JBR custom title bar right inset", e);
                return 0;
            }
        }

        /** Forces whether the title bar's hit test treats the area as client area. */
        public void forceHitTest(boolean client) {
            if (!isAvailable()) {
                return;
            }
            try {
                titleBar.forceHitTest(client);
            }
            catch (Throwable e) {
                LOG.debug("Failed to force JBR title bar hit test", e);
            }
        }

        /** Removes the custom title bar from the window. */
        public void uninstall() {
            if (!isAvailable()) {
                return;
            }
            try {
                decorations.setCustomTitleBar(frame, null);
            }
            catch (Throwable e) {
                LOG.debug("Failed to uninstall JBR custom title bar", e);
            }
        }
    }
}
